package com.builttoroam.devicecalendar

import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentValues
import android.content.ContentUris
import android.database.Cursor
import android.database.CursorWrapper
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import com.builttoroam.devicecalendar.models.Event
import com.builttoroam.devicecalendar.models.Reminder
import com.google.gson.JsonParser
import com.google.gson.Gson
import io.flutter.plugin.common.MethodChannel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowContentResolver
import java.time.ZonedDateTime
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async

/** Real delegate + SQLite assertions/transactions. Latches model a slow Binder,
 * not a fake successful native mutation. No wall-clock performance threshold. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CalendarWriteThreadingTest {
    private lateinit var provider: WriteThreadProvider
    private lateinit var delegate: CalendarDelegate
    private val replies = mutableListOf<Reply>()
    private val monday = ZonedDateTime.parse("2026-09-07T19:45:00+01:00[Europe/London]")
    private val start get() = monday.toInstant().toEpochMilli()

    @Before fun setup() {
        provider = WriteThreadProvider()
        provider.onCreate()
        provider.seed(start, 5, true, true)
        provider.db.execSQL("INSERT INTO Attendees (event_id, attendeeEmail, attendeeStatus, attendeeRelationship, attendeeType) VALUES (21096, 'owner@example.com', 1, 2, 1)")
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        delegate = CalendarDelegate(null, RuntimeEnvironment.getApplication())
    }
    @After fun close() {
        provider.release.countDown()
        // Even a failed assertion must drain admitted writes before closing SQL.
        replies.forEach { await { it.calls.get() > 0 } }
        provider.db.close()
    }
    private fun reply() = Reply().also { replies.add(it) }
    private fun rename(expected: String, requested: String, d: CalendarDelegate = delegate): Reply =
        reply().also { d.applyEventChanges("7", "21096", title(expected, requested), null, it) }
    private fun title(expected: String, requested: String) =
        mapOf<String, Any?>("title" to mapOf("expected" to expected, "requested" to requested))
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        assertTrue("Asynchronous operation did not complete", condition())
    }
    private fun finished(r: Reply) {
        await { r.calls.get() > 0 }
        assertEquals(1, r.calls.get())
        assertEquals(listOf(true), r.onMain.toList())
    }
    private fun updated(r: Reply) {
        finished(r)
        assertNull("Native error: ${r.error}", r.error)
        assertEquals("updated", json(r)["outcome"].asString)
    }
    private fun json(r: Reply) = (if (r.value is String) JsonParser.parseString(r.value as String)
        else Gson().toJsonTree(r.value)).asJsonObject
    private fun threads() {
        assertTrue("Provider access unexpectedly stayed on main: ${provider.mainCalls}",
            provider.mainCalls.isEmpty())
        assertTrue("No provider operations executed", provider.calls.get() > 0)
        assertEquals("A write leaked a provider cursor", provider.opened.get(), provider.closes.get())
    }
    private fun held(phase: String) {
        provider.hold = phase
        val r = rename("test-rec", "first")
        await { provider.entered.count == 0L || r.calls.get() > 0 }
        assertEquals("Write never reached held $phase", 0L, provider.entered.count)
        assertEquals("Reply before provider $phase completed", 0, r.calls.get())
        var heartbeat = false
        Handler(Looper.getMainLooper()).post { heartbeat = true }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Main looper blocked", heartbeat)
        provider.release.countDown()
        updated(r)
        assertEquals("first", provider.row(21096)!!.getAsString(Events.TITLE))
        threads()
    }
    @Test fun heldPreReadDoesNotBlockMain() = held("query")
    @Test fun heldBatchDoesNotBlockMain() = held("batch")
    @Test fun heldPostCommitDoesNotAcknowledgeEarly() = held("afterBatch")

    @Test fun cursorReadsAndCleanupAreOffMain() {
        updated(rename("test-rec", "first"))
        assertTrue(provider.cursorReads.get() > 0)
        assertTrue(provider.closes.get() > 0)
        threads()
    }

    @Test fun replacementDirectReadDoesNotRaceOutstandingOldWrite() = replacementRead(false)
    @Test fun replacementRangeReadDoesNotRaceOutstandingOldWrite() = replacementRead(true)
    private fun replacementRead(range: Boolean) {
        provider.hold = "batch"
        val write = rename("test-rec", "first")
        await { provider.entered.count == 0L || write.calls.get() > 0 }
        assertEquals(0, write.calls.get())
        val replacement = CalendarDelegate(null, RuntimeEnvironment.getApplication())
        val read = reply()
        if (range) replacement.retrieveEvents("7", start, start + 3600000, listOf("21096"), read)
        else replacement.retrieveEvent("7", "21096", read)
        // Give the real IO dispatcher an execution opportunity while the
        // deterministic provider latch keeps the old write uncommitted.
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(150)
        while (System.nanoTime() < deadline && read.calls.get() == 0) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        assertEquals("New delegate published pre-write recovery evidence", 0, read.calls.get())
        provider.release.countDown()
        updated(write); finished(read)
        assertNull(read.error)
        val value = JsonParser.parseString(read.value as String)
        val event = if (range) value.asJsonArray[0].asJsonObject else value.asJsonObject
        assertEquals("first", event["eventTitle"].asString)
        threads()
    }

    private fun ordered(replaceDelegate: Boolean, rejectFirst: Boolean = false) {
        provider.hold = "batch"
        if (rejectFirst) provider.failure = "batch"
        val first = rename("test-rec", "first")
        await { provider.entered.count == 0L || first.calls.get() > 0 }
        assertEquals(0L, provider.entered.count)
        val before = provider.calls.get()
        val nextDelegate = if (replaceDelegate)
            CalendarDelegate(null, RuntimeEnvironment.getApplication()) else delegate
        val second = rename(if (rejectFirst) "test-rec" else "first", "second", nextDelegate)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Successor touched provider before predecessor completed", before, provider.calls.get())
        assertEquals(0, second.calls.get())
        provider.release.countDown()
        if (rejectFirst) { finished(first); assertNotNull(first.error) } else updated(first)
        updated(second)
        assertEquals("second", provider.row(21096)!!.getAsString(Events.TITLE))
        threads()
    }
    @Test fun sameDelegateSerializesPreconditionsAndWrites() = ordered(false)
    @Test fun replacementDelegateSharesWriteLane() = ordered(true)
    @Test fun activityDestructionDoesNotAbandonAdmittedWrite() {
        val app = RuntimeEnvironment.getApplication()
        val controller = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR)
        val activity = controller.get()
        val binding = java.lang.reflect.Proxy.newProxyInstance(
            io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding::class.java.classLoader,
            arrayOf(io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding::class.java)) { _, method, _ ->
            check(Looper.myLooper() == Looper.getMainLooper())
            if (method.name == "getActivity") activity else error(method.name)
        } as io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
        provider.hold = "batch"
        val first = rename("test-rec", "first", CalendarDelegate(binding, app))
        await { provider.entered.count == 0L || first.calls.get() > 0 }
        controller.pause().stop().destroy()
        assertEquals(0, first.calls.get())
        val second = rename("first", "second", CalendarDelegate(null, app))
        assertEquals(0, second.calls.get())
        provider.release.countDown()
        updated(first); updated(second)
        assertEquals("second", provider.row(21096)!!.getAsString(Events.TITLE))
        threads()
    }
    @Test fun rejectedBatchDoesNotPoisonNextWrite() = ordered(false, true)

    private fun failed(phase: String) {
        provider.failure = phase
        val r = rename("test-rec", "first")
        finished(r)
        assertNotNull("Failure became a successful reply", r.error)
        val committed = phase == "afterBatch"
        assertEquals(if (committed) "first" else "test-rec", provider.row(21096)!!.getAsString(Events.TITLE))
        updated(rename(if (committed) "first" else "test-rec", "second"))
        assertEquals("second", provider.row(21096)!!.getAsString(Events.TITLE))
        threads()
    }
    @Test fun queryFailureReleasesLane() = failed("query")
    @Test fun cursorReadFailureReleasesLane() = failed("cursor")
    @Test fun cursorCloseFailureReleasesLane() = failed("close")
    @Test fun batchRejectionReleasesLane() = failed("batch")
    @Test fun postCommitFailureDoesNotReplayWrite() {
        failed("afterBatch")
        assertEquals("No automatic retry after uncertain result", 2, provider.batches.get())
    }
    @Test fun revokedPermissionIsAnErrorNotSuccess() = failed("permission")

    @Test fun expectedValueConflictDoesNotChangeProvider() {
        val r = rename("stale", "wrong")
        finished(r)
        assertNull(r.error)
        assertEquals("conflict", json(r)["outcome"].asString)
        assertEquals("test-rec", provider.row(21096)!!.getAsString(Events.TITLE))
        updated(rename("test-rec", "right"))
        threads()
    }

    @Test fun nestedPayloadIsCapturedBeforeQueueWait() {
        provider.hold = "batch"
        val first = rename("test-rec", "first")
        await { provider.entered.count == 0L || first.calls.get() > 0 }
        val nested = mutableMapOf<String, Any?>("expected" to "first", "requested" to "second")
        val changes = mutableMapOf<String, Any?>("title" to nested)
        val second = reply()
        delegate.applyEventChanges("7", "21096", changes, null, second)
        nested["requested"] = "mutated after submission"
        changes.clear()
        provider.release.countDown()
        updated(first); updated(second)
        assertEquals("second", provider.row(21096)!!.getAsString(Events.TITLE))
        threads()
    }

    private fun crossEntry(kind: String) {
        provider.hold = "batch"
        val first = rename("test-rec", "first")
        await { provider.entered.count == 0L || first.calls.get() > 0 }
        val before = provider.calls.get()
        val second = reply()
        val event = Event().apply {
            eventTitle = "created"; eventStartDate = start; eventEndDate = start + 3600000
            eventStartTimeZone = "Europe/London"; eventEndTimeZone = "Europe/London"
        }
        when (kind) {
            "create" -> delegate.createOrUpdateEvent("7", event, second)
            "update" -> { event.eventId = "900"; delegate.createOrUpdateEvent("7", event, second) }
            "delete" -> delegate.deleteEvent("7", "900", second)
            "rsvp" -> delegate.updateAttendeeStatus("7", "21096", "owner@example.com", 1, 4, null, second)
            "createCalendar" -> delegate.createCalendar("New", "#FFFFFF", "local", second)
            "deleteCalendar" -> delegate.deleteCalendar("7", second)
            else -> error(kind)
        }
        assertEquals("Other write entry overtook held batch", before, provider.calls.get())
        assertEquals(0, second.calls.get())
        event.eventTitle = "mutated"
        provider.release.countDown()
        updated(first)
        finished(second)
        assertNull(second.error)
        when (kind) {
            "create" -> assertEquals("created", provider.row((second.value as String).toLong())!!.getAsString(Events.TITLE))
            "update" -> assertEquals("created", provider.row(900)!!.getAsString(Events.TITLE))
            "delete" -> assertEquals(1, provider.row(900)!!.getAsInteger(Events.DELETED))
            "rsvp" -> {
                assertEquals("updated", json(second)["outcome"].asString)
                provider.db.rawQuery("SELECT attendeeStatus FROM Attendees WHERE event_id=21096", null).use {
                    assertTrue(it.moveToFirst()); assertEquals(4, it.getInt(0))
                }
            }
        }
        threads()
    }
    @Test fun creationCannotOvertakeMutation() = crossEntry("create")
    @Test fun legacyUpdateCannotOvertakeMutation() = crossEntry("update")
    @Test fun deletionCannotOvertakeMutation() = crossEntry("delete")
    @Test fun rsvpCannotOvertakeMutation() = crossEntry("rsvp")
    @Test fun calendarCreationCannotOvertakeMutation() = crossEntry("createCalendar")
    @Test fun calendarDeletionCannotOvertakeMutation() = crossEntry("deleteCalendar")

    @Test fun createDoesNotReplyBeforeRemindersArePersisted() {
        provider.hold = "insertReminder"
        val event = Event().apply {
            eventTitle = "created"; eventStartDate = start; eventEndDate = start + 3600000
            reminders.add(Reminder(30, 1))
        }
        val r = reply()
        delegate.createOrUpdateEvent("7", event, r)
        await { provider.entered.count == 0L || r.calls.get() > 0 }
        assertEquals(0L, provider.entered.count)
        assertEquals(0, r.calls.get())
        provider.release.countDown()
        finished(r)
        assertNull(r.error)
        provider.db.rawQuery("SELECT minutes FROM Reminders WHERE event_id=?", arrayOf(r.value.toString())).use {
            assertTrue(it.moveToFirst()); assertEquals(30, it.getInt(0))
        }
        threads()
    }


    @Test fun calendarColorCannotOvertakeMutation() {
        provider.hold = "batch"
        val first = rename("test-rec", "first")
        await { provider.entered.count == 0L || first.calls.get() > 0 }
        val before = provider.calls.get()
        val second = reply()
        delegate.updateCalendarColor(7, 3, 0xff123456.toInt(), second)
        assertEquals(before, provider.calls.get())
        assertEquals(0, second.calls.get())
        provider.release.countDown()
        updated(first); finished(second)
        assertEquals(true, second.value)
        provider.db.rawQuery("SELECT calendar_color FROM Calendars WHERE _id=7", null).use {
            assertTrue(it.moveToFirst()); assertEquals(0xff123456.toInt(), it.getInt(0))
        }
        threads()
    }

    @Test fun missingCompletionReturnsErrorAndDoesNotStrandNextWrite() {
        val r = reply()
        CalendarWriteExecutor.submit(r, {}) { }
        finished(r)
        assertNotNull(r.error)
        updated(rename("test-rec", "next"))
    }

    @Test fun recordedSuccessWaitsForAllCleanup() {
        val r = reply()
        val cleaned = AtomicInteger()
        CalendarWriteExecutor.submit(r, { cleaned.incrementAndGet() }) {
            it.success("done")
            provider.entered.countDown()
            check(provider.release.await(8, TimeUnit.SECONDS))
        }
        await { provider.entered.count == 0L }
        assertEquals(0, r.calls.get())
        provider.release.countDown()
        finished(r)
        assertEquals("done", r.value)
        assertEquals(1, cleaned.get())
    }

    @Test fun throwAfterRecordedSuccessIsOneUncertainError() {
        val r = reply()
        CalendarWriteExecutor.submit(r, {}) {
            it.success("not finished yet")
            throw IllegalStateException("cleanup did not finish")
        }
        finished(r)
        assertTrue(r.error!!.startsWith("500:"))
        assertNull(r.value)
        updated(rename("test-rec", "next"))
    }

    @Test fun duplicateCompletionDoesNotSendMultipleReplies() {
        val r = reply()
        CalendarWriteExecutor.submit(r, {}) {
            it.success("first"); it.success("second"); it.error("500", "third", null)
        }
        finished(r)
        assertEquals("first", r.value)
        updated(rename("test-rec", "next"))
    }

    @Test fun lostChannelDoesNotReplayOrBlockLaterWrites() {
        val callbacks = AtomicInteger()
        val cleaned = AtomicInteger()
        val writes = AtomicInteger()
        val gone = object : MethodChannel.Result {
            override fun success(result: Any?) { callbacks.incrementAndGet(); error("engine detached") }
            override fun error(code: String, message: String?, details: Any?) { callbacks.incrementAndGet(); error("engine detached") }
            override fun notImplemented() { error("unexpected") }
        }
        CalendarWriteExecutor.submit(gone, { cleaned.incrementAndGet() }) {
            writes.incrementAndGet()
            delegate.applyEventChanges("7", "21096", title("test-rec", "first"), null, it)
        }
        val next = rename("first", "second")
        updated(next)
        assertEquals(1, callbacks.get()); assertEquals(1, cleaned.get()); assertEquals(1, writes.get())
        assertEquals("second", provider.row(21096)!!.getAsString(Events.TITLE))
    }

    private fun withPermission(granted: Boolean, body: (CalendarDelegate, () -> Unit) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        val controller = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        try {
            val activity = controller.get()
            shadowOf(app).denyPermissions(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR)
            val binding = java.lang.reflect.Proxy.newProxyInstance(
                io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding::class.java.classLoader,
                arrayOf(io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding::class.java)) { _, method, _ ->
                check(Looper.myLooper() == Looper.getMainLooper()) { "Permission UI accessed off main" }
                if (method.name == "getActivity") activity else error(method.name)
            } as io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
            val d = CalendarDelegate(binding, app)
            body(d) {
                val request = shadowOf(activity).lastRequestedPermission
                assertNotNull(request)
                assertEquals(0, provider.calls.get())
                if (granted) shadowOf(app).grantPermissions(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR)
                d.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                    intArrayOf(if (granted) android.content.pm.PackageManager.PERMISSION_GRANTED
                    else android.content.pm.PackageManager.PERMISSION_DENIED))
            }
        } finally {
            shadowOf(app).grantPermissions(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR)
            controller.pause().stop().destroy()
        }
    }
    @Test fun permissionDenialDoesNotTouchProviderOrStrandRequest() = withPermission(false) { d, resume ->
        val r = rename("test-rec", "first", d)
        resume(); finished(r)
        assertTrue(r.error!!.startsWith("401:"))
        assertEquals(0, provider.calls.get())
    }
    @Test fun permissionRetryCapturesNestedChangesAndRecurrenceScope() = withPermission(true) { d, resume ->
        val scope = mutableMapOf<String, Any?>("scope" to "entireSeries",
            "originalEventId" to "21096", "originalOccurrenceStart" to start,
            "selectedOccurrenceWasDetached" to false, "resetDetachedOverrides" to true)
        val nested = mutableMapOf<String, Any?>("expected" to "test-rec", "requested" to "renamed")
        val r = reply()
        d.applyEventChanges("7", "21096", mapOf("title" to nested), scope, r)
        scope["scope"] = "invalid"; nested["requested"] = "tampered"
        resume(); updated(r)
        assertEquals("renamed", provider.row(21096)!!.getAsString(Events.TITLE))
        assertEquals("renamed", provider.row(21098)!!.getAsString(Events.TITLE))
        threads()
    }
    @Test fun permissionRetryCapturesEventAndRecurrenceLists() = withPermission(true) { d, resume ->
        val event = Event().apply {
            eventTitle = "created"; eventStartDate = start; eventEndDate = start + 3600000
            recurrenceRule = com.builttoroam.devicecalendar.models.RecurrenceRule(org.dmfs.rfc5545.recur.Freq.DAILY).apply {
                count = 3; byday = mutableListOf("MO", "TU")
            }
            reminders.add(Reminder(30))
        }
        val r = reply()
        d.createOrUpdateEvent("7", event, r)
        event.eventTitle = "tampered"; event.recurrenceRule!!.count = 8
        event.recurrenceRule!!.byday!!.clear(); event.reminders.clear()
        resume(); finished(r)
        assertNull(r.error)
        val row = provider.row(r.value.toString().toLong())!!
        assertEquals("created", row.getAsString(Events.TITLE))
        assertTrue(row.getAsString(Events.RRULE).contains("COUNT=3"))
        assertTrue(row.getAsString(Events.RRULE).contains("BYDAY=MO,TU"))
        threads()
    }
    @Test fun permissionRetryDoesNotTurnInstanceDeletionIntoSeriesDeletion() = withPermission(true) { d, resume ->
        val r = reply()
        d.deleteEvent("7", "21096", r, start + 2 * 86400000, start + 2 * 86400000 + 3600000, false)
        resume(); finished(r)
        assertNull(r.error)
        assertEquals(true, r.value)
        assertEquals(0, provider.row(21096)!!.getAsInteger(Events.DELETED))
        assertEquals(start + 2 * 86400000, provider.lastInserted!!.getAsLong(Events.ORIGINAL_INSTANCE_TIME))
        assertEquals(Events.STATUS_CANCELED, provider.lastInserted!!.getAsInteger(Events.STATUS))
        threads()
    }
    @Test fun denyingOneOfSeveralPermissionRequestsDoesNotLoseAnother() {
        val app = RuntimeEnvironment.getApplication()
        val controller = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        try {
            val activity = controller.get()
            shadowOf(app).denyPermissions(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR)
            val binding = java.lang.reflect.Proxy.newProxyInstance(
                io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding::class.java.classLoader,
                arrayOf(io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding::class.java)) { _, method, _ ->
                if (method.name == "getActivity") activity else error(method.name)
            } as io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
            val d = CalendarDelegate(binding, app)
            val pending = (1..5).map {
                val r = rename("test-rec", "next", d)
                r to shadowOf(activity).lastRequestedPermission
            }
            pending.forEach { (r, request) ->
                d.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                    intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED))
                finished(r); assertTrue(r.error!!.startsWith("401:"))
            }
            assertEquals(0, provider.calls.get())
        } finally {
            shadowOf(app).grantPermissions(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR)
            controller.pause().stop().destroy()
        }
    }

    @Test fun rejectedSubmissionDoesNotStrandIdleLane() = submissionRejection(false)
    @Test fun rejectedSubmissionKeepsPredecessorInHandoverBarrier() = submissionRejection(true)
    private fun submissionRejection(predecessor: Boolean) {
        val queued = java.util.ArrayDeque<Runnable>()
        var reject = false
        val lane = CalendarWriteLane(java.util.concurrent.Executor {
            if (reject) throw java.util.concurrent.RejectedExecutionException("injected scheduling failure")
            queued.add(it)
        })
        val effects = AtomicInteger()
        val cleanups = AtomicInteger()
        fun runNext() {
            val task = queued.removeFirst()
            val thread = Thread(task)
            thread.start(); thread.join(8000)
            assertFalse("Controlled native worker did not finish", thread.isAlive)
        }
        val first = if (predecessor) reply().also { r ->
            lane.submit(r, { cleanups.incrementAndGet() }) { effects.incrementAndGet(); it.success("first") }
        } else null
        reject = true
        val rejected = reply()
        lane.submit(rejected, { cleanups.incrementAndGet() }) {
            effects.incrementAndGet(); it.success("must never run")
        }
        finished(rejected)
        assertTrue(rejected.error!!.startsWith("500:"))
        assertEquals(0, effects.get())
        assertEquals(1, cleanups.get())
        val barrier = lane.captureHandoverBarrier()
        kotlinx.coroutines.runBlocking {
            val wait = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { barrier() }
            assertEquals(!predecessor, wait.isCompleted)
            if (predecessor) runNext()
            wait.await()
        }
        first?.let { finished(it) }
        reject = false
        val next = reply()
        lane.submit(next, { cleanups.incrementAndGet() }) { effects.incrementAndGet(); it.success("next") }
        runNext(); finished(next)
        assertEquals("next", next.value)
        assertEquals(if (predecessor) 2 else 1, effects.get())
        assertEquals(if (predecessor) 3 else 2, cleanups.get())
    }

    @Test fun cancelledHandoverWaitCannotCancelSharedWriteCompletion() {
        provider.hold = "batch"
        val first = rename("test-rec", "first")
        await { provider.entered.count == 0L || first.calls.get() > 0 }
        val barrier = CalendarWriteExecutor.captureHandoverBarrier()
        kotlinx.coroutines.runBlocking {
            val abandoned = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { barrier() }
            assertFalse(abandoned.isCompleted)
            abandoned.cancel()
            abandoned.join()
            val live = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { barrier() }
            assertFalse("Cancelling a reader released the write barrier", live.isCompleted)
            provider.release.countDown()
            kotlinx.coroutines.withTimeout(8000) { live.await() }
        }
        updated(first)
        updated(rename("first", "second"))
        threads()
    }
    // 2026-09-15 16:12 capture: Samsung deletes separate Wed and Thu/Fri
    // roots; KeepCal removes the Mon/Tue recurrence, then adds COUNT=5 to
    // its replacement. Execute real native writes, not canned ACKs. This
    // isolates KeepCal's effects; it does NOT emulate Google's sync writer.
    @Test fun recurrenceReplacementDoesNotUndeleteSamsungDeletedRoots() =
        deletedRootsIsolation(false)

    @Test fun recurrenceReplacementDoesNotUndeleteRootsWithReversedSqlOrder() =
        deletedRootsIsolation(true)

    private fun deletedRootsIsolation(reverse: Boolean) {
        if (reverse) provider.db.execSQL("PRAGMA reverse_unordered_selects = ON")
        provider.db.execSQL("DELETE FROM Events WHERE _id=21098")
        provider.db.execSQL("UPDATE Events SET deleted=1 WHERE _id=21097")
        provider.db.execSQL("UPDATE Events SET dtstart=?, rrule='FREQ=DAILY;COUNT=2;WKST=MO' WHERE _id=21096",
            arrayOf(start + 3600000))
        provider.db.execSQL("UPDATE Events SET dtstart=?, deleted=1, dirty=1 WHERE _id=900",
            arrayOf(start + 2 * 86400000 - 3600000))
        provider.db.insertOrThrow("Events", null, ContentValues(provider.row(900)!!).apply {
            put(Events._ID, 901)
            put(Events._SYNC_ID, "separate-thursday-friday-root")
            put(Events.DTSTART, start + 3 * 86400000 - 3600000)
            put(Events.RRULE, "FREQ=DAILY;COUNT=2;WKST=MO")
        })
        val unrelatedBefore = listOf(provider.row(900), provider.row(901))
        fun range(at: Long) = EventDateRangeValue(at, "Europe/London",
            at + 3600000, "Europe/London", false).toMap()
        val removed = reply()
        delegate.applyEventChanges("7", "21096", mapOf(
            "dateRange" to mapOf("expected" to range(start + 3600000), "requested" to range(start)),
            "recurrence" to mapOf(
                "expected" to mapOf("rule" to mapOf("freq" to "DAILY", "count" to 2)),
                "requested" to mapOf("rule" to null))),
            mapOf("scope" to "entireSeries", "originalEventId" to "21096",
                "originalOccurrenceStart" to start + 3600000,
                "selectedOccurrenceWasDetached" to false, "resetDetachedOverrides" to true), removed)
        updated(removed)
        val replacement = json(removed)["resultingEventId"].asString
        assertEquals(1, provider.row(21096)!!.getAsInteger(Events.DELETED))
        assertEquals(unrelatedBefore, listOf(provider.row(900), provider.row(901)))
        val added = reply()
        delegate.applyEventChanges("7", replacement, mapOf("recurrence" to mapOf(
            "expected" to mapOf("rule" to null),
            "requested" to mapOf("rule" to mapOf("freq" to "DAILY", "count" to 5)))), null, added)
        updated(added)
        // Range verification and a replacement delegate must not perform repairs
        // or revive deleted roots while materializing provider data.
        val read = reply()
        CalendarDelegate(null, RuntimeEnvironment.getApplication()).retrieveEvents(
            "7", start - 86400000, start + 7 * 86400000, emptyList(), read)
        finished(read)
        assertNull(read.error)
        assertEquals(unrelatedBefore, listOf(provider.row(900), provider.row(901)))
        assertEquals(1, provider.row(21096)!!.getAsInteger(Events.DELETED))
        assertEquals(listOf(replacement.toLong()), provider.rows()
            .filter { it.getAsInteger(Events.DELETED) != 1 }.map { it.getAsLong(Events._ID) })
        assertEquals((0L until 5L).map { start + it * 86400000 }, provider.starts(replacement.toLong()))
        threads()
    }

    private class Reply : MethodChannel.Result {
        val calls = AtomicInteger()
        val onMain = Collections.synchronizedList(mutableListOf<Boolean>())
        var value: Any? = null
        var error: String? = null
        override fun success(result: Any?) { value = result; done() }
        override fun error(code: String, message: String?, details: Any?) { error = "$code: $message"; done() }
        override fun notImplemented() { error = "notImplemented"; done() }
        private fun done() {
            onMain.add(Looper.myLooper() == Looper.getMainLooper())
            calls.incrementAndGet()
        }
    }
}

internal class WriteThreadProvider : TombstoneCalendarProvider() {
    var lastInserted: ContentValues? = null
    val mainCalls = Collections.synchronizedList(mutableListOf<String>())
    val calls = AtomicInteger()
    val batches = AtomicInteger()
    val cursorReads = AtomicInteger()
    val opened = AtomicInteger()
    val closes = AtomicInteger()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    @Volatile var hold: String? = null
    @Volatile var failure: String? = null
    private fun touch(phase: String) {
        calls.incrementAndGet()
        val main = Looper.myLooper() == Looper.getMainLooper()
        if (main) mainCalls.add(phase)
        if (hold == phase) {
            hold = null
            entered.countDown()
            check(!main) { "Blocking provider work on main" }
            check(release.await(8, TimeUnit.SECONDS)) { "Test did not release provider" }
        }
        if (failure == phase || failure == "permission" && phase == "query") {
            val type = failure
            failure = null
            when (type) {
                "batch" -> throw SQLiteException("injected transaction rejection")
                "afterBatch" -> throw RemoteException("reply lost after actual commit")
                "permission" -> throw SecurityException("calendar permission revoked")
                else -> throw IllegalStateException("injected $phase failure")
            }
        }
    }
    override fun onCreate(): Boolean {
        super.onCreate()
        db.execSQL("ALTER TABLE Events ADD COLUMN lastDate INTEGER")
        db.execSQL("ALTER TABLE Events ADD COLUMN visible INTEGER DEFAULT 1")
        db.execSQL("""CREATE TABLE Calendars (_id INTEGER PRIMARY KEY, name TEXT,
            calendar_displayName TEXT, account_name TEXT, account_type TEXT,
            calendar_access_level INTEGER, calendar_color INTEGER, ownerAccount TEXT,
            calendar_timezone TEXT, calendar_color_index TEXT)""")
        db.execSQL("INSERT INTO Calendars (_id) VALUES (7)")
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        touch("query")
        // Instances.query encodes its bounds in the URI. Preserve them in this
        // SQL fixture as well; the parent replay fixture never queried subranges.
        val instanceBounds = uri.pathSegments.takeIf { it.first() == "instances" }?.takeLast(2)
        val from = instanceBounds?.get(0)?.toLongOrNull()
        val to = instanceBounds?.get(1)?.toLongOrNull()
        val boundedSelection = if (from != null && to != null)
            (selection?.let { "($it) AND " } ?: "") + "begin <= $to AND end >= $from" else selection
        return object : CursorWrapper(super.query(uri, projection, boundedSelection, selectionArgs, sortOrder).also { opened.incrementAndGet() }) {
            override fun getString(i: Int): String? { cursorReads.incrementAndGet(); touch("cursor"); return super.getString(i) }
            override fun getLong(i: Int): Long { cursorReads.incrementAndGet(); touch("cursor"); return super.getLong(i) }
            override fun close() { try { super.close() } finally { closes.incrementAndGet(); touch("close") } }
        }
    }
    override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
        touch("batch"); batches.incrementAndGet()
        val result = super.applyBatch(operations)
        touch("afterBatch")
        return result
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri {
        lastInserted = values?.let { ContentValues(it) }
        touch(if (uri.pathSegments.first() == "reminders") "insertReminder" else "insert")
        if (uri.pathSegments.first() == "exception") {
            // Legacy CalendarContract exception insertion copies the master,
            // binds the original slot and applies the supplied cancellation.
            val masterId = uri.lastPathSegment!!.toLong()
            val master = row(masterId)!!
            val occurrence = ContentValues(master).apply {
                remove(Events._ID)
                putNull(Events.RRULE); putNull(Events.DURATION); putNull(Events._SYNC_ID)
                put(Events.ORIGINAL_ID, masterId)
                put(Events.ORIGINAL_SYNC_ID, master.getAsString(Events._SYNC_ID))
                put(Events.ORIGINAL_ALL_DAY, master.getAsInteger(Events.ALL_DAY))
                put(Events.DTSTART, values!!.getAsLong(Events.ORIGINAL_INSTANCE_TIME))
                put(Events.DTEND, values.getAsLong(Events.ORIGINAL_INSTANCE_TIME) + 3600000)
                putAll(values)
            }
            return ContentUris.withAppendedId(Events.CONTENT_URI, db.insertOrThrow("Events", null, occurrence))
        }
        if (uri.pathSegments.first() == "calendars")
            return ContentUris.withAppendedId(uri, db.insertOrThrow("Calendars", null, values))
        return super.insert(uri, values)
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
        touch("update")
        if (uri.pathSegments.first() == "calendars")
            return db.update("Calendars", values, "_id=?", arrayOf(uri.lastPathSegment))
        return super.update(uri, values, selection, args)
    }
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
        touch("delete")
        if (uri.pathSegments.first() == "calendars")
            return db.delete("Calendars", "_id=?", arrayOf(uri.lastPathSegment))
        return super.delete(uri, selection, args)
    }
}
