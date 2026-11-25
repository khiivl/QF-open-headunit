package com.andrerinas.openheadunit.aap.protocol.messages

/**
 * The safeguard for an H.265 session that never shows a picture.
 *
 * Open Headunit advertises one codec per session and decodes whatever it advertised; the phone cannot ask for
 * another. A phone that cannot encode H.265, or a decoder that cannot read what it sends, therefore leaves a
 * session with no video at all. When a session advertised H.265, ran for [WATCH_MS] and rendered no frame, the
 * next session advertises H.264 instead, once, and keeps doing so until the app process ends.
 *
 * It cannot tell a codec the phone lacks from a link too slow to carry any video: both look like "no frame". The
 * cost of a wrong guess is H.264 until the next app start, which is why it is one shot per process.
 */
object VideoCodecFallback {

    /** How long a session may run without a rendered frame before H.265 is given up on. */
    const val WATCH_MS = 20_000L

    /** Set once the fallback has fired: every later session advertises H.264. */
    @Volatile var forceH264 = false
        private set

    /** Whether the session being set up advertised H.265 (written when the service discovery response is built). */
    @Volatile var advertisedH265 = false

    fun shouldFallBack(advertisedH265: Boolean, alreadyForced: Boolean, framesRendered: Long): Boolean =
        advertisedH265 && !alreadyForced && framesRendered == 0L

    fun engage() { forceH264 = true }
}
