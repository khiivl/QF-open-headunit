package com.andrerinas.openheadunit.decoder.audio

/**
 * Whether this head unit records when the phone asks, and what to say when it will not.
 *
 * The announcement follows the same setting, so a phone that is never offered a microphone service
 * does not ask. This decides what to say when one asks anyway, which a phone still can.
 *
 * Declining on its own leaves the assistant deaf, and that is the whole reason the setting drives
 * the announcement and the vehicle type too: the phone chooses its recorder once at session start,
 * and takes its own only for a motorcycle that offers it no microphone. Those two go together, and
 * on the newer of the phone's two car services withholding the service under any other type also
 * ends connection setup.
 *
 * Pure: no Android, no logging.
 */
object MicrophonePolicy {

    /** Why a microphone request will not be honoured, or [Decline.NONE] if it will. */
    fun declineReason(headUnitMicEnabled: Boolean, recorderAvailable: Boolean): Decline = when {
        !headUnitMicEnabled -> Decline.USER_SETTING
        !recorderAvailable -> Decline.NO_MICROPHONE
        else -> Decline.NONE
    }

    /**
     * CoD OS (sound line, 24.11): the system attaches its own `ns` to the capture where it belongs (MIC); for VOICE_RECOGNITION it attaches
     * none by design, because recognisers want the raw voice, so OHU adds none of its own there.
     */
    fun ownNoiseSuppressorAllowed(codos: Boolean, audioSource: Int): Boolean =
        !(codos && audioSource == android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION)

    /** CoD OS: no second capture in a live unit call (SIM or Bluetooth HFP, `sys.qf.call_state`); the capture reopens after. */
    fun mustWaitForUnitCall(codos: Boolean, unitCallActive: Boolean): Boolean = codos && unitCallActive

    fun shouldCapture(headUnitMicEnabled: Boolean, recorderAvailable: Boolean): Boolean =
        declineReason(headUnitMicEnabled, recorderAvailable) == Decline.NONE

    enum class Decline {
        /** Capture normally. */
        NONE,

        /** The user handed the microphone to the phone. Not a fault, and the log has to say so. */
        USER_SETTING,

        /** No usable capture configuration on this device. */
        NO_MICROPHONE
    }
}
