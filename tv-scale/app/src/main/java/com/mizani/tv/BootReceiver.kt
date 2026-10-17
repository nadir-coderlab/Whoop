package com.mizani.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** أول ما يشتغل البوكس (أو يتحدّث التطبيق) تشتغل خدمة الميزان لحالها */
class BootReceiver : BroadcastReceiver() {
    private val actions = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED, "android.intent.action.QUICKBOOT_POWERON")

    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action !in actions) return
        if (ScaleService.hasPermissions(ctx)) ScaleService.start(ctx)
    }
}
