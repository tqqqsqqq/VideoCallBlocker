package com.harris.videocallblocker

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 삼성 갤럭시 등에서 TelecomManager.endCall()이 제조사 정책으로 막혀 있을 때,
 * 통화 화면의 '종료(끊기)' 버튼을 시스템 권한으로 자동 클릭하여 확실히 통화를 종료시키는 서비스.
 * 또한 통화 화면에 표시되는 상대방 이름(예: '아빠') 및 전화번호를 실시간으로 감지합니다.
 */
class CallBlockerAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        val pkg = event?.packageName?.toString()?.lowercase() ?: ""
        if (pkg.contains("incallui") || pkg.contains("dialer") || pkg.contains("phone") || pkg.contains("telecom")) {
            extractCallerInfo(root)
        }
    }

    override fun onInterrupt() {}

    private fun extractCallerInfo(root: AccessibilityNodeInfo) {
        try {
            val texts = mutableListOf<String>()
            collectTexts(root, texts)

            for (text in texts) {
                val clean = text.trim()
                if (clean.length in 1..25) {
                    // 번호 패턴 매칭 (010, 02, +82 등)
                    val digits = clean.filter { it.isDigit() }
                    if (digits.length in 8..15 && digits.startsWith("0") || digits.startsWith("82")) {
                        if (lastCallerNumber.isNullOrBlank()) {
                            lastCallerNumber = clean
                            Log.i(TAG, "Captured caller number from screen: $clean")
                        }
                    } else if (!isSystemCallText(clean) && lastCallerName.isNullOrBlank()) {
                        // "아빠", "엄마" 등 연락처 이름일 가능성 높음
                        lastCallerName = clean
                        Log.i(TAG, "Captured caller name from screen: $clean")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractCallerInfo error", e)
        }
    }

    private fun isSystemCallText(text: String): Boolean {
        val t = text.lowercase()
        val ignoreList = listOf(
            "영상통화", "통화", "hd", "volte", "연결 중", "통화 중", "음소거",
            "스피커", "키패드", "블루투스", "카메라", "화면", "종료", "끊기", "녹음",
            "대기", "전환", "추가", "메시지", "영상", "call", "dialing", "connected"
        )
        return ignoreList.any { t.contains(it) }
    }

    private fun collectTexts(node: AccessibilityNodeInfo?, list: MutableList<String>) {
        if (node == null) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { list.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { list.add(it) }
        for (i in 0 until node.childCount) {
            collectTexts(node.getChild(i), list)
        }
    }

    /**
     * 현재 화면(통화 화면)에서 통화 종료 버튼을 찾아 클릭합니다.
     * @return 클릭 성공 여부
     */
    fun disconnectCurrentCall(): Boolean {
        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "rootInActiveWindow is null")
            return false
        }

        try {
            // 1. 삼성 및 AOSP InCallUI의 대표적인 종료 버튼 리소스 ID 검색
            val knownIds = listOf(
                "com.samsung.android.incallui:id/disconnect_button",
                "com.samsung.android.incallui:id/end_call_button",
                "com.samsung.android.incallui:id/end_call",
                "com.android.incallui:id/disconnect_button",
                "com.android.incallui:id/end_call_button",
                "com.google.android.dialer:id/incall_end_call",
                "disconnect_button",
                "end_call_button",
                "end_call"
            )

            for (id in knownIds) {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                for (node in nodes) {
                    if (clickNodeOrParent(node)) {
                        Log.i(TAG, "Successfully clicked end call button by id: $id")
                        return true
                    }
                }
            }

            // 2. 텍스트 및 ContentDescription으로 검색
            val targetTexts = listOf("통화 종료", "통화종료", "통화 끊기", "종료", "End call", "Disconnect", "End")
            for (text in targetTexts) {
                val nodes = root.findAccessibilityNodeInfosByText(text)
                for (node in nodes) {
                    if (clickNodeOrParent(node)) {
                        Log.i(TAG, "Successfully clicked end call button by text: $text")
                        return true
                    }
                }
            }

            // 3. 재귀적으로 전체 트리를 순회하여 '종료' 또는 'end' 키워드가 들어간 뷰 클릭
            if (findAndClickRecursive(root)) {
                Log.i(TAG, "Successfully clicked end call button via recursive scan")
                return true
            }

            Log.w(TAG, "Could not find call disconnect button in current window")
        } catch (e: Exception) {
            Log.e(TAG, "Error while searching for end call button", e)
        }
        return false
    }

    private fun findAndClickRecursive(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val combined = "$text $desc".lowercase()

        if ((combined.contains("종료") || combined.contains("끊기") || combined.contains("end")) &&
            !combined.contains("시작") && !combined.contains("전환")
        ) {
            if (clickNodeOrParent(node)) return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (findAndClickRecursive(child)) return true
        }
        return false
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                val result = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (result) return true
            }
            current = current.parent
        }
        return false
    }

    companion object {
        private const val TAG = "VCB-A11y"
        var instance: CallBlockerAccessibilityService? = null
            private set

        @Volatile
        var lastCallerName: String? = null

        @Volatile
        var lastCallerNumber: String? = null

        fun clearCachedCaller() {
            lastCallerName = null
            lastCallerNumber = null
        }
    }
}
