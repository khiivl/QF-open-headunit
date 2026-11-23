package com.andrerinas.openheadunit.connection.modulephone

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt.ZbtAttemptPolicy.Presence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneKindPolicyTest {

    @Test fun macReadsTheSameWhateverTheSeparators() {
        assertEquals("AABBCCDDEEFF", PhoneKindPolicy.canonicalMac("aa:bb:cc:dd:ee:ff"))
        assertEquals("AABBCCDDEEFF", PhoneKindPolicy.canonicalMac("AABBCCDDEEFF"))
        assertNull(PhoneKindPolicy.canonicalMac(""))
        assertNull(PhoneKindPolicy.canonicalMac(null))
    }

    @Test fun onlyTheWrongKindForTheModeIsBlocked() {
        assertTrue(PhoneKindPolicy.blocks(carPlayMode = false, kind = PhoneKind.IPHONE))
        assertTrue(PhoneKindPolicy.blocks(carPlayMode = true, kind = PhoneKind.ANDROID))
        assertFalse(PhoneKindPolicy.blocks(carPlayMode = false, kind = PhoneKind.ANDROID))
        assertFalse(PhoneKindPolicy.blocks(carPlayMode = true, kind = PhoneKind.IPHONE))
        assertFalse(PhoneKindPolicy.blocks(carPlayMode = false, kind = null))
        assertFalse(PhoneKindPolicy.blocks(carPlayMode = true, kind = null))
    }

    @Test fun reportsTrackTheConnectedPhone() {
        val full = Presence(connected = true, androidAuto = true, phoneName = "Phone", phoneMac = "AA:BB:CC:DD:EE:FF")
        val first = PhoneKindPolicy.next(null, full)
        assertEquals(ModulePhone("AA:BB:CC:DD:EE:FF", "Phone"), first)

        // the short report names nobody: the earlier one still holds
        val short = Presence(connected = true, androidAuto = true)
        assertEquals(first, PhoneKindPolicy.next(first, short))

        // disconnected: nobody
        assertNull(PhoneKindPolicy.next(first, Presence(connected = false, androidAuto = false)))
    }
}
