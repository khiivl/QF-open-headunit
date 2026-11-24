package com.andrerinas.openheadunit.decoder.audio

import android.media.MediaRecorder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophonePolicyCodosTest {

    @Test fun codosAddsNoNoiseSuppressorOfItsOwnOnVoiceRecognition() {
        assertFalse(MicrophonePolicy.ownNoiseSuppressorAllowed(true, MediaRecorder.AudioSource.VOICE_RECOGNITION))
        assertTrue(MicrophonePolicy.ownNoiseSuppressorAllowed(true, MediaRecorder.AudioSource.MIC))
        assertTrue(MicrophonePolicy.ownNoiseSuppressorAllowed(true, MediaRecorder.AudioSource.DEFAULT))
    }

    @Test fun otherBuildsKeepTheirNoiseSuppressorOnEverySource() {
        assertTrue(MicrophonePolicy.ownNoiseSuppressorAllowed(false, MediaRecorder.AudioSource.VOICE_RECOGNITION))
    }

    @Test fun codosWaitsForAUnitCallOnly() {
        assertTrue(MicrophonePolicy.mustWaitForUnitCall(true, true))
        assertFalse(MicrophonePolicy.mustWaitForUnitCall(true, false))
        assertFalse(MicrophonePolicy.mustWaitForUnitCall(false, true))
    }
}
