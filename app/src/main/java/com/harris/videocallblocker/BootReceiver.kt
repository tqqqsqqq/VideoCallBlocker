package com.harris.videocallblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 재부팅/앱 업데이트 후 감시 서비스 자동 재시작 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> GuardService.start(context)
        }
    }
}
