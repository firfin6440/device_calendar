package com.builttoroam.devicecalendar.diagnostics

import android.util.Log

/** No public constructor that accepts unreviewed text. Never used for provider data. */
class DiagnosticValue internal constructor(private val encoded: Any?) {
    override fun toString(): String = encoded.toString()
}
enum class DiagnosticField { id, calendar, root, repair, stage, operation, outcome,
    count, generation, attempt, delaySeconds, elapsedMs, pending, enabled, available,
    current, closed, retry, stopped, handoff, detached, error, start, end, slot,
    expectedStart, expectedEnd, requestedStart, requestedEnd, recurrence, dirty,
    deleted, changed, sourceCount, destinationCount, insertedCount, restoredCount,
    removedCount, titleChanged, locationChanged, durationChanged, timeChanged,
    colorChanged, index, session, boundary, calls, runtime, installed, resolved,
    attached, allowed, persisted }
enum class DiagnosticStep { unknown, describe, status, arm, wake, disarm, apply,
    due, dismiss, snooze, maintenance, delivered, skipped, uncertain, rejected,
    applied, schedule, cancel, post, update, IDLE, SCHEDULED, RETRY,
    POSTED, UPDATED, CANCELLED, BLOCKED, current, all, following, recurrenceCreation, recurrenceLimit, splitPrefix }

/** Thread-safe, process-local bounded aliases. No source fragments, hashes or dates. */
class NativeObfuscation(private val maxEntries: Int = 512,
    private val maxCharacters: Int = 65536) {
    private val aliases = mutableMapOf<Pair<String, String>, DiagnosticValue>()
    private var retained = 0
    private var sequence = 0L
    @Synchronized private fun alias(kind: String, value: String?): DiagnosticValue {
        if (value == null) return DiagnosticValue("[$kind:absent]")
        if (value.isEmpty()) return DiagnosticValue("[$kind:empty]")
        if (value.length > 4096) return DiagnosticValue("[$kind:redacted]")
        val key = kind to value
        aliases[key]?.let { return it }
        if (aliases.size >= maxEntries || retained + value.length > maxCharacters)
            return DiagnosticValue("[$kind:redacted]")
        val result = DiagnosticValue("[$kind:${++sequence}]")
        aliases[key] = result; retained += value.length
        return result
    }
    fun id(value: String?) = alias("identifier", value)
    fun calendar(value: String?) = alias("calendar", value)
    fun event(value: String?) = alias("event", value)
    fun instant(value: Long?) = alias("instant", value?.toString())
    fun recurrence(value: String?) = alias("recurrence", value)
    fun flag(value: Boolean?) = DiagnosticValue(value)
    fun count(value: Number?) = DiagnosticValue(value?.toLong()?.takeIf { it >= 0 })
    fun state(value: Enum<*>?) = DiagnosticValue(value?.name)
    fun step(value: Any?) = state(DiagnosticStep.entries.firstOrNull { it.name == value } ?: DiagnosticStep.unknown)
    fun unknown() = DiagnosticValue("[redacted]")
    fun failure(value: Throwable?) = DiagnosticValue(when (value) {
        null -> "absent"
        is java.util.concurrent.TimeoutException -> "timeout"
        is SecurityException -> "permission"
        is IllegalArgumentException -> "argument"
        is IllegalStateException -> "state"
        else -> "other"
    })
    companion object { val session = NativeObfuscation() }
}

/** Both construction and output are contained. Never prints Throwable or stack. */
object NativeDiagnostics {
    fun record(code: Enum<*>, enabled: Boolean = true,
        sink: ((String) -> Unit)? = null,
        fields: NativeObfuscation.() -> Map<DiagnosticField, DiagnosticValue> = { emptyMap() }) {
        if (!enabled) return
        try {
            val data = NativeObfuscation.session.fields()
            val line = "${code.name} $data"
            if (sink != null) sink(line) else when (code) {
                ProviderDiagnostic.recurrenceRepairApplied, ProviderDiagnostic.deletionRepairApplied -> Log.i("KeepCalSyncRepair", line)
                ProviderDiagnostic.recurrenceRepairFailed -> Log.w("KeepCalSyncRepairDebug", line)
                else -> Log.d("KeepCalDiagnostics", line)
            }
        } catch (_: Throwable) { /* Diagnostic failures never change provider outcomes. */ }
    }
}

enum class ProviderDiagnostic {
    metadataRowFailed, metadataReadFailed, recurrencePruned, seriesMoved,
    exceptionsReset, masterSnapshot, exceptionSnapshot, snapshotFailed,
    splitExperiment, splitExperimentReadFailed, splitExperimentConfigured,
    replyFailed, recurrenceRepairApplied, recurrenceRepairFailed, deletionRepairApplied,
}
