package com.harris.videocallblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 상시 실행되는 감시 서비스.
 *
 * 영상통화 판별 방법:
 *   (1) 통화 중(OFFHOOK)이고
 *   (2) 받은 전화가 아닌 '건 전화'이며  ← 통신사 300분은 발신만 차감
 *   (3) 카메라가 사용 중이고
 *   (4) 맨 앞 화면이 통화 앱(incallui 등)일 때
 * → 발신 영상통화로 간주하고 시간을 잰다. 한도를 넘으면 전화를 끊는다.
 *
 * 음성통화는 카메라를 쓰지 않으므로 절대 끊지 않는다.
 */
class GuardService : Service() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private var callState = TelephonyManager.CALL_STATE_IDLE
    private var ringingSeen = false            // 이번 통화가 받은 전화인지
    private val camerasInUse = mutableSetOf<String>()
    private var sessionStartElapsed = 0L        // 0이면 영상통화 세션 없음
    private var sessionWallStart = 0L          // 영상통화 시작 벽시계 시각(ms)
    private var baseUsedSeconds = 0L            // 세션 시작 시점의 누적 사용량

    private var telephonyCallback: TelephonyCallback? = null
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null

    // ---------------- lifecycle ----------------
    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannels(this)
        startForegroundCompat(buildStatusNotification())
        registerCamera()
        registerCallState()
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!prefs.enabled) {
            stopSelf(); return START_NOT_STICKY
        }
        updateStatusNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        finishSession(false)
        getSystemService(CameraManager::class.java).unregisterAvailabilityCallback(cameraCallback)
        val tm = getSystemService(TelephonyManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { tm.unregisterTelephonyCallback(it) }
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------- camera ----------------
    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            camerasInUse += cameraId
            evaluate("camera $cameraId in use")
        }

        override fun onCameraAvailable(cameraId: String) {
            camerasInUse -= cameraId
            // 영상통화 도중 카메라만 꺼도 통신사는 영상통화로 계속 과금 → 세션 유지
        }
    }

    private fun registerCamera() {
        getSystemService(CameraManager::class.java).registerAvailabilityCallback(cameraCallback, handler)
    }

    // ---------------- call state ----------------
    private fun registerCallState() {
        if (!Usage.has(this, android.Manifest.permission.READ_PHONE_STATE)) return
        val tm = getSystemService(TelephonyManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = onCallState(state)
                }
                tm.registerTelephonyCallback(ContextCompat.getMainExecutor(this), cb)
                telephonyCallback = cb
            } else {
                @Suppress("DEPRECATION")
                val l = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) = onCallState(state)
                }
                @Suppress("DEPRECATION")
                tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
                phoneStateListener = l
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "call state listen failed", e)
        }
    }

    private fun onCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> ringingSeen = true
            TelephonyManager.CALL_STATE_OFFHOOK -> evaluate("offhook")
            TelephonyManager.CALL_STATE_IDLE -> {
                finishSession(false)
                ringingSeen = false
            }
        }
        callState = state
        updateStatusNotification()
    }

    // ---------------- core logic ----------------
    private fun evaluate(reason: String) {
        if (!prefs.enabled || sessionStartElapsed != 0L) return
        if (callState != TelephonyManager.CALL_STATE_OFFHOOK && !isInCallSafe()) return
        if (ringingSeen) return                 // 받은 영상통화는 차감 대상 아님 → 건드리지 않음
        if (camerasInUse.isEmpty()) return
        if (!Usage.isInCallUiForeground(this)) return

        Log.i(TAG, "outgoing video call detected ($reason)")
        baseUsedSeconds = Usage.usedSeconds(this, prefs)
        sessionStartElapsed = SystemClock.elapsedRealtime()
        sessionWallStart = System.currentTimeMillis()
        checkLimit()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (sessionStartElapsed != 0L) {
                if (!isInCallSafe() && callState == TelephonyManager.CALL_STATE_IDLE) finishSession(false)
                else checkLimit()
            }
            updateStatusNotification()
            handler.postDelayed(this, if (sessionStartElapsed != 0L) 3_000L else 60_000L)
        }
    }

    private fun liveSeconds(): Long =
        if (sessionStartElapsed == 0L) 0L
        else (SystemClock.elapsedRealtime() - sessionStartElapsed) / 1000

    private fun checkLimit() {
        val limitSec = prefs.limitMinutes * 60L
        val used = baseUsedSeconds + liveSeconds()
        val period = prefs.currentPeriodStart()

        if (used >= limitSec) {
            Log.i(TAG, "limit reached: used=$used limit=$limitSec → ending call")
            endCall()
            notifyAlert(
                ID_BLOCKED,
                "영상통화가 종료되었어요",
                "이번 달 영상통화 한도(${prefs.limitMinutes}분)를 모두 사용했어요. 음성통화는 계속 가능해요."
            )
            finishSession(true)
            return
        }
        if (used >= limitSec * 0.8 && !prefs.warnedFlag(period, 80)) {
            prefs.setWarnedFlag(period, 80)
            val left = (limitSec - used) / 60
            notifyAlert(ID_WARN, "영상통화 한도 80% 사용", "이번 달 남은 영상통화: 약 ${left}분")
        }
    }

    private fun finishSession(blocked: Boolean) {
        if (sessionStartElapsed == 0L) return
        val secs = liveSeconds()
        val wallStart = sessionWallStart
        prefs.addTrackedSeconds(prefs.currentPeriodStart(), secs)
        Log.i(TAG, "video session finished: ${secs}s (blocked=$blocked)")
        sessionStartElapsed = 0L
        sessionWallStart = 0L
        updateStatusNotification()

        // 통화기록이 시스템에 기록될 수 있도록 2초 후 SMS 발송
        val appContext = applicationContext
        handler.postDelayed({
            SmsReporter.reportVideoCall(appContext, wallStart, secs, blocked)
        }, 2000L)
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun endCall() {
        try {
            val ok = getSystemService(TelecomManager::class.java).endCall()
            Log.i(TAG, "endCall -> $ok")
        } catch (e: Exception) {
            Log.e(TAG, "endCall failed", e)
        }
    }

    @Suppress("MissingPermission")
    private fun isInCallSafe(): Boolean = try {
        getSystemService(TelecomManager::class.java).isInCall
    } catch (_: SecurityException) {
        false
    }

    // ---------------- notifications ----------------
    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID_STATUS, n)
        }
    }

    private fun buildStatusNotification(): Notification {
        val used = if (sessionStartElapsed != 0L) baseUsedSeconds + liveSeconds()
        else Usage.usedSeconds(this, prefs)
        val limit = prefs.limitMinutes
        val usedMin = used / 60
        val text = if (sessionStartElapsed != 0L) "영상통화 중 · ${usedMin}/${limit}분 사용"
        else if (usedMin >= limit) "한도 초과 · 영상통화 차단 중 (${usedMin}/${limit}분)"
        else "이번 달 영상통화 ${usedMin}/${limit}분 사용"

        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("영상통화 지킴이")
            .setContentText(text)
            .setProgress(limit.coerceAtLeast(1), usedMin.toInt().coerceAtMost(limit), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateStatusNotification() {
        getSystemService(NotificationManager::class.java).notify(ID_STATUS, buildStatusNotification())
    }

    private fun notifyAlert(id: Int, title: String, text: String) {
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(id, n)
    }

    companion object {
        private const val TAG = "VCB"
        private const val CH_STATUS = "status"
        private const val CH_ALERT = "alert"
        private const val ID_STATUS = 1
        private const val ID_WARN = 2
        private const val ID_BLOCKED = 3

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_STATUS, "보호 상태", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_ALERT, "한도 알림", NotificationManager.IMPORTANCE_HIGH)
            )
        }

        fun start(ctx: Context) {
            if (!Prefs(ctx).enabled) return
            ContextCompat.startForegroundService(ctx, Intent(ctx, GuardService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GuardService::class.java))
        }
    }
}
