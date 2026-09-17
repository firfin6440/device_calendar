package com.builttoroam.devicecalendar

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.builttoroam.devicecalendar.common.ErrorCodes
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred

/**
 * Process-wide execution lane, NOT a durable queue or a cross-process lease.
 * No Activity lifecycle owns/cancels an admitted Binder write. A caller's
 * Future remains incomplete until the synchronous operation (including cleanup)
 * has returned. Existing durable ownership/uncertainty rules stay in Dart.
 */
internal object CalendarWriteExecutor {
    private val lane = CalendarWriteLane(Executors.newSingleThreadExecutor { task ->
        Thread(task, "CalendarProviderWrites").apply { isDaemon = true }
    })

    fun captureHandoverBarrier(): suspend () -> Unit = lane.captureHandoverBarrier()

    fun submit(result: MethodChannel.Result, cleanup: () -> Unit,
               operation: (CalendarWriteResult) -> Unit) = lane.submit(result, cleanup, operation)
}

/** Executor must be a serial background executor. Injection permits testing
 * submission rejection without shutting down the process-owned production lane. */
internal class CalendarWriteLane(private val executor: Executor) {
    private val main = Handler(Looper.getMainLooper())
    private var tail = CompletableDeferred(Unit)

    /** Replacement delegates must not recover from data predating a write
     * whose old Dart/engine reply may no longer have a consumer. The captured
     * wait has no cancellation/completion authority over the shared tail. */
    @Synchronized
    fun captureHandoverBarrier(): suspend () -> Unit {
        val inherited = tail
        return { inherited.await() }
    }

    @Synchronized
    fun submit(result: MethodChannel.Result, cleanup: () -> Unit,
               operation: (CalendarWriteResult) -> Unit) {
        val finished = CompletableDeferred<Unit>()
        val predecessor = tail
        tail = finished
        val completion = CalendarWriteResult()
        try {
            executor.execute {
                try {
                    // The owning runtime can retire while this request waits
                    // behind another Binder operation on the serial lane.
                    (result as? CalendarInvocationFence)?.checkCalendarInvocation()
                    operation(completion)
                } catch (failure: Throwable) {
                    // This may be AFTER a commit. Never invent rollback proof
                    // or retry here. Generic errors remain uncertain.
                    completion.failed(failure)
                } finally {
                    // Actual native completion, independent of a live reply.
                    finished.complete(Unit)
                    deliver(completion, result, cleanup)
                }
            }
        } catch (failure: Throwable) {
            // Rejected submission must neither strand the tail nor drop an
            // earlier outstanding write from the replacement-read barrier.
            tail = predecessor
            completion.failed(failure)
            deliver(completion, result, cleanup)
        }
    }

    private fun deliver(completion: CalendarWriteResult, result: MethodChannel.Result,
                        cleanup: () -> Unit) {
        main.post {
            try {
                completion.deliver(result)
            } catch (failure: Throwable) {
                // Engine/channel loss is not authority to replay SQL.
                Log.e("CalendarWriteExecutor", "Write reply delivery failed", failure)
            } finally {
                cleanup()
            }
        }
    }
}

/** Worker-local outcome. Do not mutate main-thread permission caches with it. */
internal class CalendarWriteResult : MethodChannel.Result {
    private var response: ((MethodChannel.Result) -> Unit)? = null

    override fun success(result: Any?) {
        if (response == null) response = { it.success(result) }
    }
    override fun error(code: String, message: String?, details: Any?) {
        if (response == null) response = { it.error(code, message, details) }
    }
    override fun notImplemented() {
        if (response == null) response = { it.notImplemented() }
    }
    fun failed(failure: Throwable) {
        // A success recorded before failed cleanup is not a completed success.
        response = { it.error(ErrorCodes.GENERIC_ERROR, failure.message, null) }
    }
    fun deliver(result: MethodChannel.Result) {
        val reply = response
        if (reply == null) {
            result.error(ErrorCodes.GENERIC_ERROR, "Calendar write ended without a result", null)
        } else {
            reply(result)
        }
    }
}

/** Channel payloads contain immutable scalars and potentially mutable containers. */
internal fun snapshotCalendarArguments(values: Map<String, Any?>): Map<String, Any?> =
    values.mapValues { (_, value) -> snapshotCalendarValue(value) }

private fun snapshotCalendarValue(value: Any?): Any? = when (value) {
    is Map<*, *> -> value.mapValues { (_, child) -> snapshotCalendarValue(child) }
    is List<*> -> value.map { snapshotCalendarValue(it) }
    is ByteArray -> value.copyOf()
    is IntArray -> value.copyOf()
    is LongArray -> value.copyOf()
    is DoubleArray -> value.copyOf()
    is FloatArray -> value.copyOf()
    else -> value
}
