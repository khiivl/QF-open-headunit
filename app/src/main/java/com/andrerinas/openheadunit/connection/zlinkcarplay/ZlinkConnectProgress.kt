package com.andrerinas.openheadunit.connection.zlinkcarplay

import android.os.SystemClock
import com.andrerinas.openheadunit.connection.ConnectionStage
import com.andrerinas.openheadunit.connection.ConnectionStageTracker
import com.andrerinas.openheadunit.connection.modulephone.ModulePhoneWatcher
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Puts a CarPlay connection on the same stage tracker the home card and the status pill read, so the card shows a step
 * and its progress bar from the moment the car starts (or Connect is pressed) until the session is up. See
 * [ZlinkConnectProgressPolicy] for what is shown and when it ends. Only the neutral steps are used ("Waking your
 * phone", "Connecting"): the other stage labels name Android Auto.
 */
object ZlinkConnectProgress {
    private const val POLL_MS = 500L

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null

    /** Starts showing the progress of a CarPlay connection that was just asked for. Does nothing with a session up. */
    @Synchronized
    fun begin() {
        if (ZlinkHub.hasActiveSession) return
        job?.cancel()
        ConnectionStageTracker.beginAttempt(ConnectionStage.WAKING_PHONE)
        AppLog.i("zlink progress: CarPlay is connecting, showing the step on the home")
        job = scope.launch { run() }
    }

    /** Ends the progress without a connection, for the user's Cancel. */
    @Synchronized
    fun cancel() {
        val running = job ?: return
        running.cancel()
        job = null
        ConnectionStageTracker.endAttempt()
        AppLog.i("zlink progress: cancelled")
    }

    private suspend fun run() {
        val startedAt = SystemClock.elapsedRealtime()
        var phoneSince: Long? = null
        var shown = ConnectionStage.WAKING_PHONE
        try {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                if (ModulePhoneWatcher.phone.value != null) {
                    if (phoneSince == null) phoneSince = now
                } else {
                    phoneSince = null
                }
                when (ZlinkConnectProgressPolicy.step(
                    sessionActive = ZlinkHub.hasActiveSession,
                    offerSuspended = ZlinkHub.isOfferSuspended,
                    elapsedMs = now - startedAt,
                    phoneOnModuleForMs = phoneSince?.let { now - it }
                )) {
                    ZlinkConnectProgressPolicy.Step.STOP -> return
                    ZlinkConnectProgressPolicy.Step.CONNECTING -> if (shown != ConnectionStage.CONNECTING) {
                        shown = ConnectionStage.CONNECTING
                        ConnectionStageTracker.report(ConnectionStage.CONNECTING)
                    }
                    ZlinkConnectProgressPolicy.Step.WAKING -> Unit
                }
                delay(POLL_MS)
            }
        } finally {
            // Whatever ended it, the step must not outlive the attempt. A newer begin() replaces this job and sets
            // its own step, so only the job that is still current clears it.
            synchronized(this) {
                if (job === kotlin.coroutines.coroutineContext[Job]) {
                    job = null
                    ConnectionStageTracker.endAttempt()
                }
            }
        }
    }
}
