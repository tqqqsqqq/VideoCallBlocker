package com.harris.videocallblocker

import android.annotation.SuppressLint
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.harris.videocallblocker.databinding.ActivitySettingsBinding

/** PIN 확인 후에만 진입하는 부모 설정 화면 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        b.swEnabled.isChecked = prefs.enabled
        b.etLimit.setText(prefs.limitMinutes.toString())
        b.etResetDay.setText(prefs.resetDay.toString())
        b.etParentPhone.setText(prefs.parentPhone)

        val period = prefs.currentPeriodStart()
        val logMin = Usage.callLogVideoSeconds(this, maxOf(period, prefs.manualResetAt)) / 60
        val trackedMin = prefs.trackedSeconds(period) / 60
        b.tvDetected.text = "현재 인식된 사용량: 통화기록 ${logMin}분 / 앱 자체집계 ${trackedMin}분 (큰 값 적용)"

        b.btnSave.setOnClickListener { save() }
        b.btnResetUsage.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("사용량 초기화")
                .setMessage("이번 달 집계를 0분으로 되돌릴까요? (지금 이후 통화부터 다시 집계)")
                .setPositiveButton("초기화") { _, _ ->
                    prefs.resetTracked(prefs.currentPeriodStart())
                    Toast.makeText(this, "초기화했어요", Toast.LENGTH_SHORT).show()
                    restartGuard(); finish()
                }
                .setNegativeButton("취소", null)
                .show()
        }
    }

    private fun save() {
        val limit = b.etLimit.text.toString().toIntOrNull()
        val day = b.etResetDay.text.toString().toIntOrNull()
        if (limit == null || day == null || day !in 1..28) {
            Toast.makeText(this, "한도와 집계일(1~28)을 확인하세요", Toast.LENGTH_SHORT).show()
            return
        }
        val newPin = b.etNewPin.text.toString()
        if (newPin.isNotEmpty() && newPin.length < 4) {
            Toast.makeText(this, "PIN은 4자리 이상", Toast.LENGTH_SHORT).show()
            return
        }

        prefs.limitMinutes = limit
        prefs.resetDay = day
        prefs.enabled = b.swEnabled.isChecked
        prefs.parentPhone = b.etParentPhone.text.toString()
        if (newPin.isNotEmpty()) prefs.setPin(newPin)

        // 집계일이 바뀌었을 수 있으니 새 기간 기준으로 보정값 저장
        b.etUsedNow.text.toString().toIntOrNull()?.let { min ->
            prefs.setTrackedSeconds(prefs.currentPeriodStart(), min * 60L)
        }

        restartGuard()
        Toast.makeText(this, "저장했어요", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun restartGuard() {
        GuardService.stop(this)
        if (prefs.enabled) GuardService.start(this)
    }
}
