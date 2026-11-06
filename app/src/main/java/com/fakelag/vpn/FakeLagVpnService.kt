package com.fakelag.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.PriorityQueue
import kotlin.random.Random
import kotlinx.coroutines.*

class FakeLagVpnService : VpnService() {

    companion object {
        const val ACTION_START   = "com.fakelag.START"
        const val ACTION_STOP    = "com.fakelag.STOP"
        const val EXTRA_DELAY    = "delay_ms"
        const val EXTRA_LOSS     = "loss_pct"
        const val EXTRA_JITTER   = "jitter_ms"
        const val CHANNEL_ID     = "fakelag_channel"
        const val NOTIF_ID       = 42

        // Instância viva — usada pelo OverlayService para atualizar em tempo real
        @Volatile var instance: FakeLagVpnService? = null
    }

    // Parâmetros ajustáveis em tempo real
    @Volatile var delayMs    = 200L
    @Volatile var lossPct    = 5
    @Volatile var jitterMs   = 50L

    // Métricas ao vivo (lidas pelo JS bridge)
    @Volatile var lastLatMs     = 0L
    @Volatile var droppedCount  = 0L
    @Volatile var sentCount     = 0L

    private var vpnIface: ParcelFileDescriptor? = null
    private var job: Job? = null

    // Fila de delay: (timestamp_ns_para_soltar, bytes_do_pacote)
    private val queue    = PriorityQueue<Pair<Long, ByteArray>>(compareBy { it.first })
    private val qLock    = Any()

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotifChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        delayMs  = intent?.getLongExtra(EXTRA_DELAY,  200L) ?: 200L
        lossPct  = intent?.getIntExtra(EXTRA_LOSS,    5)    ?: 5
        jitterMs = intent?.getLongExtra(EXTRA_JITTER, 50L)  ?: 50L

        startForeground(NOTIF_ID, buildNotif())
        setupTunnel()
        startEngine()

        return START_STICKY
    }

    private fun setupTunnel() {
        vpnIface?.close()
        vpnIface = Builder()
            .addAddress("10.33.0.1", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .setSession("FakeLag FF")
            .setMtu(1500)
            .apply {
                try { addDisallowedApplication(packageName) }
                catch (_: Exception) {}
            }
            .establish()
    }

    private fun startEngine() {
        job?.cancel()
        job = CoroutineScope(Dispatchers.IO).launch {
            val pfd = vpnIface ?: return@launch
            val inStream  = FileInputStream(pfd.fileDescriptor)
            val outStream = FileOutputStream(pfd.fileDescriptor)
            val buf       = ByteArray(32767)

            // Leitura + enfileiramento com fake lag
            launch {
                while (isActive) {
                    val len = try { inStream.read(buf) } catch (_: Exception) { break }
                    if (len < 20) continue

                    sentCount++

                    // Só aplica lag em UDP (protocolo 0x11 = 17)
                    // TCP passa direto — mantém login, voz, downloads normais
                    val proto = buf[9].toInt() and 0xFF
                    if (proto != 0x11) {
                        try { outStream.write(buf, 0, len) } catch (_: Exception) {}
                        continue
                    }

                    // Packet loss
                    if (Random.nextInt(100) < lossPct) {
                        droppedCount++
                        continue
                    }

                    // Delay + jitter
                    val jit       = if (jitterMs > 0) Random.nextLong(-jitterMs, jitterMs) else 0L
                    val finalDel  = maxOf(0L, delayMs + jit)
                    val releaseAt = System.nanoTime() + finalDel * 1_000_000L
                    lastLatMs     = finalDel

                    synchronized(qLock) {
                        queue.offer(Pair(releaseAt, buf.copyOf(len)))
                    }
                }
            }

            // Liberação dos pacotes quando o delay vence
            launch {
                while (isActive) {
                    val now  = System.nanoTime()
                    val pkts = mutableListOf<ByteArray>()

                    synchronized(qLock) {
                        while (queue.isNotEmpty() && queue.peek()!!.first <= now) {
                            pkts.add(queue.poll()!!.second)
                        }
                    }

                    for (p in pkts) {
                        try { outStream.write(p) } catch (_: Exception) {}
                    }

                    delay(1) // poll a cada 1ms
                }
            }
        }
    }

    override fun onDestroy() {
        job?.cancel()
        vpnIface?.close()
        vpnIface = null
        instance = null
        super.onDestroy()
    }

    private fun createNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "FakeLag VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Fake Lag ativo" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotif() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("FakeLag FF ativo")
        .setContentText("Delay: ${delayMs}ms | Loss: ${lossPct}% | Jitter: ±${jitterMs}ms")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setOngoing(true)
        .addAction(
            0, "Parar",
            PendingIntent.getService(
                this, 0,
                Intent(this, FakeLagVpnService::class.java).apply { action = ACTION_STOP },
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()
}
