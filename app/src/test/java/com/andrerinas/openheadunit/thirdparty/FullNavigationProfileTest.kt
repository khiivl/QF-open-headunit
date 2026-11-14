package com.andrerinas.openheadunit.thirdparty

import com.andrerinas.openheadunit.aap.AapVersionNegotiation
import com.andrerinas.openheadunit.aap.protocol.messages.Messages
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FullNavigationProfileTest {

    @Test
    fun offAnnouncesExactlyWhatTheAppAnnouncedBefore() {
        assertEquals(AapVersionNegotiation.ANNOUNCED_MINOR, FullNavigationProfile.versionMinor(false))
        assertEquals(1000, FullNavigationProfile.navigationIntervalMs(false))
        assertEquals("1", FullNavigationProfile.softwareBuild(false))
        assertEquals("0.1.0", FullNavigationProfile.softwareVersion(false))
        // the request that goes out with the switch off is byte for byte the old one
        assertArrayEquals(Messages.versionRequest, Messages.versionRequest(FullNavigationProfile.versionMinor(false)))
    }

    @Test
    fun onAnnouncesWhatZlinkAnnounces() {
        assertEquals(4, FullNavigationProfile.versionMinor(true))
        assertEquals(500, FullNavigationProfile.navigationIntervalMs(true))
        assertEquals("2022-03-30-438482292", FullNavigationProfile.softwareBuild(true))
        assertEquals("2.0-linux", FullNavigationProfile.softwareVersion(true))
    }

    @Test
    fun theVersionRequestCarriesMajorOneAndTheChosenMinor() {
        // channel 0, flags 3 (unencrypted control), length 6, type 1 (VERSION_REQUEST), major 1, minor 4
        assertArrayEquals(byteArrayOf(0, 3, 0, 6, 0, 1, 0, 1, 0, 4), Messages.versionRequest(4))
    }

    @Test
    fun theFullNavigationVersionStaysBelowTheOneSixFeatureSet() {
        // nothing gated on 1.6 is switched on by this option
        assertFalse(FullNavigationProfile.versionMinor(true) >= AapVersionNegotiation.FEATURE_MINOR_1_6)
    }
}
