package com.andrerinas.openheadunit.connection

import com.andrerinas.openheadunit.utils.AppLog

/**
 * One timestamped line for every change of the connection state and of the status-pill stage, so a hang shows which
 * state it stopped in. The timestamp comes from [AppLog]; the line says from -> to and what the new state carries.
 */
object ConnectionStateLog {

    fun describe(state: CommManager.ConnectionState?): String = when (state) {
        null -> "none"
        is CommManager.ConnectionState.Disconnected ->
            "Disconnected(reason=${state.reason}, endedBy=${state.endedBy}, clean=${state.isClean}, userExit=${state.isUserExit}, " +
                "reachedSsl=${state.reachedSsl}, physical=${state.hadPhysicalConnection}, transport=${state.transport})"
        is CommManager.ConnectionState.Error -> "Error(${state.message})"
        else -> state.javaClass.simpleName
    }

    fun state(from: CommManager.ConnectionState, to: CommManager.ConnectionState, mode: String?) {
        AppLog.i("ConnState: ${describe(from)} -> ${describe(to)} [mode=${mode ?: "-"}]")
    }

    fun stage(from: ConnectionStage?, to: ConnectionStage?, why: String) {
        if (from != to) AppLog.i("ConnStage: ${from ?: "none"} -> ${to ?: "none"} [$why]")
    }
}
