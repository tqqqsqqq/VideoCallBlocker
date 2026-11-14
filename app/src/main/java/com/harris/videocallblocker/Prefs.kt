package com.harris.videocallblocker

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar

/** 앱 설정 + 자체 집계 사용량 저장소 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("vcb_prefs", Context.MODE_PRIVATE)

    /** 월 허용 영상통화 시간(분). 통신사 300분보다 여유 있게 기본 250분 */
    var limitMinutes: Int
        get() = sp.getInt(KEY_LIMIT, 250)
        set(v) = sp.edit().putInt(KEY_LIMIT, v.coerceIn(0, 10_000)).apply()

    /** 집계 시작일(매월 n일). 통신사 청구 기준은 보통 1일 */
    var resetDay: Int
        get() = sp.getInt(KEY_RESET_DAY, 1)
        set(v) = sp.edit().putInt(KEY_RESET_DAY, v.coerceIn(1, 28)).apply()

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_ENABLED, v).apply()

    /** 영상통화 보고 문자를 받을 부모 번호 (비어 있으면 문자 안 보냄) */
    var parentPhone: String
        get() = sp.getString("parent_phone", "") ?: ""
        set(v) = sp.edit().putString("parent_phone", v.filter { it.isDigit() || it == '+' }).apply()

    // ---------- 부모 PIN ----------
    val hasPin: Boolean get() = sp.contains(KEY_PIN_HASH)

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        sp.edit().putString(KEY_PIN_SALT, salt).putString(KEY_PIN_HASH, hash(salt, pin)).apply()
    }

    fun checkPin(pin: String): Boolean {
        val salt = sp.getString(KEY_PIN_SALT, null) ?: return false
        return sp.getString(KEY_PIN_HASH, null) == hash(salt, pin)
    }

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    // ---------- 자체 집계(통화기록에 영상 표시가 안 되는 기기 대비) ----------
    fun trackedSeconds(periodStart: Long): Long = sp.getLong("tracked_$periodStart", 0L)

    fun addTrackedSeconds(periodStart: Long, seconds: Long) {
        val key = "tracked_$periodStart"
        sp.edit().putLong(key, sp.getLong(key, 0L) + seconds).apply()
    }

    /** 부모가 직접 입력한 '이번 달 이미 사용한 시간'으로 보정 */
    fun setTrackedSeconds(periodStart: Long, seconds: Long) {
        sp.edit().putLong("tracked_$periodStart", seconds.coerceAtLeast(0L)).apply()
    }

    fun resetTracked(periodStart: Long) {
        sp.edit().remove("tracked_$periodStart").apply()
        // 통화기록 기반 사용량도 이 시점 이후부터만 세도록 기준점 이동
        sp.edit().putLong(KEY_MANUAL_RESET_AT, System.currentTimeMillis()).apply()
    }

    /** 부모가 '사용량 초기화'를 누른 시각(이번 집계기간 안에서만 유효) */
    val manualResetAt: Long get() = sp.getLong(KEY_MANUAL_RESET_AT, 0L)

    fun warnedFlag(periodStart: Long, level: Int): Boolean =
        sp.getBoolean("warned_${level}_$periodStart", false)

    fun setWarnedFlag(periodStart: Long, level: Int) =
        sp.edit().putBoolean("warned_${level}_$periodStart", true).apply()

    /** 현재 집계기간 시작 시각(ms) */
    fun currentPeriodStart(now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (cal.get(Calendar.DAY_OF_MONTH) < resetDay) cal.add(Calendar.MONTH, -1)
        cal.set(Calendar.DAY_OF_MONTH, resetDay)
        return cal.timeInMillis
    }

    companion object {
        private const val KEY_LIMIT = "limit_minutes"
        private const val KEY_RESET_DAY = "reset_day"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_MANUAL_RESET_AT = "manual_reset_at"
    }
}
