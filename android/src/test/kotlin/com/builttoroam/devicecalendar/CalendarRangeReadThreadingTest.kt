package com.builttoroam.devicecalendar

import android.Manifest
import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.CursorWrapper
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import com.builttoroam.devicecalendar.common.Constants
import com.builttoroam.devicecalendar.models.Event
import com.google.gson.*
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodChannel
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowContentResolver

/** Real delegate/ContentResolver/IO/Handler/Gson. Provider fixtures only control
 * external rows, errors and timing; they do not fake a successful plugin result.
 * Latch deadlines are deadlock guards, not performance thresholds. Existing
 * SQLite suites verify recurrence semantics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CalendarRangeReadThreadingTest {
    private lateinit var provider: ReadProvider
    private val replies = mutableListOf<Reply>()
    private val app get() = RuntimeEnvironment.getApplication()
    private val slot = 1789482600000L
    private fun mainThread() = Looper.myLooper() == Looper.getMainLooper()
    @Before fun setup() {
        provider = ReadProvider()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        assertTrue("Call the production entry point on Android main", mainThread())
    }
    @After fun release() {
        provider.resume.countDown()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun calendarQueryCannotBlockTheMainLooper() = heartbeat("calendars")
    @Test fun instancesQueryCannotBlockTheMainLooper() = heartbeat("instances")
    private fun heartbeat(path: String) {
        provider.gatePath = path
        val reply = read()
        assertTrue("Worker did not reach held query", provider.entered.await(5, TimeUnit.SECONDS))
        try {
            var heartbeat = false
            Handler(Looper.getMainLooper()).post { heartbeat = true }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(heartbeat)
            assertTrue("No result before provider release", reply.results.isEmpty())
        } finally { provider.resume.countDown() }
        await(reply)
        assertNull(reply.single().error)
        assertAllWorkOffMainAndClosed()
    }

    @Test fun fullReadAndSerializationStayOffMainAndReplyOnMain() {
        val delegate = CalendarDelegate(null, app)
        val serialized = CopyOnWriteArrayList<Boolean>()
        observeSerialization(delegate) { serialized.add(mainThread()) }
        val reply = read(delegate = delegate)
        await(reply)
        val row = JsonParser.parseString(reply.single().value as String).asJsonArray.single().asJsonObject
        assertEquals("701", row["eventId"].asString)
        assertEquals("7", row["calendarId"].asString)
        assertEquals("Europe/London", row["eventStartTimeZone"].asString)
        assertEquals(slot, row["eventOriginalStartDate"].asLong)
        assertEquals("700", row["originalEventId"].asString)
        assertEquals("remote-701", row["syncId"].asString)
        assertEquals(1, row["attendees"].asJsonArray.size())
        assertEquals(1, row["reminders"].asJsonArray.size())
        assertTrue(serialized.isNotEmpty())
        assertTrue("Gson must not serialize events on main", serialized.none { it })
        assertEquals(listOf("calendars", "instances", "attendees", "reminders"), provider.queries.map { it.path })
        assertAllWorkOffMainAndClosed()
    }

    @Test fun calendarQueryFailureIsOneError() = failure("query:calendars")
    @Test fun instancesQueryFailureIsOneError() = failure("query:instances")
    @Test fun attendeesQueryFailureIsOneError() = failure("query:attendees")
    @Test fun remindersQueryFailureIsOneError() = failure("query:reminders")
    @Test fun calendarParseFailureClosesCursorAndIsOneError() = failure("read:calendars")
    @Test fun instancesParseFailureClosesCursorAndIsOneError() = failure("read:instances")
    @Test fun attendeeParseFailureClosesCursorsAndIsOneError() = failure("read:attendees")
    @Test fun reminderParseFailureClosesCursorsAndIsOneError() = failure("read:reminders")
    @Test fun calendarCloseFailureIsOneError() = failure("close:calendars")
    @Test fun instancesCloseFailureIsOneError() = failure("close:instances")
    @Test fun attendeeCloseFailureIsOneError() = failure("close:attendees")
    @Test fun reminderCloseFailureIsOneError() = failure("close:reminders")
    private fun failure(at: String) {
        provider.failAt = at
        val reply = read()
        await(reply)
        assertEquals("500", reply.single().error)
        assertTrue(reply.single().message.orEmpty().contains(at))
        assertAllWorkOffMainAndClosed()
    }

    @Test fun nullCalendarCursorIsFailureNotMissingCalendar() = nullCursor("calendars")
    @Test fun nullInstancesIsFailureNotAuthoritativeEmptyRange() = nullCursor("instances")
    @Test fun nullAttendeesIsFailureNotAuthoritativeEmptyAttendees() = nullCursor("attendees")
    @Test fun nullRemindersIsFailureNotAuthoritativeEmptyReminders() = nullCursor("reminders")
    private fun nullCursor(path: String) {
        provider.nullPath = path
        val reply = read()
        await(reply)
        assertEquals("500", reply.single().error)
        assertAllWorkOffMainAndClosed()
    }

    @Test fun revokedPermissionDuringQueryIsAnErrorNotAnEmptyRange() {
        provider.revoke = true
        val reply = read()
        await(reply)
        assertEquals("500", reply.single().error)
        assertTrue(reply.single().message.orEmpty().contains("revoked"))
        assertAllWorkOffMainAndClosed()
    }
    @Test fun emptyInstancesIsASuccessfulEmptyRange() {
        provider.emptyPath = "instances"
        val reply = read()
        await(reply)
        assertNull(reply.single().error)
        assertEquals("[]", reply.single().value)
        assertEquals(listOf("calendars", "instances"), provider.queries.map { it.path })
        assertAllWorkOffMainAndClosed()
    }
    @Test fun missingCalendarIsOneNotFoundWithoutInstancesQuery() {
        provider.emptyPath = "calendars"
        val reply = read()
        await(reply)
        assertEquals("404", reply.single().error)
        assertEquals(listOf("calendars"), provider.queries.map { it.path })
        assertAllWorkOffMainAndClosed()
    }
    @Test fun realEmptyAttendeesRemainValid() = emptyChildren("attendees")
    @Test fun realEmptyRemindersRemainValid() = emptyChildren("reminders")
    private fun emptyChildren(path: String) {
        provider.emptyPath = path
        val reply = read()
        await(reply)
        assertNull(reply.single().error)
        val event = JsonParser.parseString(reply.single().value as String).asJsonArray.single().asJsonObject
        assertEquals(0, event[path].asJsonArray.size())
        assertAllWorkOffMainAndClosed()
    }
    @Test fun openStartRetainsTheEpochBound() = openBound(true)
    @Test fun openEndRetainsTheMaximumBound() = openBound(false)
    private fun openBound(openStart: Boolean) {
        val from = if (openStart) null else slot
        val to = if (openStart) slot + 2000 else null
        val reply = read(start = from, end = to)
        await(reply)
        assertNull(reply.single().error)
        val query = provider.queries.single { it.path == "instances" }
        assertEquals(listOf((from ?: 0L).toString(), (to ?: Long.MAX_VALUE).toString()),
            query.uri.pathSegments.takeLast(2))
        val expected = calendarInstanceQuery("7", from ?: 0L, to ?: Long.MAX_VALUE, emptyList())
        assertEquals(expected.selection, query.selection)
        assertEquals(expected.selectionArgs.toList(), query.args)
        assertAllWorkOffMainAndClosed()
    }
    @Test fun invalidCalendarIdKeepsNotFoundAndDoesNotQuery() {
        val reply = read(calendar = "not-a-number")
        await(reply)
        assertEquals("404", reply.single().error)
        assertTrue(provider.queries.isEmpty())
    }
    @Test fun serializerFailureIsOneMainThreadErrorAfterCleanup() {
        val delegate = CalendarDelegate(null, app)
        observeSerialization(delegate) { throw IllegalStateException("serialize-failure") }
        val reply = read(delegate = delegate)
        await(reply)
        assertEquals("500", reply.single().error)
        assertTrue(reply.single().message.orEmpty().contains("serialize-failure"))
        assertAllWorkOffMainAndClosed()
    }

    @Test fun overlappingReadsCanFinishInReverseWithoutMixingReplies() = overlap(false, false)
    @Test fun oneFailedOverlappingReadDoesNotPoisonTheOther() = overlap(true, false)
    @Test fun replacementDelegateCannotStealOldDelegateReply() = overlap(false, true)
    private fun overlap(failFirst: Boolean, replaceDelegate: Boolean) {
        provider.gatePath = "instances"
        provider.gateCalendar = "7"
        val delegate = CalendarDelegate(null, app)
        val first = read(delegate = delegate, calendar = "7", start = slot, end = slot + 100)
        assertTrue(provider.entered.await(5, TimeUnit.SECONDS))
        try {
            val second = read(delegate = if (replaceDelegate) CalendarDelegate(null, app) else delegate,
                calendar = "8", start = slot + 1000, end = slot + 2000)
            await(second)
            assertEquals("8", JsonParser.parseString(second.single().value as String)
                .asJsonArray.single().asJsonObject["calendarId"].asString)
            assertTrue(first.results.isEmpty())
            if (failFirst) provider.failAfterGate = true
        } finally { provider.resume.countDown() }
        await(first)
        if (failFirst) assertEquals("500", first.single().error)
        else assertEquals("7", JsonParser.parseString(first.single().value as String)
            .asJsonArray.single().asJsonObject["calendarId"].asString)
        assertAllWorkOffMainAndClosed()
    }

    @Test fun eventIdsAreCapturedAndExactQueryArgumentsArePreserved() {
        provider.gatePath = "calendars"
        val ids = mutableListOf("701")
        val reply = read(start = slot, end = slot + 2000, ids = ids)
        assertTrue(provider.entered.await(5, TimeUnit.SECONDS))
        try { ids.clear(); ids.add("999") } finally { provider.resume.countDown() }
        await(reply)
        val query = provider.queries.single { it.path == "instances" }
        val expected = calendarInstanceQuery("7", slot, slot + 2000, listOf("701"))
        assertEquals(expected.selection, query.selection)
        assertEquals(expected.selectionArgs.toList(), query.args)
        assertEquals(Constants.EVENT_PROJECTION.toList(), query.projection)
        assertEquals("dtstart DESC", query.sort)
        assertEquals(listOf(slot.toString(), (slot + 2000).toString()), query.uri.pathSegments.takeLast(2))
        assertAllWorkOffMainAndClosed()
    }
    @Test fun invalidArgumentsNeverTouchTheProvider() {
        val reply = read(start = null, end = null)
        await(reply)
        assertEquals("400", reply.single().error)
        assertTrue(provider.queries.isEmpty())
    }
    @Test fun permissionDenialDoesNotQuery() = permission(false)
    @Test fun permissionGrantKeepsTheOriginalIdOnlyFilter() = permission(true)
    private fun permission(granted: Boolean) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            val binding = Proxy.newProxyInstance(ActivityPluginBinding::class.java.classLoader,
                arrayOf(ActivityPluginBinding::class.java)) { _, method, _ ->
                if (method.name == "getActivity") activity else error("Unexpected binding call " + method.name)
            } as ActivityPluginBinding
            val delegate = CalendarDelegate(binding, app)
            val reply = read(delegate = delegate, start = null, end = null, ids = listOf("701"))
            val request = shadowOf(activity).lastRequestedPermission
            assertNotNull(request)
            assertTrue(provider.queries.isEmpty())
            assertTrue(reply.results.isEmpty())
            if (granted) shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            delegate.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                intArrayOf(if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED))
            await(reply)
            if (!granted) {
                assertEquals("401", reply.single().error)
                assertTrue(provider.queries.isEmpty())
            } else {
                assertNull(reply.single().error)
                val expected = calendarInstanceQuery("7", 0L, Long.MAX_VALUE, listOf("701"))
                assertEquals(expected.selectionArgs.toList(), provider.queries.single { it.path == "instances" }.args)
                assertAllWorkOffMainAndClosed()
            }
        } finally { controller.pause().stop().destroy() }
    }

    private data class Result(val value: Any?, val error: String?, val message: String?)
    private inner class Reply : MethodChannel.Result {
        val results = CopyOnWriteArrayList<Result>()
        val threads = CopyOnWriteArrayList<Boolean>()
        override fun success(result: Any?) { threads.add(mainThread()); results.add(Result(result, null, null)) }
        override fun error(code: String, message: String?, details: Any?) {
            threads.add(mainThread()); results.add(Result(null, code, message))
        }
        override fun notImplemented() = error("unimplemented", null, null)
        fun single(): Result {
            assertEquals("Exactly one terminal reply", 1, results.size)
            assertTrue("Replies/cache completion must stay on main", threads.all { it })
            return results.single()
        }
    }
    private fun read(delegate: CalendarDelegate = CalendarDelegate(null, app), calendar: String = "7",
                     start: Long? = slot, end: Long? = slot + 2000, ids: List<String> = emptyList()): Reply =
        Reply().also { replies.add(it); delegate.retrieveEvents(calendar, start, end, ids, it) }
    private fun await(reply: Reply) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (reply.results.isEmpty() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertTrue("No terminal result", reply.results.isNotEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        reply.single()
    }
    private fun assertAllWorkOffMainAndClosed() {
        val wrong = provider.threads.filter { it.second }
        assertTrue("Provider/cursor work on main: $wrong", wrong.isEmpty())
        assertTrue(provider.cursors.all { it.closes == 1 && it.isClosed })
        replies.filter { it.results.isNotEmpty() }.forEach { it.single() }
    }
    private fun observeSerialization(delegate: CalendarDelegate, observe: () -> Unit) {
        val factory = object : TypeAdapterFactory {
            override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
                if (type.rawType != Event::class.java) return null
                val normal = gson.getDelegateAdapter(this, type)
                return object : TypeAdapter<T>() {
                    override fun write(writer: JsonWriter, value: T) { observe(); normal.write(writer, value) }
                    override fun read(reader: JsonReader): T = normal.read(reader)
                }
            }
        }
        // Observation of actual Gson invocation, not a production dispatcher seam.
        CalendarDelegate::class.java.getDeclaredField("_gson").apply { isAccessible = true }
            .set(delegate, GsonBuilder().registerTypeAdapterFactory(factory).create())
    }
    private data class Query(val path: String, val uri: Uri, val projection: List<String>,
                             val selection: String?, val args: List<String>?, val sort: String?)
    private inner class ReadProvider : ContentProvider() {
        val queries = CopyOnWriteArrayList<Query>()
        val threads = CopyOnWriteArrayList<Pair<String, Boolean>>()
        val cursors = CopyOnWriteArrayList<TrackedCursor>()
        var failAt: String? = null
        var nullPath: String? = null
        var emptyPath: String? = null
        var revoke = false
        var gatePath: String? = null
        var gateCalendar = "7"
        @Volatile var failAfterGate = false
        private val gated = AtomicBoolean()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        override fun onCreate() = true
        fun record(point: String) { threads.add(point to mainThread()) }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                           selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            val path = uri.pathSegments.first()
            record("query:$path")
            val calendar = if (path == "calendars") uri.lastPathSegment!!
                else if (path == "instances") selectionArgs!!.first() else "7"
            queries.add(Query(path, uri, projection!!.toList(), selection, selectionArgs?.toList(), sortOrder))
            if (path == gatePath && calendar == gateCalendar && gated.compareAndSet(false, true)) {
                check(!mainThread()) { "Blocking provider query on main: $path" }
                entered.countDown()
                check(resume.await(5, TimeUnit.SECONDS)) { "Test failed to release provider" }
                if (failAfterGate) throw IllegalStateException("held-query-failed")
            }
            if (failAt == "query:$path") throw IllegalStateException(failAt)
            if (revoke && path == "instances") throw SecurityException("calendar permission revoked")
            if (nullPath == path) return null
            val matrix = MatrixCursor(projection)
            if (emptyPath != path) {
                val values: Map<String, Any?> = when (path) {
                    "calendars" -> mapOf("_id" to calendar.toLong(), "calendar_displayName" to "Work",
                        "account_name" to "me@example.com", "ownerAccount" to "me@example.com",
                        "account_type" to "com.google", "calendar_access_level" to 700, "calendar_color" to 0)
                    "instances" -> mapOf("event_id" to 701L, "title" to "ordinary fixture",
                        "begin" to slot, "end" to slot + 3600000, "eventTimezone" to "Europe/London",
                        "eventEndTimezone" to "Europe/London", "original_id" to 700L,
                        "originalInstanceTime" to slot, "_sync_id" to "remote-701",
                        "selfAttendeeStatus" to 4, "rrule" to "FREQ=DAILY;COUNT=3")
                    "attendees" -> mapOf("attendeeName" to "Me", "attendeeEmail" to "me@example.com",
                        "attendeeType" to 1, "attendeeStatus" to 4, "attendeeRelationship" to 2)
                    "reminders" -> mapOf("minutes" to 10, "method" to 1)
                    else -> error("Unexpected provider query: $uri")
                }
                matrix.addRow(projection.map { values[it] }.toTypedArray())
            }
            return TrackedCursor(path, matrix).also { cursors.add(it) }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri = error("Read must not write")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = error("Read must not write")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = error("Read must not write")
    }
    private inner class TrackedCursor(val path: String, cursor: Cursor) : CursorWrapper(cursor) {
        var closes = 0
        private fun reading() {
            provider.record("read:$path")
            if (provider.failAt == "read:$path") throw IllegalStateException(provider.failAt)
        }
        override fun moveToFirst(): Boolean { reading(); return super.moveToFirst() }
        override fun moveToNext(): Boolean { reading(); return super.moveToNext() }
        override fun getString(index: Int): String? { reading(); return super.getString(index) }
        override fun getInt(index: Int): Int { reading(); return super.getInt(index) }
        override fun getLong(index: Int): Long { reading(); return super.getLong(index) }
        override fun close() {
            provider.record("close:$path")
            closes++
            super.close()
            if (provider.failAt == "close:$path") throw IllegalStateException(provider.failAt)
        }
    }
}
