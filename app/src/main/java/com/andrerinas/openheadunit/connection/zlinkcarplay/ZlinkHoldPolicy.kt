package com.andrerinas.openheadunit.connection.zlinkcarplay

/**
 * Pure policy governing the daemon's link mode and audio suppression towards the zLink app.
 *
 * While at least one subscriber is attached, wireless CarPlay (mode 2) is offered to the daemon
 * so CarPlay remains active without zLink's own UI. Audio playback is withheld from the zLink app
 * because OHU plays the audio itself.
 */
class ZlinkHoldPolicy {
    @Volatile
    var lastInitFrame: ByteArray? = null
        private set

    @Volatile
    var offerSuspended: Boolean = false
        private set

    /**
     * Called when the subscriber count changes.
     * Returns a frame to send to the daemon (under lock), or null if no action is needed.
     */
    fun onSubscriberCountChanged(oldCount: Int, newCount: Int): ByteArray? {
        val last = lastInitFrame ?: return null
        return if (oldCount == 0 && newCount > 0) {
            // First subscriber arrived: hold wireless CarPlay and cancel any suspension
            offerSuspended = false
            ZlinkMessage.withMode(last, 2)
        } else {
            // Backgrounding (e.g. going Home) does NOT restore mode 0,
            // keeping wireless CarPlay alive.
            null
        }
    }

    /**
     * Inspects and potentially rewrites a 0x102 message from the app to the daemon.
     * Returns the frame to forward to the daemon.
     */
    fun onAppInitFrame(frame: ByteArray, subscriberCount: Int): ByteArray {
        lastInitFrame = frame
        return if (subscriberCount > 0 && !offerSuspended) {
            if (ZlinkMessage.fieldValue(frame, 7) != 2L) {
                ZlinkMessage.withMode(frame, 2)
            } else {
                frame
            }
        } else if (offerSuspended && ZlinkMessage.fieldValue(frame, 7) != 0L) {
            // After a user Disconnect the daemon restarts and the app offers CarPlay again by itself; keep it off.
            ZlinkMessage.withMode(frame, 0)
        } else {
            frame
        }
    }

    /**
     * Determines whether an audio frame from the daemon (port 1666, d2a) should be forwarded
     * to the zLink app. If subscribers exist, OHU plays the audio and the app must not.
     */
    fun shouldForwardAudioToApp(port: Int, d2a: Boolean, subscriberCount: Int): Boolean {
        if (port == 1666 && d2a && subscriberCount > 0) {
            return false
        }
        return true
    }

    /**
     * Suspends the wireless CarPlay offer (e.g. user disconnect or power off).
     * Returns the 0x102 frame with field 7 = 0 to send to the daemon.
     */
    fun suspendOffer(): ByteArray {
        offerSuspended = true
        val last = lastInitFrame
        return if (last != null) {
            ZlinkMessage.withMode(last, 0)
        } else {
            // Minimal 0x102 frame with field 7 = 0
            byteArrayOf(
                0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x10,
                0, 0, 0, 2,
                0, 0, 1, 2,
                0x38, 0
            )
        }
    }

    /**
     * Resumes the wireless CarPlay offer after suspension.
     * Returns the 0x102 frame with field 7 = 2 to send to the daemon, or null if no init frame seen.
     */
    fun resumeOffer(subscriberCount: Int = 0): ByteArray? {
        offerSuspended = false
        val last = lastInitFrame ?: return null
        return ZlinkMessage.withMode(last, 2)
    }
}
