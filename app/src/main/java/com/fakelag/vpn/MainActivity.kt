package com.fakelag.vpn

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var lagActive = false

    private val vpnLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) checkOverlayAndStart()
        }

    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) startAll()
            else toast("Permissão de overlay negada")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btn_main).setOnClickListener {
            if (lagActive) stopAll() else requestPermissionsAndStart()
        }
    }

    private fun requestPermissionsAndStart() {
        // 1. VPN permission
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnLauncher.launch(vpnIntent)
        } else {
            checkOverlayAndStart()
        }
    }

    private fun checkOverlayAndStart() {
        // 2. Overlay permission
        if (!Settings.canDrawOverlays(this)) {
            overlayLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                       Uri.parse("package:$packageName"))
            )
        } else {
            startAll()
        }
    }

    private fun startAll() {
        // Inicia VPN service
        startService(Intent(this, FakeLagVpnService::class.java).apply {
            action = FakeLagVpnService.ACTION_START
            putExtra(FakeLagVpnService.EXTRA_DELAY,  200L)
            putExtra(FakeLagVpnService.EXTRA_LOSS,   5)
            putExtra(FakeLagVpnService.EXTRA_JITTER, 50L)
        })

        // Inicia overlay flutuante
        startService(Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
        })

        lagActive = true
        findViewById<Button>(R.id.btn_main).text = "DESATIVAR"
        toast("FakeLag ativo — abra o Free Fire")

        // Minimiza o app pra ir pro jogo
        moveTaskToBack(true)
    }

    private fun stopAll() {
        startService(Intent(this, FakeLagVpnService::class.java).apply {
            action = FakeLagVpnService.ACTION_STOP
        })
        startService(Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        })

        lagActive = false
        findViewById<Button>(R.id.btn_main).text = "ATIVAR FAKE LAG"
        toast("FakeLag desativado")
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
