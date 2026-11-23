package com.andrerinas.openheadunit.connection.modulephone

import android.content.Context
import com.andrerinas.openheadunit.utils.AppLog

/** The phones this unit has run a session with, by Bluetooth address. Kept on the unit only. */
class KnownPhones(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("known-phones", Context.MODE_PRIVATE)

    fun kindOf(mac: String?): PhoneKind? {
        val key = PhoneKindPolicy.canonicalMac(mac) ?: return null
        return prefs.getString(key, null)?.let { name -> PhoneKind.values().firstOrNull { it.name == name } }
    }

    fun remember(mac: String?, kind: PhoneKind) {
        val key = PhoneKindPolicy.canonicalMac(mac) ?: return
        if (kindOf(key) == kind) return
        prefs.edit().putString(key, kind.name).apply()
        AppLog.i("known phones: a phone is now remembered as ${kind.name}")
    }

    fun forget(mac: String?) {
        val key = PhoneKindPolicy.canonicalMac(mac) ?: return
        prefs.edit().remove(key).apply()
        AppLog.i("known phones: forgot phone $key")
    }

    fun clear() {
        prefs.edit().clear().apply()
        AppLog.i("known phones: cleared all remembered phones")
    }
}
