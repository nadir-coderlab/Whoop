package com.mizani.tv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.service.dreams.DreamService
import android.view.KeyEvent
import android.view.View

/**
 * شاشة توقف سودا: بعد دقايق بدون استخدام تصير الشاشة سودا كأن التلفزيون طافي،
 * والبوكس يظل صاحي ويسمع للميزان (زر الباور في الريموت ينيّم البوكس نوم عميق وما يسمع).
 * تنختار من إعدادات البوكس ← شاشة التوقف، وأي زر في الريموت يرجّع الصورة.
 */
class BlackDream : DreamService() {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false      // أي زر يرجّع الصورة
        isFullscreen = true
        isScreenBright = false
        setContentView(View(this).apply { setBackgroundColor(Color.BLACK) })
    }
}

/** زر «شاشة سودا الحين»: نفس الشاشة السودا على طول بدون انتظار، وأي زر يرجّع. */
class BlackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        setContentView(View(this).apply { setBackgroundColor(Color.BLACK) })
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) finish()
        return true
    }
}
