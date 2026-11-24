package com.andrerinas.openheadunit.connection.zlinkcarplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Debug builds only: sends one key, a held key or a two-key chord to the zLink daemon on command, for finding which
 * codes and gestures the daemon acts on.
 *
 *     adb shell am broadcast -a com.andrerinas.openheadunit.DEBUG_ZLINK_KEY -p com.andrerinas.headunitrevived --ei keycode 87
 *     ... --ei keycode 89 --ei hold 2000            (rewind held for two seconds)
 *     ... --ei keycode 89 --ei with 5 --ei hold 300 (call down, rewind down, 300 ms, rewind up, call up)
 *
 * Without `hold` the key is down for [PULSE_MS], as the voice button and the back key are.
 */
class ZlinkDebugKeyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val code = intent.getIntExtra("keycode", -1)
        if (code < 0) return
        val with = intent.getIntExtra("with", -1)
        val hold = intent.getIntExtra("hold", PULSE_MS.toInt()).coerceIn(10, 10_000).toLong()
        AppLog.i("zlink debug: sending key $code" + (if (with >= 0) " together with $with" else "") + " held ${hold}ms to the daemon")
        val handler = Handler(Looper.getMainLooper())
        if (with >= 0) ZlinkHub.injectKey(with, true)
        ZlinkHub.injectKey(code, true)
        handler.postDelayed({
            ZlinkHub.injectKey(code, false)
            if (with >= 0) ZlinkHub.injectKey(with, false)
        }, hold)
    }

    companion object {
        const val PULSE_MS = 80L
    }
}
