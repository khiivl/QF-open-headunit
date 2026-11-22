package com.andrerinas.openheadunit.connection.zlinkcarplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.OemAppManager
import com.andrerinas.openheadunit.utils.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.Closeable

/**
 * Foreground service hosting the in-process [ZlinkProxy] and watching for incoming CarPlay sessions.
 *
 * Subscribed to [ZlinkHub], it keeps wireless CarPlay on offer to iPhones and automatically raises
 * [ZlinkCarPlayActivity] when the first video SPS of a session arrives.
 */
class ZlinkService : Service() {
    private var proxy: ZlinkProxy? = null
    private var hubSubscription: Closeable? = null
    private var powerReceiver: ZlinkPowerReceiver? = null
    @Volatile private var lastRaiseMs = 0L
    @Volatile private var sessionRaised = false

    // The CarPlay audio belongs to the service, not the screen, so music keeps playing on the home screen.
    @Volatile private var audioSink: ZlinkAudioSink? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ProjectionModePolicy.carPlayEnabled(Settings(this).projectionMode)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()

        CoroutineScope(Dispatchers.IO).launch {
            ZlinkRedirect.ensureRedirectRules(applicationContext)
            if (Settings(applicationContext).hideZlinkUi && ZlinkScreenPolicy.isUiHidden(applicationContext) != true) {
                ZlinkScreenPolicy.applyVisibility(applicationContext, true)
                // Changing the app's screens stops its process, and the daemon restarts in a loop until the app is
                // back. Starting the app's own background service brings it up again.
                OemAppManager.executeCommand(applicationContext, ZLINK_DAEMON_START)
            }
        }

        if (proxy == null) {
            proxy = ZlinkProxy().also { it.start() }
        }

        if (hubSubscription == null) {
            hubSubscription = ZlinkHub.subscribe(object : ZlinkHub.Listener {
                override fun onFrame(frame: ZlinkWire.Frame) {
                    if (frame.channel == ZlinkWire.CH_AUDIO && frame.type == ZlinkWire.TYPE_AUDIO) {
                        ZlinkWire.parseAudio(frame.payload)?.let { chunk ->
                            val sink = audioSink ?: ZlinkAudioSink().also { audioSink = it }
                            sink.feed(chunk)
                        }
                    }
                    if (frame.channel == ZlinkWire.CH_VIDEO && frame.type == ZlinkWire.TYPE_VIDEO) {
                        val unit = ZlinkWire.parseVideo(frame.payload) ?: return
                        if (unit.nalType == 7 && !sessionRaised) {
                            if (raiseProjection()) {
                                sessionRaised = true
                            }
                        }
                    }
                }

                override fun onSessionState(state: ZlinkSessionState) {
                    if (state == ZlinkSessionState.GAVE_UP) {
                        sessionRaised = false
                        audioSink?.release()
                        audioSink = null
                    }
                }
            }, userIntent = false)
        }

        if (powerReceiver == null) {
            val receiver = ZlinkPowerReceiver()
            ContextCompat.registerReceiver(
                this,
                receiver,
                ZlinkPowerReceiver.createFilter(),
                ContextCompat.RECEIVER_EXPORTED
            )
            powerReceiver = receiver
        }

        return START_STICKY
    }

    private fun raiseProjection(): Boolean {
        if (ZlinkCarPlayActivity.isResumed) return true
        // Held off (Disconnect, mode switch): the user connects CarPlay themselves, nothing opens on its own.
        if (ZlinkHub.isOfferSuspended) return true
        val now = System.currentTimeMillis()
        if (now - lastRaiseMs < RAISE_COOLDOWN_MS) return false
        lastRaiseMs = now
        return try {
            startActivity(Intent(this, ZlinkCarPlayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            AppLog.i("zlink service: CarPlay session started, raising the CarPlay screen")
            true
        } catch (e: Exception) {
            AppLog.w("zlink service: could not raise the CarPlay screen: ${e.message}")
            false
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "CarPlay", NotificationManager.IMPORTANCE_MIN))
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_aa)
            .setContentTitle("CarPlay ready")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    override fun onDestroy() {
        if (!ProjectionModePolicy.carPlayEnabled(Settings(this).projectionMode)) {
            CoroutineScope(Dispatchers.IO).launch {
                ZlinkRedirect.removeRedirectRules(applicationContext)
            }
        }
        powerReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            it.close()
        }
        powerReceiver = null
        hubSubscription?.close()
        hubSubscription = null
        audioSink?.release()
        audioSink = null
        sessionRaised = false
        proxy?.stop()
        proxy = null
        super.onDestroy()
    }

    companion object {
        private const val ZLINK_DAEMON_START =
            "am start-foreground-service -n com.zjinnova.zlink/com.zjinnova.android.zlink.features.service.DaemonService"
        private const val CHANNEL = "zlink_carplay"
        private const val NOTIFICATION_ID = 0x5A11
        private const val RAISE_COOLDOWN_MS = 5_000L

        /** Starts or stops the service to match the projection mode. Safe to call repeatedly. */
        fun sync(context: Context) {
            val intent = Intent(context, ZlinkService::class.java)
            if (ProjectionModePolicy.carPlayEnabled(Settings(context).projectionMode)) {
                try {
                    ContextCompat.startForegroundService(context, intent)
                } catch (e: Exception) {
                    AppLog.w("zlink service: could not start: ${e.message}")
                }
            } else {
                context.stopService(intent)
                CoroutineScope(Dispatchers.IO).launch {
                    ZlinkRedirect.removeRedirectRules(context.applicationContext)
                }
            }
        }
    }
}
