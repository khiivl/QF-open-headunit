package com.andrerinas.openheadunit.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostapdConfigTest {

    @Test fun readsHexNameAndPassphrase() {
        val text = listOf("interface=wlan0", "ssid2=4b373036", "channel=0", "wpa=2", "wpa_passphrase=secret pass")
            .joinToString("\n")
        assertEquals(Pair("K706", "secret pass"), HostapdConfig.parse(text))
    }

    @Test fun readsPlainName() {
        assertEquals(Pair("Net", "pw123456"), HostapdConfig.parse(listOf("ssid=Net", "wpa_passphrase=pw123456").joinToString("\n")))
    }

    @Test fun nullWithoutPassphraseOrName() {
        assertNull(HostapdConfig.parse(listOf("ssid=Net", "wpa=2").joinToString("\n")))
        assertNull(HostapdConfig.parse("wpa_passphrase=pw123456"))
        assertNull(HostapdConfig.parse(""))
    }

    @Test fun ignoresComments() {
        assertEquals(
            Pair("A", "b1234567"),
            HostapdConfig.parse(listOf("#ssid=Old", "ssid=A", "wpa_passphrase=b1234567").joinToString("\n"))
        )
    }

    @Test fun readsTheBandFromHwMode() {
        assertEquals(true, HostapdConfig.runs5Ghz(listOf("interface=wlan0", "hw_mode=a", "channel=0").joinToString(System.lineSeparator())))
        assertEquals(false, HostapdConfig.runs5Ghz("hw_mode=g"))
        assertEquals(false, HostapdConfig.runs5Ghz("hw_mode=b"))
        assertNull(HostapdConfig.runs5Ghz("channel=36"))
        assertNull(HostapdConfig.runs5Ghz(""))
    }
}
