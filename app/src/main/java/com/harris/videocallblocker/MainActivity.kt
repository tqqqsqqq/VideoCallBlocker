package com.harris.videocallblocker

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.harris.videocallblocker.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            GuardService.start(this); refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        GuardService.createChannels(this)

        b.btnParent.setOnClickListener { askPin { startActivity(Intent(this, SettingsActivity::class.java)) } }

        if (!prefs.hasPin) createPinDialog()
    }

    override fun onResume() {
        super.onResume()
        if (Usage.hasPhonePerms(this)) GuardService.start(this)
        refresh()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private val refresher = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 5_000) }
    }

    // ---------------- dashboard ----------------
    @SuppressLint("SetTextI18n")
    private fun refresh() {
        val limit = prefs.limitMinutes
        val usedMin = (Usage.usedSeconds(this, prefs) / 60).toInt()
        val ratio = if (limit == 0) 1f else usedMin.toFloat() / limit

        b.tvUsed.text = usedMin.toString()
        b.tvLimit.text = "/ ${limit}분"
        b.ring.setProgressCompat((ratio.coerceAtMost(1f) * 1000).toInt(), true)

        val color = when {
            ratio >= 1f -> R.color.danger
            ratio >= 0.8f -> R.color.warn
            else -> R.color.accent
        }
        b.ring.setIndicatorColor(ContextCompat.getColor(this, color))

        val df = SimpleDateFormat("M월 d일", Locale.KOREA)
        b.tvPeriod.text = "${df.format(prefs.currentPeriodStart())}부터 집계 중 · 매월 ${prefs.resetDay}일 초기화"

        val (statusText, statusColor) = when {
            !prefs.enabled -> "○ 보호 꺼짐" to R.color.text_dim
            !Usage.hasPhonePerms(this) -> "! 권한 필요" to R.color.warn
            ratio >= 1f -> "● 한도 초과 · 영상통화 차단 중" to R.color.danger
            else -> "● 보호 중" to R.color.ok
        }
        b.tvStatus.text = statusText
        b.tvStatus.setTextColor(ContextCompat.getColor(this, statusColor))
        b.tvRemain.text = if (ratio >= 1f) "다음 집계일에 자동으로 다시 열려요"
        else "남은 영상통화 ${limit - usedMin}분"

        renderPerms()
    }

    private data class PermItem(val title: String, val desc: String, val granted: Boolean, val action: () -> Unit)

    private fun renderPerms() {
        val items = mutableListOf(
            PermItem("전화 · 통화기록 · 문자", "영상통화 감지 및 종료, 부모 문자 알림", Usage.hasPhonePerms(this)) {
                permLauncher.launch(Usage.PHONE_PERMS)
            },
            PermItem("사용정보 접근", "음성통화 중 카메라 사용을 영상통화로 오인하지 않기", Usage.hasUsageAccess(this)) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            },
            PermItem("배터리 최적화 제외", "백그라운드에서 꺼지지 않게", Usage.ignoresBatteryOpt(this)) {
                @SuppressLint("BatteryLife")
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                startActivity(i)
            },
        )
        if (Build.VERSION.SDK_INT >= 33) {
            items += PermItem("알림", "한도 경고 / 차단 알림", Usage.has(this, Manifest.permission.POST_NOTIFICATIONS)) {
                permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }

        b.permList.removeAllViews()
        val dp = resources.displayMetrics.density
        items.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
            }
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(this).apply {
                text = (if (item.granted) "✓  " else "•  ") + item.title
                setTextColor(ContextCompat.getColor(context, if (item.granted) R.color.ok else R.color.text))
                textSize = 15f
            })
            texts.addView(TextView(this).apply {
                text = item.desc
                setTextColor(ContextCompat.getColor(context, R.color.text_dim))
                textSize = 12f
            })
            row.addView(texts)
            if (!item.granted) {
                row.addView(MaterialButton(this).apply {
                    text = "허용"
                    cornerRadius = (12 * dp).toInt()
                    backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.accent))
                    setOnClickListener { item.action() }
                })
            }
            b.permList.addView(row)
        }
        b.cardPerms.alpha = if (items.all { it.granted }) 0.7f else 1f
    }

    // ---------------- PIN ----------------
    private fun pinField(hint: String) = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        setPadding(48, 36, 48, 36)
    }

    private fun createPinDialog() {
        val et = pinField("부모 PIN 4자리 이상")
        MaterialAlertDialogBuilder(this)
            .setTitle("부모 PIN 만들기")
            .setMessage("설정 변경이나 보호 해제 시 필요해요. 아이에게 알려주지 마세요.")
            .setView(et)
            .setCancelable(false)
            .setPositiveButton("확인") { _, _ ->
                val pin = et.text.toString()
                if (pin.length < 4) {
                    Toast.makeText(this, "4자리 이상 입력하세요", Toast.LENGTH_SHORT).show()
                    createPinDialog()
                } else {
                    prefs.setPin(pin)
                    permLauncher.launch(Usage.PHONE_PERMS)
                }
            }
            .show()
    }

    private fun askPin(onOk: () -> Unit) {
        val et = pinField("PIN")
        MaterialAlertDialogBuilder(this)
            .setTitle("부모 PIN 입력")
            .setView(et)
            .setPositiveButton("확인") { _, _ ->
                if (prefs.checkPin(et.text.toString())) onOk()
                else Toast.makeText(this, "PIN이 틀렸어요", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
