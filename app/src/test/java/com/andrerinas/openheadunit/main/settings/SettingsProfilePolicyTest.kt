package com.andrerinas.openheadunit.main.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsProfilePolicyTest {

    @Test fun nothingIsHiddenOnAUnitWithoutZlink() {
        assertTrue(SettingsProfilePolicy.hiddenIds(false).isEmpty())
    }

    @Test fun theConnectionPlumbingAndTestToolsAreHiddenWithZlink() {
        val hidden = SettingsProfilePolicy.hiddenIds(true)
        for (id in listOf("hotspotBand", "fiveGhzChannel", "scopeNotice", "autoOptimize", "wifiConnectionMode", "nativeApTransport", "usbWirelessDongle", "oemAppManagement", "debugVideoFaultInjection")) {
            assertTrue("$id should be hidden", id in hidden)
        }
    }

    @Test fun theSettingsThatWereKeptOnPurposeAreNeverHidden() {
        val hidden = SettingsProfilePolicy.hiddenIds(true)
        for (id in listOf(
            "projectionMode", "autoReconnectOnAccOn",
            // wired Android Auto: the choice of connection, the USB listening and libusb switches
            "connectionMode", "listenForUsbDevices", "useLibusb",
            "hotspotNameAndPassword", "resolution", "fpsLimit"
        )) {
            assertFalse("$id must stay", id in hidden)
        }
    }

    @Test fun hiddenRowsAreDroppedFromTheFilteredList() {
        val items = listOf<SettingItem>(
            SettingItem.CategoryHeader("wirelessConnection", 0),
            SettingItem.SettingEntry(stableId = "wifiConnectionMode", nameResId = 0, value = "", onClick = {}),
            SettingItem.SettingEntry(stableId = "keep", nameResId = 0, value = "", onClick = {}),
        )
        val shown = SettingsFilterPolicy.filter(
            items = items, query = "", advanced = true, showsUsb = true, showsWifi = true,
            basicIds = emptySet(), text = { "" }, hiddenIds = setOf("wifiConnectionMode")
        )
        assertEquals(listOf("wirelessConnection", "keep"), shown.map { it.stableId })
    }

    @Test fun androidAutoSettingsAreHiddenInCarPlayModeOnly() {
        val carPlay = SettingsProfilePolicy.hiddenIds(zlinkUnit = true, carPlayMode = true)
        val androidAuto = SettingsProfilePolicy.hiddenIds(zlinkUnit = true, carPlayMode = false)
        for (id in listOf("resolution", "fpsLimit", "videoCodec", "enableAudioSink", "keymap", "gpsNavigation", "autoEnableHotspot")) {
            assertTrue("$id hidden in CarPlay mode", id in carPlay)
            assertFalse("$id shown in Android Auto mode", id in androidAuto)
        }
    }

    @Test fun carPlaySettingsAreHiddenInAndroidAutoModeOnly() {
        val carPlay = SettingsProfilePolicy.hiddenIds(zlinkUnit = true, carPlayMode = true)
        val androidAuto = SettingsProfilePolicy.hiddenIds(zlinkUnit = true, carPlayMode = false)
        for (id in listOf("hideZlinkUi")) {
            assertTrue("$id hidden in Android Auto mode", id in androidAuto)
            assertFalse("$id shown in CarPlay mode", id in carPlay)
        }
    }

    @Test fun bothModeUnhidesBothAndroidAutoAndCarPlaySettings() {
        val both = SettingsProfilePolicy.hiddenIds(zlinkUnit = true, both = true)
        for (id in listOf("resolution", "fpsLimit", "videoCodec", "enableAudioSink", "hideZlinkUi")) {
            assertFalse("$id must be shown in both mode", id in both)
        }
    }

    @Test fun sharedSettingsAreHiddenInNeitherMode() {
        for (mode in listOf(true, false)) {
            val hidden = SettingsProfilePolicy.hiddenIds(zlinkUnit = true, carPlayMode = mode)
            for (id in listOf("appLanguage", "hotspotNameAndPassword", "projectionMode", "autoReconnectOnAccOn",
                "screenOrientation", "hudMirroring", "enableCarLauncher", "exportSettings", "logLevel")) {
                assertFalse("$id must show in carPlayMode=$mode", id in hidden)
            }
        }
    }
}
