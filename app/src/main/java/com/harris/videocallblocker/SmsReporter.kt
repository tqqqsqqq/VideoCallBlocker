package com.harris.videocallblocker

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 영상통화 종료 시 부모에게 SMS 보고 */
object SmsReporter {
    private const val TAG = "VCB-SMS"
    private val handler = Handler(Looper.getMainLooper())

    /**
     * @param sessionWallStart 영상통화 감지 시각(ms)
     * @param sessionSeconds   앱이 잰 통화시간
     * @param blocked          한도 초과로 앱이 끊었는지
     * @param retryCount       재시도 횟수
     */
    fun reportVideoCall(
        context: Context,
        sessionWallStart: Long,
        sessionSeconds: Long,
        blocked: Boolean,
        retryCount: Int = 0
    ) {
        val prefs = Prefs(context)
        val to = prefs.parentPhone
        if (to.isBlank()) return

        var number: String? = CallBlockerAccessibilityService.lastCallerNumber
        var name: String? = CallBlockerAccessibilityService.lastCallerName
        var duration = sessionSeconds

        // 1. 통화기록에서 번호/이름/시간 조회
        if (Usage.has(context, Manifest.permission.READ_CALL_LOG)) {
            try {
                // 최근 3분 이내의 발신 통화 중 최신 1건 조회
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DURATION),
                    "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.TYPE} = ?",
                    arrayOf((sessionWallStart - 180_000).toString(), CallLog.Calls.OUTGOING_TYPE.toString()),
                    "${CallLog.Calls.DATE} DESC LIMIT 1"
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val logNumber = c.getString(0)
                        if (!logNumber.isNullOrBlank()) number = logNumber
                        val logName = c.getString(1)
                        if (!logName.isNullOrBlank()) name = logName
                        val logDur = c.getLong(2)
                        if (logDur > 0) duration = logDur
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "call log query failed", e)
            }
        }

        // 2. 통화기록에 아직 반영되지 않아 번호가 없으면 2.5초 후 1회 재시도
        if (number.isNullOrBlank() && retryCount < 1) {
            Log.i(TAG, "Caller info not ready yet, retrying in 2.5s...")
            handler.postDelayed({
                reportVideoCall(context, sessionWallStart, sessionSeconds, blocked, retryCount + 1)
            }, 2500L)
            return
        }

        // 3. 번호는 있는데 이름이 없으면 연락처(Contacts)에서 역조회 ("아빠" 등)
        if (!number.isNullOrBlank() && name.isNullOrBlank()) {
            name = lookupContactName(context, number!!)
        }

        val displayNum = number ?: "알 수 없음"
        val who = if (!name.isNullOrBlank()) "$name($displayNum)" else displayNum

        val usedMin = Usage.usedSeconds(context, prefs) / 60
        val limit = prefs.limitMinutes
        val time = SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(sessionWallStart))

        val msg = buildString {
            append("[영상통화 알림]\n")
            append("$time 발신\n")
            append("상대: $who\n")
            append("통화: ${fmt(duration)}")
            if (blocked) append(" (한도초과 자동종료)")
            append("\n이번달: ${usedMin}/${limit}분")
        }

        send(context, to, msg)
        CallBlockerAccessibilityService.clearCachedCaller()
    }

    private fun lookupContactName(context: Context, phoneNumber: String): String? {
        if (!Usage.has(context, Manifest.permission.READ_CONTACTS)) return null
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "contact lookup failed", e)
            null
        }
    }

    fun send(context: Context, to: String, msg: String): Boolean {
        if (!Usage.has(context, Manifest.permission.SEND_SMS)) {
            Log.w(TAG, "SEND_SMS not granted"); return false
        }
        return try {
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                context.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
            val parts = sms.divideMessage(msg)
            sms.sendMultipartTextMessage(to, null, parts, null, null)
            Log.i(TAG, "sms sent to $to: $msg")
            true
        } catch (e: Exception) {
            Log.e(TAG, "sms failed", e); false
        }
    }

    private fun fmt(sec: Long): String {
        val m = sec / 60; val s = sec % 60
        return if (m > 0) "${m}분 ${s}초" else "${s}초"
    }
}
