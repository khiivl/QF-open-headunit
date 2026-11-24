package com.andrerinas.openheadunit.input

import android.view.KeyEvent
import com.andrerinas.openheadunit.input.BridgeVoiceKeyPolicy.Forward
import com.andrerinas.openheadunit.input.BridgeVoiceKeyPolicy.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BridgeVoiceKeyPolicyTest {

    private val press = "D/McuKeyEventHandler( 5587): onReceived - code: 304 - action: 0"
    private val release = "D/McuKeyEventHandler( 5587): onReceived - code: 304 - action: 1"

    @Test fun `a key line is parsed into code and action`() {
        assertEquals(304 to 0, BridgeVoiceKeyPolicy.parse(press))
        assertEquals(304 to 1, BridgeVoiceKeyPolicy.parse(release))
    }

    @Test fun `other lines and other tags are ignored`() {
        assertNull(BridgeVoiceKeyPolicy.parse("D/McuKeyEventHandler( 5587): handleVoice: no exist kimi and VAV app"))
        assertNull(BridgeVoiceKeyPolicy.parse("D/SomethingElse( 1): onReceived - code: 304 - action: 0"))
        assertNull(BridgeVoiceKeyPolicy.parse(""))
    }

    private fun forward(
        code: Int, action: Int = 0, now: Long = 10_000, last: Long = 0, since: Long = 5_000,
        enabled: Boolean = true, androidAuto: Boolean = false, carPlay: Boolean = false
    ) = BridgeVoiceKeyPolicy.forward(code, action, now, last, since, enabled, androidAuto, carPlay)

    @Test fun `the voice key goes to the assistant of the running projection`() {
        assertEquals(Forward(Target.ANDROID_AUTO, KeyEvent.KEYCODE_SEARCH), forward(304, androidAuto = true))
        assertEquals(Forward(Target.CARPLAY, 1500), forward(304, carPlay = true))
    }

    @Test fun `media and call keys are passed on as they are`() {
        for (code in listOf(85, 87, 88, 89, 90, 126, 127, 5, 6)) {
            assertEquals(Forward(Target.CARPLAY, code), forward(code, carPlay = true))
            assertEquals(Forward(Target.ANDROID_AUTO, code), forward(code, androidAuto = true))
        }
    }

    @Test fun `the combined accept and reject keys become call and end call`() {
        assertEquals(Forward(Target.CARPLAY, 5), forward(305, carPlay = true))
        assertEquals(Forward(Target.CARPLAY, 6), forward(306, carPlay = true))
        assertEquals(Forward(Target.ANDROID_AUTO, KeyEvent.KEYCODE_CALL), forward(305, androidAuto = true))
        assertEquals(Forward(Target.ANDROID_AUTO, KeyEvent.KEYCODE_ENDCALL), forward(306, androidAuto = true))
    }

    @Test fun `Android Auto wins when both could take the key`() =
        assertEquals(Forward(Target.ANDROID_AUTO, 87), forward(87, androidAuto = true, carPlay = true))

    @Test fun `no projection, the setting off, or an unknown key means nothing`() {
        assertNull(forward(88))
        assertNull(forward(88, enabled = false, carPlay = true))
        assertNull(forward(4, carPlay = true))
        assertNull(forward(999, androidAuto = true))
    }

    @Test fun `only the key down counts`() = assertNull(forward(88, action = 1, carPlay = true))

    @Test fun `a repeat inside the debounce is dropped, a later press counts, the voice key waits longer`() {
        assertNull(forward(88, now = 10_100, last = 10_000, carPlay = true))
        assertEquals(Forward(Target.CARPLAY, 88), forward(88, now = 10_300, last = 10_000, carPlay = true))
        assertNull(forward(304, now = 10_400, last = 10_000, carPlay = true))
        assertEquals(Forward(Target.CARPLAY, 1500), forward(304, now = 10_800, last = 10_000, carPlay = true))
    }

    @Test fun `the line the stream repeats at its start is not a press`() {
        assertNull(forward(88, since = BridgeVoiceKeyPolicy.STARTUP_SKIP_MS - 1, carPlay = true))
        assertEquals(Forward(Target.CARPLAY, 88), forward(88, since = BridgeVoiceKeyPolicy.STARTUP_SKIP_MS, carPlay = true))
    }

    @Test fun `a stream that dies at once is retried ever more slowly, a healthy one resets the wait`() {
        val min = BridgeVoiceKeyPolicy.RETRY_MIN_MS
        assertEquals(min, BridgeVoiceKeyPolicy.nextRetryDelayMs(min / 2, 100))
        assertEquals(min * 2, BridgeVoiceKeyPolicy.nextRetryDelayMs(min, 100))
        assertEquals(BridgeVoiceKeyPolicy.RETRY_MAX_MS, BridgeVoiceKeyPolicy.nextRetryDelayMs(BridgeVoiceKeyPolicy.RETRY_MAX_MS, 100))
        assertEquals(min, BridgeVoiceKeyPolicy.nextRetryDelayMs(40_000, BridgeVoiceKeyPolicy.STREAM_HEALTHY_MS))
    }

    @Test fun `the Siri key is the one zLink sends`() {
        assertEquals(1500, BridgeVoiceKeyPolicy.CARPLAY_SIRI_KEY)
    }
}
