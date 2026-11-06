package com.fakelag.vpn

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient

class OverlayService : Service() {

    companion object {
        const val ACTION_START = "com.fakelag.overlay.START"
        const val ACTION_STOP  = "com.fakelag.overlay.STOP"
    }

    private lateinit var wm: WindowManager
    private var webView: WebView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            removeOverlay()
            stopSelf()
            return START_NOT_STICKY
        }
        showOverlay()
        return START_STICKY
    }

    private fun showOverlay() {
        if (webView != null) return

        val wv = WebView(this).apply {
            settings.apply {
                javaScriptEnabled   = true
                domStorageEnabled   = true
                allowFileAccess     = true
                allowContentAccess  = true
            }
            setBackgroundColor(0x00000000) // transparente
            webViewClient = WebViewClient()
            addJavascriptInterface(LagBridge(), "FakeLagBridge")
            loadUrl("file:///android_asset/overlay.html")
        }

        val params = WindowManager.LayoutParams(
            dpToPx(210),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dpToPx(8)
            y = dpToPx(60)
        }

        wm.addView(wv, params)
        webView = wv
    }

    private fun removeOverlay() {
        webView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        webView = null
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }

    private fun dpToPx(dp: Int) =
        (dp * resources.displayMetrics.density).toInt()

    // ── Bridge JS ↔ FakeLagVpnService ─────────────────────────
    inner class LagBridge {

        @JavascriptInterface
        fun toggleService(active: Boolean) {
            val svc = Intent(this@OverlayService, FakeLagVpnService::class.java)
            svc.action = if (active) FakeLagVpnService.ACTION_START
                         else        FakeLagVpnService.ACTION_STOP
            startService(svc)
        }

        @JavascriptInterface
        fun setConfig(delay: Long, loss: Int, jitter: Long) {
            FakeLagVpnService.instance?.also {
                it.delayMs  = delay
                it.lossPct  = loss
                it.jitterMs = jitter
            }
        }

        @JavascriptInterface
        fun getStatus(): String {
            val svc = FakeLagVpnService.instance
            return """{"lat":${svc?.lastLatMs ?: 0},"dropped":${svc?.droppedCount ?: 0},"sent":${svc?.sentCount ?: 0}}"""
        }
    }
}
