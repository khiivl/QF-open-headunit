package com.andrerinas.openheadunit.connection.modulephone

import android.content.Context
import android.os.SystemClock
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt.ZbtAttemptPolicy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt.ZbtByteChannel
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt.ZbtMessages
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which phone is on the unit's Bluetooth module, and what kind it is.
 *
 * It never keeps a connection of its own to the module: the module hands the phone's data to the newest client, so a
 * second reader takes what the owner (the Android Auto carrier, the CarPlay daemon) is waiting for and breaks the
 * connection. The Android Auto carrier reports what it reads here; a CarPlay session is read once, briefly, after it is
 * up.
 */
object ModulePhoneWatcher {
    private val _phone = MutableStateFlow<ModulePhone?>(null)
    val phone: StateFlow<ModulePhone?> = _phone.asStateFlow()

    @Volatile private var known: KnownPhones? = null

    fun start(context: Context) {
        if (known == null) known = KnownPhones(context.applicationContext)
    }

    /** Called by the Android Auto carrier for every link report it reads from the module. */
    fun reportFromCarrier(presence: ZbtAttemptPolicy.Presence) {
        val next = PhoneKindPolicy.next(_phone.value, presence)
        if (next != _phone.value) {
            _phone.value = next
            AppLog.i("module phone: ${if (next == null) "no phone on the module" else "a phone is on the module"}")
        }
    }

    /** The carrier let go of the module, so nothing is known about it any more. */
    fun clear() {
        _phone.value = null
    }

    /** The kind of the phone on the module right now, if it has been seen working before. */
    fun connectedKind(): PhoneKind? = _phone.value?.mac?.let { known?.kindOf(it) }

    fun kindOfMac(mac: String?): PhoneKind? = known?.kindOf(mac)

    /** Remembers the phone on the module as [kind]; called when a session with it worked. */
    fun rememberConnected(kind: PhoneKind) {
        _phone.value?.mac?.let { known?.remember(it, kind) }
    }

    /** Remembers [mac] as a phone of [kind]. */
    fun rememberMac(mac: String?, kind: PhoneKind) {
        known?.remember(mac, kind)
    }

    /**
     * One short read of the module's link report, for a moment when nothing is being set up over it (a CarPlay
     * session that is already running): which phone is on the module right now. Null when it says none or nothing.
     */
    fun snapshotOnce(): ModulePhone? {
        var seen: ModulePhone? = null
        val channel = try {
            ZbtByteChannel.open(
                initialize = false,
                bufferRfcommData = false,
                onControlFrame = { msgId, body ->
                    ZbtAttemptPolicy.readPresence(msgId, ZbtMessages.parse(body))?.let { seen = PhoneKindPolicy.next(seen, it) }
                }
            )
        } catch (e: Exception) {
            return null
        }
        try {
            val until = SystemClock.elapsedRealtime() + SNAPSHOT_MS
            while (SystemClock.elapsedRealtime() < until && seen?.mac == null) {
                if (channel.pumpOnce(until) == ZbtByteChannel.Pump.ENDED) break
            }
        } catch (e: Exception) {
            AppLog.d("module phone: snapshot ended: ${e.message}")
        } finally {
            try { channel.close() } catch (_: Exception) {}
        }
        return seen
    }

    private const val SNAPSHOT_MS = 2_500L
}

object PhoneKindGate {

    private val _carPlayNeedsIphone = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** True for a short while after a Connect found an Android phone on the module: CarPlay needs an iPhone. */
    val carPlayNeedsIphone: kotlinx.coroutines.flow.StateFlow<Boolean> = _carPlayNeedsIphone

    /**
     * Run when the user presses Connect in CarPlay mode, before the link is restarted: a short read of the module,
     * safe here because no handshake is in flight yet and the restart that follows begins a new one. True when the
     * phone on the module is known to be an Android phone.
     */
    fun checkBeforeCarPlayConnect(): Boolean {
        val phone = ModulePhoneWatcher.snapshotOnce()
        val blocked = PhoneKindPolicy.blocks(carPlayMode = true, kind = ModulePhoneWatcher.kindOfMac(phone?.mac))
        _carPlayNeedsIphone.value = blocked
        if (blocked) {
            Thread({
                try { Thread.sleep(HINT_MS) } catch (_: InterruptedException) {}
                _carPlayNeedsIphone.value = false
            }, "carplay-hint-expiry").also { it.isDaemon = true }.start()
        }
        return blocked
    }

    fun clearCarPlayHint() { _carPlayNeedsIphone.value = false }

    private const val HINT_MS = 30_000L


    /** Android Auto would be attempted against [mac] (or the phone on the module): not if it is known to be an iPhone. */
    fun blocksAndroidAuto(mac: String? = null): Boolean {
        val kind = if (mac != null) ModulePhoneWatcher.kindOfMac(mac) else ModulePhoneWatcher.connectedKind()
        return PhoneKindPolicy.blocks(carPlayMode = false, kind = kind)
    }
}
