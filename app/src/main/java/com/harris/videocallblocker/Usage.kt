package com.harris.videocallblocker

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.Process
import android.provider.CallLog
import androidx.core.content.ContextCompat

/** 영상통화 사용량 계산 + 권한 상태 확인 유틸 */
object Usage {

    /** 이번 집계기간의 '발신' 영상통화 사용 초 (통신사 300분은 발신 기준) */
    fun usedSeconds(context: Context, prefs: Prefs, liveSessionSeconds: Long = 0L): Long {
        val periodStart = prefs.currentPeriodStart()
        val since = maxOf(periodStart, prefs.manualResetAt)
        val fromLog = callLogVideoSeconds(context, since)
        val tracked = prefs.trackedSeconds(periodStart)
        // 통화기록과 자체집계 중 큰 값을 사용 (보수적으로)
        return maxOf(fromLog, tracked) + liveSessionSeconds
    }

    fun callLogVideoSeconds(context: Context, since: Long): Long {
        if (!has(context, Manifest.permission.READ_CALL_LOG)) return 0L
        var total = 0L
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.FEATURES, CallLog.Calls.DURATION),
                "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.TYPE} = ?",
                arrayOf(since.toString(), CallLog.Calls.OUTGOING_TYPE.toString()),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val features = c.getInt(0)
                    if (features and CallLog.Calls.FEATURES_VIDEO != 0) total += c.getLong(1)
                }
            }
        } catch (_: Exception) {
        }
        return total
    }

    // ---------- 통화 화면 판별 ----------
    private val IN_CALL_KEYWORDS = listOf("incallui", "dialer", "telecom", "phone", "vtcall", "ims")

    /**
     * 가장 최근 전면에 나온 앱이 통화 화면인지.
     * 음성통화 중 아이가 카메라 앱을 켠 경우 등을 영상통화로 오인하지 않기 위함.
     * 사용정보 접근 권한이 없으면 true(판별 불가 → 통화로 간주).
     */
    fun isInCallUiForeground(context: Context): Boolean {
        if (!hasUsageAccess(context)) return true
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return true
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - 10 * 60_000L, end)
        val ev = UsageEvents.Event()
        var lastPkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED) lastPkg = ev.packageName
        }
        val pkg = lastPkg?.lowercase() ?: return true
        return IN_CALL_KEYWORDS.any { pkg.contains(it) }
    }

    // ---------- 권한 ----------
    fun has(context: Context, perm: String) =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    fun hasUsageAccess(context: Context): Boolean {
        val aom = context.getSystemService(AppOpsManager::class.java)
        val mode = aom.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun ignoresBatteryOpt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

    val PHONE_PERMS = arrayOf(
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.SEND_SMS,
    )

    fun hasPhonePerms(context: Context) = PHONE_PERMS.all { has(context, it) }
}
