package com.tivimatelite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tivimatelite.web.AppLogStore
import com.tivimatelite.web.BootPrefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!BootPrefs.isAutoStartEnabled(context)) return

        AppLogStore.i("BootReceiver", "BOOT_COMPLETED, launching app")
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
