package com.builttoroam.devicecalendar

import android.content.Context
import android.util.Log
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/** Explicit developer fault injection, never part of durable mutation intent.
 * Session state is shared by foreground and headless engines in this process.
 * Only explicit persistence opt-in survives process death. No engine-local
 * Dart flag can disagree with the executor. All access is on the write lane.
 */
internal object CalendarSplitDebugOptions {
    private const val STORE = "keepcal_calendar_fault_injection"
    private const val OMIT_START = "omit_split_dtstart"
    private const val TAG = "KeepCalFaultInjection"
    private data class State(val enabled: Boolean, val persist: Boolean) {
        fun wire() = mapOf("enabled" to enabled, "persistAcrossRestarts" to persist)
    }
    private var session: State? = null

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    private fun current(context: Context): State = session ?: run {
        val persisted = preferences(context).getBoolean(OMIT_START, false)
        State(persisted, persisted).also { session = it }
    }

    /** Simulates process loss only; never called by plugin/Activity lifecycle. */
    internal fun resetSessionForTesting() { session = null }

    fun omitDtstart(context: Context?): Boolean = try {
        context != null && current(context).enabled
    } catch (failure: Exception) {
        // A broken diagnostic preference must not break normal calendar saves.
        Log.e(TAG, "Could not read split experiment; keeping DTSTART", failure)
        false
    }

    fun handle(context: Context, call: MethodCall, result: MethodChannel.Result) {
        // Serialized with writes: a completed toggle governs subsequent native
        // splits, not a provider transaction already executing. No calendar
        // data or mutation queue is changed merely by changing this preference.
        val requested = call.argument<Any?>("enabled")
        val persist = call.argument<Any?>("persistAcrossRestarts")
        CalendarWriteExecutor.submit(result, {}) { reply ->
            if (call.method == "setSplitDtstartOmissionForTesting") {
                if (requested !is Boolean || persist !is Boolean || (!requested && persist)) {
                    reply.error("INVALID_ARGUMENT", "Explicit boolean enabled/persistAcrossRestarts required; cannot persist a disabled experiment", null)
                    return@submit
                }
                check(preferences(context).edit().putBoolean(OMIT_START, requested && persist).commit()) {
                    "Could not persist the split experiment setting; read it again"
                }
                session = State(requested, persist)
                Log.w(TAG, "split-dtstart-omission configured enabled=$requested persistAcrossRestarts=$persist")
            }
            // Unlike the write-side safe fallback, the UI must learn of read
            // failures rather than falsely showing the experiment as disabled.
            reply.success(current(context).wire())
        }
    }
}
