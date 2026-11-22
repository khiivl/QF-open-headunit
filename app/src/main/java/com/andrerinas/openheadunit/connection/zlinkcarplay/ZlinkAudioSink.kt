package com.andrerinas.openheadunit.connection.zlinkcarplay

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.andrerinas.openheadunit.utils.AppLog
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Plays the zLink PCM feed on its own thread so a blocking write never stalls video. */
class ZlinkAudioSink {
    private val queue = ArrayBlockingQueue<ZlinkWire.AudioChunk>(64)
    @Volatile private var running = true
    private var track: AudioTrack? = null
    private var rate = 0
    private var channels = 0
    private val thread = Thread({ loop() }, "zlink-audio").also { it.isDaemon = true; it.start() }

    fun feed(chunk: ZlinkWire.AudioChunk) {
        if (!queue.offer(chunk)) { queue.poll(); queue.offer(chunk) }   // keep latency bounded: drop oldest
    }

    private fun loop() {
        while (running) {
            val chunk = try { queue.poll(200, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break } ?: continue
            try {
                if (track == null || rate != chunk.sampleRate || channels != chunk.channels) open(chunk)
                track?.write(chunk.pcm, 0, chunk.pcm.size)
            } catch (e: Exception) { AppLog.w("zlink audio: ${e.message}") }
        }
        try { track?.stop() } catch (_: Exception) {}
        track?.release(); track = null
    }

    @Suppress("DEPRECATION")
    private fun open(c: ZlinkWire.AudioChunk) {
        track?.release()
        val mask = if (c.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val min = AudioTrack.getMinBufferSize(c.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack(AudioManager.STREAM_MUSIC, c.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT, min * 4, AudioTrack.MODE_STREAM)
            .also { it.play() }
        rate = c.sampleRate; channels = c.channels
        AppLog.i("zlink audio: track ${rate}Hz ${channels}ch")
    }

    fun release() { running = false; thread.interrupt() }
}
