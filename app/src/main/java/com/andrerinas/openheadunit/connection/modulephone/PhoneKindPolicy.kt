package com.andrerinas.openheadunit.connection.modulephone

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt.ZbtAttemptPolicy

/** What kind of phone a Bluetooth address belongs to, as learned from sessions that worked. */
enum class PhoneKind { IPHONE, ANDROID }

/** The phone the unit's Bluetooth module currently has connected. */
data class ModulePhone(val mac: String?, val name: String?)

/**
 * Pure rules for telling which phone is on the module and whether it can be used in the current mode.
 *
 * The module's own phone_type cannot be used: it follows the mode the module is set for, so an iPhone is reported
 * as an Android Auto phone while the unit is in Android Auto mode. A phone's kind is learned instead, from a
 * session that actually worked with it.
 */
object PhoneKindPolicy {

    /** A Bluetooth address without separators, upper case, so the same phone always reads the same. */
    fun canonicalMac(mac: String?): String? =
        mac?.filter { it.isLetterOrDigit() }?.uppercase()?.takeIf { it.isNotEmpty() }

    /** Whether a phone of [kind] cannot be used in the current mode. Unknown phones are never blocked. */
    fun blocks(carPlayMode: Boolean, kind: PhoneKind?): Boolean = when (kind) {
        null -> false
        PhoneKind.ANDROID -> carPlayMode
        PhoneKind.IPHONE -> !carPlayMode
    }

    /**
     * The phone on the module after a link report: none when the report says disconnected, otherwise the reported
     * one. A report without a name or address (the short LinkInfo) keeps what an earlier report of the same
     * connection gave.
     */
    fun next(previous: ModulePhone?, presence: ZbtAttemptPolicy.Presence): ModulePhone? {
        if (!presence.connected) return null
        return ModulePhone(
            mac = presence.phoneMac ?: previous?.mac,
            name = presence.phoneName ?: previous?.name
        )
    }
}
