package com.harris.videocallblocker

import android.Manifest
import android.content.Context
import android.os.Build
import android.provider.CallLog
import android.telephony.SmsManager
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 영상통화 종료 시 부모에게 SMS 보고 */
object SmsReporter {
    private const val TAG = "VCB-SMS"

    /**
     * @param sessionWallStart 영상통화 감지 시각(ms)
     * @param sessionSeconds   앱이 잰 통화시간(통화기록에 값이 없을 때 사용)
     * @param blocked          한도 초과로 앱이 끊었는지
     */
    fun reportVideoCall(context: Context, sessionWallStart: Long, sessionSeconds: Long, blocked: Boolean) {
        val prefs = Prefs(context)
        val to = prefs.parentPhone
        if (to.isBlank()) return

        // 통화기록에서 상대 번호/이름/실제 통화시간 조회 (통화 직후라 몇 초 지연 후 호출됨)
        var number = "알 수 없음"
        var name: String? = null
        var duration = sessionSeconds
        if (Usage.has(context, Manifest.permission.READ_CALL_LOG)) {
            try {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DURATION),
                    "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.TYPE} = ?",
                    arrayOf((sessionWallStart - 60_000).toString(), CallLog.Calls.OUTGOING_TYPE.toString()),
                    "${CallLog.Calls.DATE} DESC"
                )?.use { c ->
                    if (c.moveToFirst()) {
                        number = c.getString(0)?.takeIf { it.isNotBlank() } ?: number
                        name = c.getString(1)?.takeIf { it.isNotBlank() }
                        val logDur = c.getLong(2)
                        if (logDur > 0) duration = logDur
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "call log query failed", e)
            }
        }

        val usedMin = Usage.usedSeconds(context, prefs) / 60
        val limit = prefs.limitMinutes
        val time = SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(sessionWallStart))
        val who = if (name != null) "$name($number)" else number

        val msg = buildString {
            append("[영상통화 알림]\n")
            append("$time 발신\n")
            append("상대: $who\n")
            append("통화: ${fmt(duration)}")
            if (blocked) append(" (한도초과 자동종료)")
            append("\n이번달: ${usedMin}/${limit}분")
        }
        send(context, to, msg)
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
            Log.i(TAG, "sms sent to $to")
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
