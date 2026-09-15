package com.builttoroam.devicecalendar

import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteQueryBuilder
import android.net.Uri
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.flutter.plugin.common.MethodChannel
import java.time.ZonedDateTime
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicReference
import org.dmfs.rfc5545.recur.RecurrenceRule
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

/**
 * 2026-09-14 18:41 debug capture: root 21096, Tuesday exception 21098,
 * Thursday exception 21097, split suffix 21100. Native saves all return updated,
 * yet Tuesday is absent from Android while calendar-action-14 projects it.
 *
 * The real CalendarDelegate builds and applies every write/assertion/batch.
 * The test provider supplies SQLite plus Android's soft-delete/expansion
 * semantics, not a canned success or an expected missing-Tuesday response.
 * This is a native persistence test; it does not run the Dart optimistic UI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RecurrenceTombstoneRoundTripTest {
    private lateinit var provider: TombstoneCalendarProvider
    private val monday = ZonedDateTime.parse("2026-09-07T19:45:00+01:00[Europe/London]")
    private fun ms(date: ZonedDateTime) = date.toInstant().toEpochMilli()
    private fun range(date: ZonedDateTime) = EventDateRangeValue(
        ms(date), date.zone.id, ms(date.plusHours(1)), date.zone.id, false).toMap()

    @Before fun setup() {
        provider = TombstoneCalendarProvider()
        provider.onCreate()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
    }
    @After fun close() { provider.db.close() }

    @Test fun capturedSplitThenReturnMustKeepTuesdayPersisted() = replay(split = true)
    @Test fun capturedSplitThenReturnWithReversedSqlRowsMustKeepTuesdayPersisted() =
        replay(split = true, reverse = true)
    @Test fun returningToOldTimeAlsoKeepsTuesdayWithoutAnySplit() = replay(split = false)
    @Test fun returningWithoutSplitWithReversedSqlRowsKeepsTuesday() =
        replay(split = false, reverse = true)
    @Test fun controlMovingToAnUnoccupiedOriginalSlotKeepsEveryDay() =
        replay(split = true, destination = monday.plusMinutes(5))
    @Test fun controlNoExceptionAtTuesdayKeepsEveryDayOnReturn() =
        replay(split = true, withExceptions = false)
    @Test fun controlProviderPurgedTombstonesBeforeReturnKeepsEveryDay() =
        replay(split = true, purgeTombstones = true)

    @Test fun fieldsOnlyEditDoesNotRestoreDeletedOccurrences() {
        provider.seed(ms(monday), 2, true, false)
        provider.db.execSQL("UPDATE Events SET deleted=1 WHERE _id=21098")
        val result = call { delegate, reply -> delegate.applyEventChanges("7", "21096",
            mapOf("title" to mapOf("expected" to "test-rec", "requested" to "renamed")),
            target("entireSeries", monday) + ("resetDetachedOverrides" to false), reply) }
        assertEquals("updated", result.asJsonObject["outcome"].asString)
        assertEquals(1, provider.row(21098)!!.getAsInteger(Events.DELETED))
        assertEquals(listOf(ms(monday)), provider.starts(21096))
    }

    @Test fun resetDoesNotGuessMissingTombstoneAllDayIdentity() =
        unmatchedAllDayIdentity(null)
    @Test fun resetDoesNotReuseAnAllDayTombstoneForATimedSlot() =
        unmatchedAllDayIdentity(1)
    private fun unmatchedAllDayIdentity(originalAllDay: Int?) {
        provider.seed(ms(monday), 2, true, false)
        move(monday, monday.plusHours(1))
        provider.db.update("Events", ContentValues().apply {
            if (originalAllDay == null) putNull(Events.ORIGINAL_ALL_DAY)
            else put(Events.ORIGINAL_ALL_DAY, originalAllDay)
        }, "_id=21098", null)
        val before = provider.row(21098)!!
        move(monday.plusHours(1), monday)
        // The timestamp alone cannot authorize reusing an incompatible or
        // incomplete physical identity. Do not manufacture an exception.
        assertEquals(before, provider.row(21098))
        assertEquals(1, provider.rows().count { it.getAsString(Events.ORIGINAL_ID) == "21096" })
    }

    @Test fun resetDoesNotRestoreUnrelatedOrOutOfRuleTombstones() {
        provider.seed(ms(monday), 2, true, true)
        move(monday, monday.plusHours(1))
        val foreign = ContentValues(provider.row(21098)!!).apply {
            put(Events._ID, 21101); put(Events.ORIGINAL_ID, 900)
            put(Events.ORIGINAL_SYNC_ID, "unrelated-master")
        }
        provider.db.insertOrThrow("Events", null, foreign)
        val otherCalendar = ContentValues(provider.row(21098)!!).apply {
            put(Events._ID, 21102); put(Events.CALENDAR_ID, 8)
        }
        provider.db.insertOrThrow("Events", null, otherCalendar)
        move(monday.plusHours(1), monday)
        assertEquals(listOf(ms(monday), ms(monday.plusDays(1))), provider.starts(21096))
        for (id in listOf(21097L, 21101L, 21102L)) {
            assertEquals("Out-of-scope tombstone $id changed", 1, provider.row(id)!!.getAsInteger(Events.DELETED))
        }
    }

    @Test fun resetPrefersExistingLiveExceptionOverDeletedAlias() {
        provider.seed(ms(monday), 2, true, false)
        move(monday, monday.plusHours(1))
        val live = ContentValues(provider.row(21098)!!).apply {
            put(Events._ID, 21101); put(Events.DELETED, 0)
            put(Events._SYNC_ID, "new-exception")
        }
        provider.db.insertOrThrow("Events", null, live)
        move(monday.plusHours(1), monday)
        assertEquals(1, provider.row(21098)!!.getAsInteger(Events.DELETED))
        assertEquals(0, provider.row(21101)!!.getAsInteger(Events.DELETED))
        assertEquals(listOf(ms(monday), ms(monday.plusDays(1))), provider.starts(21096))
    }

    @Test fun ambiguousDeletedAliasesRejectAtomicallyInsteadOfRestoringDuplicates() {
        provider.seed(ms(monday), 2, true, false)
        move(monday, monday.plusHours(1))
        provider.db.insertOrThrow("Events", null, ContentValues(provider.row(21098)!!).apply {
            put(Events._ID, 21101); put(Events._SYNC_ID, "other-exception")
        })
        val result = change(monday.plusHours(1), monday)
        assertEquals("conflict", result.asJsonObject["outcome"].asString)
        assertEquals(ms(monday.plusHours(1)), provider.row(21096)!!.getAsLong(Events.DTSTART))
        assertEquals(1, provider.row(21098)!!.getAsInteger(Events.DELETED))
        assertEquals(1, provider.row(21101)!!.getAsInteger(Events.DELETED))
    }

    @Test fun skippedDeletedAliasBecomingLiveInvalidatesTheResetSelection() {
        provider.seed(ms(monday), 2, true, false)
        move(monday, monday.plusHours(1))
        provider.db.insertOrThrow("Events", null, ContentValues(provider.row(21098)!!).apply {
            put(Events._ID, 21101); put(Events.DELETED, 0)
            put(Events._SYNC_ID, "new-exception")
        })
        val liveBefore = provider.row(21101)!!
        provider.beforeBatch = {
            provider.db.execSQL("UPDATE Events SET deleted=0 WHERE _id=21098")
        }
        val result = change(monday.plusHours(1), monday)
        assertEquals("conflict", result.asJsonObject["outcome"].asString)
        assertEquals(ms(monday.plusHours(1)), provider.row(21096)!!.getAsLong(Events.DTSTART))
        assertEquals(liveBefore, provider.row(21101))
    }

    @Test fun changedTombstoneBetweenReadAndBatchRollsBackMasterAndReset() {
        provider.seed(ms(monday), 2, true, false)
        move(monday, monday.plusHours(1))
        provider.beforeBatch = {
            provider.db.execSQL("UPDATE Events SET originalInstanceTime=originalInstanceTime+60000 WHERE _id=21098")
        }
        val result = change(monday.plusHours(1), monday)
        assertEquals("conflict", result.asJsonObject["outcome"].asString)
        assertEquals(ms(monday.plusHours(1)), provider.row(21096)!!.getAsLong(Events.DTSTART))
        assertEquals(1, provider.row(21098)!!.getAsInteger(Events.DELETED))
    }

    private fun replay(split: Boolean, reverse: Boolean = false,
                       destination: ZonedDateTime = monday,
                       withExceptions: Boolean = true, purgeTombstones: Boolean = false) {
        provider.seed(ms(monday), if (split) 5 else 2, withExceptions, split)
        if (reverse) provider.db.execSQL("PRAGMA reverse_unordered_selects = ON")
        val initial = (0L until if (split) 5L else 2L).map {
            ms(monday.plusDays(it).minusHours(if (withExceptions && it == 1L) 1 else 0))
        }
        assertEquals("Seed is a live moved Tuesday, not a seeded missing instance",
            initial.sorted(), provider.starts(21096))

        // Action 12, 18:41:41: entire series 19:45 -> 20:45, reset overrides.
        move(monday, monday.plusHours(1))
        assertEquals((0L until if (split) 5L else 2L).map { ms(monday.plusDays(it).plusHours(1)) },
            provider.starts(21096))
        if (withExceptions) {
            // Diagnostic fingerprint, not the final regression expectation.
            // Future fixes may retain a live reset row instead of a tombstone.
            val tuesday = provider.row(21098)
            assertNotNull("The normal provider delete is not a physical row purge", tuesday)
            assertEquals(ms(monday.plusDays(1)), tuesday!!.getAsLong(Events.ORIGINAL_INSTANCE_TIME))
            assertEquals("remote-master", tuesday.getAsString(Events.ORIGINAL_SYNC_ID))
        }

        val suffixId = if (split) {
            // Action 13, 18:41:48: Wednesday onwards 20:45 -> 18:45.
            val result = call { delegate, reply -> delegate.applyEventChanges(
                "7", "21096", mapOf("dateRange" to mapOf(
                    "expected" to range(monday.plusDays(2).plusHours(1)),
                    "requested" to range(monday.plusDays(2).minusHours(1)))),
                target("thisAndFollowing", monday.plusDays(2).plusHours(1)), reply) }
            assertEquals(result.toString(), "updated", result.asJsonObject["outcome"].asString)
            val id = provider.rows().single { it.getAsString(Events.RRULE) != null &&
                it.getAsLong(Events._ID) !in listOf(21096L, 900L) }.getAsLong(Events._ID)
            assertEquals((2L..4L).map { ms(monday.plusDays(it).minusHours(1)) }, provider.starts(id))
            assertEquals(listOf(ms(monday.plusHours(1)), ms(monday.plusDays(1).plusHours(1))),
                provider.starts(21096))
            id
        } else null
        if (purgeTombstones) provider.db.delete("Events", "deleted=1", null)

        // Action 14, 18:41:55: only the prefix goes back to the original time.
        move(monday.plusHours(1), destination)
        assertEquals("Master DTSTART alone is not persistence proof for all members",
            ms(destination), provider.row(21096)!!.getAsLong(Events.DTSTART))
        if (suffixId != null) assertEquals("Split suffix must remain unchanged",
            (2L..4L).map { ms(monday.plusDays(it).minusHours(1)) }, provider.starts(suffixId))
        assertEquals("Unrelated same-title/time series must remain untouched",
            listOf(ms(monday.plusDays(1))), provider.starts(900))

        val expected = (0L..1L).map { ms(destination.plusDays(it)) }
        val persisted = provider.starts(21096)
        // Read through the actual native range API too: it must not invent
        // Tuesday to conceal the failure in provider storage/expansion.
        val nativeRead = call { delegate, reply -> delegate.retrieveEvents(
            "7", ms(monday.minusDays(1)), ms(monday.plusDays(7)), emptyList(), reply) }
        val returned = nativeRead.asJsonArray.filter {
            val row = it.asJsonObject
            row["eventId"].asString == "21096" ||
                row["originalEventId"]?.takeUnless { it.isJsonNull }?.asString == "21096"
        }.map { it.asJsonObject["eventStartDate"].asLong }.sorted()
        assertEquals("Range API and raw provider membership must agree", persisted, returned)
        repeat(2) {
            // Fresh expansion, no stale Instances and no sync completion:
            // this loss must not be dismissed as an eventual refresh delay.
            assertEquals(persisted, provider.starts(21096))
        }
        assertEquals("All native saves acknowledged updated, but a retained deleted " +
            "exception must NOT cancel Tuesday when its original slot returns. " +
            "Persisted rows=${provider.rows()}", expected, persisted)
    }

    private fun target(scope: String, original: ZonedDateTime) = mapOf<String, Any?>(
        "scope" to scope, "originalEventId" to "21096",
        "originalOccurrenceStart" to ms(original), "selectedOccurrenceWasDetached" to false,
        "resetDetachedOverrides" to true)
    private fun move(expected: ZonedDateTime, requested: ZonedDateTime) {
        val result = change(expected, requested)
        assertEquals(result.toString(), "updated", result.asJsonObject["outcome"].asString)
    }
    private fun change(expected: ZonedDateTime, requested: ZonedDateTime): JsonElement =
        call { delegate, reply -> delegate.applyEventChanges(
            "7", "21096", mapOf("dateRange" to mapOf(
                "expected" to range(expected), "requested" to range(requested))),
            target("entireSeries", expected), reply) }
    private fun call(invoke: (CalendarDelegate, MethodChannel.Result) -> Unit): JsonElement {
        val response = AtomicReference<Any>()
        invoke(CalendarDelegate(null, RuntimeEnvironment.getApplication()), object : MethodChannel.Result {
            override fun success(value: Any?) { response.set(value ?: "null") }
            override fun error(code: String, message: String?, details: Any?) {
                response.set(AssertionError("Native failure $code: $message ($details)"))
            }
            override fun notImplemented() { response.set(AssertionError("Native method unavailable")) }
        })
        val deadline = System.nanoTime() + 5_000_000_000L
        while (response.get() == null && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        val result = response.get()
        assertNotNull("Native reply did not arrive", result)
        if (result is Throwable) throw result
        return if (result is String) JsonParser.parseString(result) else Gson().toJsonTree(result)
    }
}

/**
 * Bounded Android provider model: SQL executes actual ContentProviderOperations
 * with transactions, selection predicates, expected counts and back references.
 * Only DAILY/COUNT and one-hour timed events are in this fixture's domain.
 *
 * Soft-deleted rows retain ORIGINAL_SYNC_ID/ORIGINAL_INSTANCE_TIME. Expansion
 * removes matching generated members BEFORE filtering deleted exceptions.
 * Source: AOSP CalendarInstancesHelper.performInstanceExpansion, blob
 * 8e2fdc7249b088ada1203433916a2b901c42ca67, loops at source lines 443-511:
 * https://android.googlesource.com/platform/packages/providers/CalendarProvider/+/master/src/com/android/providers/calendar/CalendarInstancesHelper.java
 * The connected Samsung's raw rows/Instances independently confirm this case.
 * This is not a replacement for a full CalendarProvider/cloud-sync emulator.
 */
private class TombstoneCalendarProvider : ContentProvider() {
    lateinit var db: SQLiteDatabase
    var beforeBatch: (() -> Unit)? = null
    override fun onCreate(): Boolean {
        db = SQLiteDatabase.create(null)
        db.execSQL("""CREATE TABLE Events (_id INTEGER PRIMARY KEY, calendar_id INTEGER,
            title TEXT, description TEXT, eventLocation TEXT, customAppUri TEXT,
            dtstart INTEGER, dtend INTEGER, duration TEXT, rrule TEXT, rdate TEXT, exrule TEXT, exdate TEXT,
            eventTimezone TEXT, eventEndTimezone TEXT, allDay INTEGER DEFAULT 0,
            original_id INTEGER, original_sync_id TEXT, originalInstanceTime INTEGER,
            originalAllDay INTEGER, _sync_id TEXT, uid2445 TEXT, deleted INTEGER DEFAULT 0,
            dirty INTEGER DEFAULT 1, eventStatus INTEGER DEFAULT 1, selfAttendeeStatus INTEGER DEFAULT 1,
            availability INTEGER DEFAULT 0, eventColor INTEGER, eventColor_index TEXT,
            organizer TEXT, hasAttendeeData INTEGER DEFAULT 0, guestsCanModify INTEGER DEFAULT 0,
            guestsCanInviteOthers INTEGER DEFAULT 1, guestsCanSeeGuests INTEGER DEFAULT 1,
            accessLevel INTEGER DEFAULT 0)""")
        db.execSQL("""CREATE TABLE Attendees (_id INTEGER PRIMARY KEY, event_id INTEGER,
            attendeeName TEXT, attendeeEmail TEXT, attendeeType INTEGER,
            attendeeRelationship INTEGER, attendeeStatus INTEGER)""")
        db.execSQL("CREATE TABLE Reminders (_id INTEGER PRIMARY KEY, event_id INTEGER, minutes INTEGER, method INTEGER)")
        db.execSQL("CREATE TABLE Instances (_id INTEGER PRIMARY KEY, event_id INTEGER, begin INTEGER, end INTEGER)")
        return true
    }
    fun seed(monday: Long, count: Int, withExceptions: Boolean, withThursday: Boolean) {
        fun event(id: Long, start: Long, rule: String?, original: Long? = null, slot: Long? = null) {
            db.insertOrThrow("Events", null, ContentValues().apply {
                put(Events._ID, id); put(Events.CALENDAR_ID, 7); put(Events.TITLE, "test-rec")
                put(Events.DTSTART, start); put(Events.EVENT_TIMEZONE, "Europe/London")
                put(Events.EVENT_END_TIMEZONE, "Europe/London")
                if (rule == null) put(Events.DTEND, start + 3_600_000) else put(Events.DURATION, "P3600S")
                put(Events.RRULE, rule); put(Events.ORGANIZER, "owner@example.com")
                put(Events._SYNC_ID, if (original != null)
                    "SYNC_ERROR: The request specified attendees that should have been omitted."
                    else if (id == 21096L) "remote-master" else "unrelated-master")
                put(Events.DIRTY, if (original == null) 0 else 1)
                if (original != null) {
                    put(Events.ORIGINAL_ID, original); put(Events.ORIGINAL_SYNC_ID, "remote-master")
                    put(Events.ORIGINAL_INSTANCE_TIME, slot); put(Events.ORIGINAL_ALL_DAY, 0)
                }
            })
        }
        event(21096, monday, "FREQ=DAILY;COUNT=$count;WKST=MO")
        event(900, monday + 86_400_000, "FREQ=DAILY;COUNT=1;WKST=MO")
        if (withExceptions) {
            event(21098, monday + 86_400_000 - 3_600_000, null, 21096, monday + 86_400_000)
            if (withThursday) {
                event(21097, monday + 3 * 86_400_000, null, 21096, monday + 3 * 86_400_000)
                db.execSQL("UPDATE Events SET selfAttendeeStatus=4 WHERE _id=21097")
            }
        }
    }
    fun rows(): List<ContentValues> = db.query("Events", null, null, null, null, null, "_id").use { c ->
        buildList { while (c.moveToNext()) add(ContentValues().also { DatabaseUtils.cursorRowToContentValues(c, it) }) }
    }
    fun row(id: Long) = rows().singleOrNull { it.getAsLong(Events._ID) == id }
    fun starts(id: Long): List<Long> {
        expand()
        return db.rawQuery("SELECT begin FROM Instances INNER JOIN Events ON Events._id=Instances.event_id " +
            "WHERE event_id=? OR original_id=? ORDER BY begin",
            arrayOf(id.toString(), id.toString())).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
    }
    private fun expand() {
        db.delete("Instances", null, null)
        val rows = rows()
        val generated = mutableListOf<ContentValues>()
        val detached = rows.filter { it.getAsString(Events.RRULE) == null &&
            it.getAsString(Events.ORIGINAL_SYNC_ID) != null && it.getAsLong(Events.ORIGINAL_INSTANCE_TIME) != null }
        fun add(event: ContentValues, start: Long) {
            generated.add(ContentValues().apply {
                put("event_id", event.getAsLong(Events._ID)); put("begin", start); put("end", start + 3_600_000)
            })
        }
        for (event in rows) {
            if (event.getAsInteger(Events.DELETED) == 1 || event.getAsInteger(Events.STATUS) == 2) continue
            val rule = event.getAsString(Events.RRULE)
            if (rule == null) continue
            check(rule.contains("FREQ=DAILY") && rule.contains("COUNT=")) { "Unsupported fixture recurrence $rule" }
            val iterator = RecurrenceRule(rule).iterator(event.getAsLong(Events.DTSTART), TimeZone.getTimeZone("Europe/London"))
            while (iterator.hasNext()) {
                val slot = iterator.nextMillis()
                // Do NOT filter deleted exceptions here. AOSP applies their
                // original-slot suppression even though it won't render them.
                if (detached.none { it.getAsLong(Events.CALENDAR_ID) == event.getAsLong(Events.CALENDAR_ID) &&
                    it.getAsString(Events.ORIGINAL_SYNC_ID) == event.getAsString(Events._SYNC_ID) &&
                    it.getAsLong(Events.ORIGINAL_INSTANCE_TIME) == slot }) add(event, slot)
            }
        }
        for (event in rows.filter { it.getAsString(Events.RRULE) == null }) {
            if (event.getAsInteger(Events.DELETED) != 1 && event.getAsInteger(Events.STATUS) != 2) {
                add(event, event.getAsLong(Events.DTSTART))
            }
        }
        generated.forEach { db.insertOrThrow("Instances", null, it) }
    }
    private fun table(uri: Uri) = when (uri.pathSegments.first()) {
        "events" -> "Events"; "attendees" -> "Attendees"; "reminders" -> "Reminders"
        else -> error("Unexpected write $uri")
    }
    private fun selection(uri: Uri, selection: String?): String? {
        val id = uri.pathSegments.getOrNull(1)?.toLongOrNull() ?: return selection
        return "_id=$id" + (selection?.let { " AND ($it)" } ?: "")
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        if (uri.pathSegments.first() == "calendars") return MatrixCursor(projection!!).apply {
            addRow(projection.map<String, Any?> { column -> when (column) {
                "_id" -> 7; "account_name", "ownerAccount" -> "owner@example.com"
                "account_type" -> "com.google"; "calendar_displayName" -> "Test"
                "calendar_access_level" -> 700; "calendar_color" -> 0
                "visible", "sync_events" -> 1
                else -> null
            } }.toTypedArray())
        }
        if (uri.pathSegments.first() == "instances") {
            expand()
            return SQLiteQueryBuilder().apply {
                tables = "Instances INNER JOIN Events ON Events._id=Instances.event_id"
                setProjectionMap(projection!!.associateWith { column -> when (column) {
                    "event_id", "begin", "end" -> "Instances.$column AS $column"
                    else -> "Events.$column AS $column"
                } })
            }.query(db, projection, selection, selectionArgs, null, null, sortOrder)
        }
        return db.query(table(uri), projection, selection(uri, selection), selectionArgs, null, null, sortOrder)
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri =
        ContentUris.withAppendedId(uri, db.insertOrThrow(table(uri), null, values))
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) =
        db.update(table(uri), values, selection(uri, selection), args)
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
        if (table(uri) != "Events" || uri.getQueryParameter("caller_is_syncadapter") == "true") {
            return db.delete(table(uri), selection(uri, selection), args)
        }
        return db.update("Events", ContentValues().apply { put(Events.DELETED, 1); put(Events.DIRTY, 1) },
            selection(uri, selection), args)
    }
    override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
        val before = beforeBatch
        beforeBatch = null
        before?.invoke()
        db.beginTransaction()
        try {
            val results = super.applyBatch(operations)
            db.setTransactionSuccessful()
            return results
        } finally { db.endTransaction() }
    }
    override fun getType(uri: Uri): String? = null
}
