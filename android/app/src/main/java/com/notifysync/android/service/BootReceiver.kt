package com.notifysync.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.notifysync.android.App

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as? App ?: return
        if (!app.prefs.bootStart || !app.prefs.masterEnabled) return
        SyncService.start(context)
    }
}
