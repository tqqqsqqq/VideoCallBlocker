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

        // 1. 통화기록(CallLog)에서 최근 통화 정보 조회
        if (Usage.has(context, Manifest.permission.READ_CALL_LOG)) {
            try {
                // 영상통화 시작 전후 3분 이내의 최신 발신 기록 1건 조회
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DURATION),
                    "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.TYPE} = ?",
                    arrayOf((sessionWallStart - 180_000).toString(), CallLog.Calls.OUTGOING_TYPE.toString()),
                    "${CallLog.Calls.DATE} DESC LIMIT 1"
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val logNumber = c.getString(0)
                        val logName = c.getString(1)
                        val logDur = c.getLong(2)

                        // 통신사 영상통화 게이트웨이(070 등)로 라우팅된 경우 처리
                        if (!logNumber.isNullOrBlank()) {
                            val isGateway = logNumber.startsWith("070") || logNumber.startsWith("050")
                            if (!isGateway || number.isNullOrBlank()) {
                                number = logNumber
                            }
                        }
                        if (!logName.isNullOrBlank()) {
                            name = logName
                        }
                        if (logDur > 0) duration = logDur
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "call log query failed", e)
            }
        }

        // 2. 만약 아직 정보가 완전히 준비되지 않은 경우 2초 후 1회 재시도
        if (number.isNullOrBlank() && name.isNullOrBlank() && retryCount < 1) {
            Log.i(TAG, "Caller info not ready yet, retrying in 2s...")
            handler.postDelayed({
                reportVideoCall(context, sessionWallStart, sessionSeconds, blocked, retryCount + 1)
            }, 2000L)
            return
        }

        // 3. 번호는 있는데 이름이 비어있으면 연락처(Contacts)에서 역조회 ("아빠" 등)
        if (!number.isNullOrBlank() && name.isNullOrBlank() && !number!!.startsWith("070")) {
            name = lookupContactName(context, number!!)
        }

        // 4. 반대로 이름("아빠")은 있는데 번호가 비어있거나 070 게이트웨이 번호인 경우:
        //    주소록에서 "아빠" 이름으로 실제 010 휴대폰 번호를 찾아냄!
        if (!name.isNullOrBlank() && (number.isNullOrBlank() || number!!.startsWith("070"))) {
            val realNumber = lookupNumberByName(context, name!!)
            if (!realNumber.isNullOrBlank()) {
                number = realNumber
            }
        }

        // 5. 상대방 표기 형식 결정 (070 통신사 게이트웨이 번호는 노출하지 않음)
        val who = when {
            !name.isNullOrBlank() && !number.isNullOrBlank() && !number!!.startsWith("070") -> {
                "$name($number)"
            }
            !name.isNullOrBlank() -> {
                name!!
            }
            !number.isNullOrBlank() && !number!!.startsWith("070") -> {
                number!!
            }
            else -> {
                "알 수 없음"
            }
        }

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

    /** 전화번호로 주소록의 표시 이름("아빠" 등) 조회 */
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

    /** 주소록에서 이름("아빠")으로 실제 휴대폰 번호 조회 */
    private fun lookupNumberByName(context: Context, contactName: String): String? {
        if (!Usage.has(context, Manifest.permission.READ_CONTACTS)) return null
        return try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?",
                arrayOf(contactName),
                "${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC LIMIT 1"
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "number lookup by name failed", e)
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
