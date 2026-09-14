package com.builttoroam.devicecalendar

import android.content.ContentValues
import android.content.ContentProvider
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteQueryBuilder
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Looper
import com.google.gson.JsonParser
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.atomic.AtomicReference
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real SQLite executes the production predicate. Instances deliberately contains
 * the malformed provider expansion observed on the phone, not a fake normalized list. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CalendarInstanceQueryTest {
    private lateinit var db: SQLiteDatabase
    private val slot = 1789482600000L
    private val day = 86400000L
    private val duration = 1800000L
    private val join = "Instances INNER JOIN Events ON Events._id = Instances.event_id"

    @Before fun setup() {
        db = SQLiteDatabase.create(null)
        db.execSQL("""CREATE TABLE Events (_id INTEGER PRIMARY KEY, calendar_id INTEGER,
            title TEXT, rrule TEXT, rdate TEXT, original_id INTEGER, original_sync_id TEXT,
            originalInstanceTime INTEGER, deleted INTEGER DEFAULT 0,
            eventStatus INTEGER DEFAULT 1, selfAttendeeStatus INTEGER DEFAULT 4,
            _sync_id TEXT, dirty INTEGER DEFAULT 1, allDay INTEGER DEFAULT 0,
            originalAllDay INTEGER)""")
        db.execSQL("CREATE INDEX eventsCalendarIdIndex ON Events(calendar_id)")
        db.execSQL("CREATE TABLE Instances (_id INTEGER PRIMARY KEY, event_id INTEGER, begin INTEGER, end INTEGER)")
        db.execSQL("CREATE UNIQUE INDEX instancesIndex ON Instances(event_id,begin,end)")
    }
    @After fun close() { db.close() }

    private fun event(id: Long, calendar: Long = 84, master: Long? = null,
                      original: Long? = null, recurring: Boolean = false,
                      deleted: Boolean = false, status: Int = 1, response: Int = 4) {
        db.insertOrThrow("Events", null, ContentValues().apply {
            put("_id", id); put("calendar_id", calendar)
            put("title", "Aperture - Sprint Review")
            if (recurring) put("rrule", "FREQ=WEEKLY;INTERVAL=2;BYDAY=TU")
            if (master != null) put("original_id", master)
            if (original != null) put("originalInstanceTime", original)
            put("deleted", if (deleted) 1 else 0)
            put("eventStatus", status); put("selfAttendeeStatus", response)
        })
    }
    private fun instance(id: Long, start: Long = slot, end: Long = start + duration) {
        db.insertOrThrow("Instances", null, ContentValues().apply {
            put("event_id", id); put("begin", start); put("end", end)
        })
    }
    private fun seed(reverse: Boolean = false, moved: Long = slot, status: Int = 1) {
        event(20377, recurring = true); instance(20377)
        event(21050, master = 20377, original = slot, status = status, response = 1)
        // AOSP omits cancelled exceptions from Instances entirely.
        if (status != 2) instance(21050, moved)
        if (reverse) db.execSQL("PRAGMA reverse_unordered_selects = ON")
    }
    private fun rows(calendar: String = "84", start: Long = slot - 3600000,
                     end: Long = slot + 3600000, ids: List<String> = emptyList()): List<Long> {
        val query = calendarInstanceQuery(calendar, start, end, ids)
        return db.rawQuery("SELECT Instances.event_id FROM $join WHERE " +
            "Instances.begin <= ? AND Instances.end >= ? AND (${query.selection}) ORDER BY Instances.event_id",
            arrayOf(end.toString(), start.toString(), *query.selectionArgs)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }
    }

    @Test fun exactPhoneRowsReturnOnlyTheExceptionWithoutCloudIdentity() {
        seed()
        assertEquals(listOf(21050L), rows())
        assertEquals(2L, db.compileStatement("SELECT count(*) FROM Instances").simpleQueryForLong())
        assertEquals(0L, db.compileStatement("SELECT count(*) FROM Events WHERE _sync_id IS NOT NULL").simpleQueryForLong())
    }
    @Test fun rowOrderDoesNotChooseTheWinner() { seed(reverse = true); assertEquals(listOf(21050L), rows()) }
    @Test fun repeatedResponsesNeverReintroduceTheGeneratedOccurrence() {
        seed()
        for (response in listOf(4, 1, 2, 4, 1)) {
            db.execSQL("UPDATE Events SET selfAttendeeStatus=? WHERE _id=21050", arrayOf(response))
            assertEquals(listOf(21050L), rows())
        }
    }
    @Test fun laterSyncMetadataDoesNotChangeMembership() {
        seed()
        assertEquals(listOf(21050L), rows())
        db.execSQL("UPDATE Events SET _sync_id='remote-master', dirty=0 WHERE _id=20377")
        db.execSQL("UPDATE Events SET original_sync_id='remote-master', _sync_id='remote-exception', dirty=0 WHERE _id=21050")
        assertEquals(listOf(21050L), rows())
    }
    @Test fun movedOutExceptionSuppressesTheOldSlotEvenOutsideTheQueryWindow() {
        seed(moved = slot + day)
        assertEquals(emptyList<Long>(), rows())
        assertEquals(listOf(21050L), rows(start = slot + day - 1000, end = slot + day + duration))
    }
    @Test fun cancelledExceptionSuppressesTheSlotWithoutAnInstancesRow() {
        seed(status = 2); assertEquals(emptyList<Long>(), rows())
    }
    @Test fun movedInExceptionDoesNotConsumeTheGeneratedOccurrenceAtItsDestination() {
        seed(moved = slot + 14 * day)
        instance(20377, slot + 14 * day)
        assertEquals(listOf(20377L, 21050L), rows(start = slot + 14 * day - 1000, end = slot + 14 * day + duration))
    }
    @Test fun unrelatedSameTitleTimeAndOtherCalendarsArePreserved() {
        seed(); event(900); instance(900)
        event(901, recurring = true); instance(901)
        event(16173, calendar = 80, master = 16177, original = slot); instance(16173)
        assertEquals(listOf(900L, 901L, 21050L), rows())
        assertEquals(listOf(16173L), rows(calendar = "80"))
    }
    @Test fun calendarMismatchCannotSuppressAnOccurrence() {
        event(20377, recurring = true); instance(20377)
        event(21050, calendar = 80, master = 20377, original = slot); instance(21050)
        assertEquals(listOf(20377L), rows())
    }
    @Test fun missingOrWrongLinksAreNotGuessedFromTitleAndTime() {
        event(20377, recurring = true); instance(20377)
        event(21050, master = 999, original = slot); instance(21050)
        event(21051, master = 20377); instance(21051)
        event(21052, original = slot); instance(21052)
        assertEquals(listOf(20377L, 21050L, 21051L, 21052L), rows())
    }
    @Test fun tombstonedExceptionsDoNotHideRestoredOccurrences() {
        event(20377, recurring = true); instance(20377)
        event(21050, master = 20377, original = slot, deleted = true); instance(21050)
        assertEquals(listOf(20377L), rows())
    }
    @Test fun staleExceptionCannotHideANowStandaloneMaster() {
        event(20377); instance(20377)
        event(21050, master = 20377, original = slot)
        assertEquals(listOf(20377L), rows())
    }
    @Test fun selectingMasterIdStillHonorsItsExceptionButDoesNotBroadenTheIdFilter() {
        seed()
        assertEquals(emptyList<Long>(), rows(ids = listOf("20377")))
        assertEquals(listOf(21050L), rows(ids = listOf("21050")))
    }
    @Test fun overlappingRangeIncludesAnOverriddenLongOccurrenceStartingBeforeTheRange() {
        seed(moved = slot + 3 * day)
        db.execSQL("UPDATE Instances SET end=? WHERE event_id=20377", arrayOf(slot + 2 * day))
        assertEquals(emptyList<Long>(), rows(start = slot + day, end = slot + day + duration))
    }
    @Test fun sqlitePlanBatchesExceptionLookupInsteadOfScanningCalendarPerReturnedRow() {
        seed()
        val q = calendarInstanceQuery("84", slot - day, slot + day, emptyList())
        val plan = db.rawQuery("EXPLAIN QUERY PLAN SELECT Instances.event_id FROM $join WHERE ${q.selection}",
            q.selectionArgs).use { c -> buildList { while (c.moveToNext()) add(c.getString(3)) } }.joinToString("\n")
        assertFalse(plan, plan.contains("CORRELATED", ignoreCase = true))
        assertTrue(plan, plan.contains("LIST SUBQUERY"))
        assertTrue(plan, plan.contains("instancesIndex"))
    }
    @Test fun staleCancelledInstancesRowIsNotDisplayed() {
        seed(status = 2); instance(21050)
        assertEquals(emptyList<Long>(), rows())
    }
    @Test fun rdateMastersAndAllDayOriginalInstantsUseTheSameIdentityRule() {
        seed()
        db.execSQL("UPDATE Events SET rrule=NULL, rdate='20260915T143000Z' WHERE _id=20377")
        assertEquals(listOf(21050L), rows())
        val midnight = 1789430400000L
        db.execSQL("UPDATE Events SET allDay=1")
        db.execSQL("UPDATE Events SET originalAllDay=1 WHERE _id=21050")
        db.execSQL("UPDATE Events SET originalInstanceTime=? WHERE _id=21050", arrayOf(midnight))
        db.execSQL("UPDATE Instances SET begin=?, end=?", arrayOf(midnight, midnight + day))
        assertEquals(listOf(21050L), rows(start = midnight, end = midnight + day))
    }
    @Test fun twoConflictingExceptionsAreNotSilentlyDeduplicated() {
        seed(); event(21051, master = 20377, original = slot, response = 2); instance(21051)
        assertEquals(listOf(21050L, 21051L), rows())
    }
    @Test fun anotherRecurringRowIsNotAcceptedAsADetachedOverride() {
        event(20377, recurring = true); instance(20377)
        event(21050, master = 20377, original = slot, recurring = true); instance(21050)
        assertEquals(listOf(20377L, 21050L), rows())
    }
    @Test fun missingGeneratedRowsAreNotInventedAndIdsRemainBoundParameters() {
        seed(); db.execSQL("DELETE FROM Instances WHERE event_id=20377")
        assertEquals(listOf(21050L), rows())
        assertEquals(emptyList<Long>(), rows(ids = listOf("21050) OR 1=1 --")))
        assertEquals(emptyList<Long>(), rows(calendar = "84 OR 1=1"))
    }

    @Test fun realDelegateReleaseReadAndFreshDelegateReadUseTheNormalizedQuery() = delegateReads(false)
    @Test fun realDelegateDebugReadAndFreshDelegateReadUseTheNormalizedQuery() = delegateReads(true)

    /** Exercise the real native plugin entry point through ContentResolver and
     * SQLite to its JSON MethodChannel result. Only metadata and empty child
     * tables are fixtures; membership is not mocked. No mutation engine exists. */
    private fun delegateReads(debug: Boolean) {
        seed()
        db.execSQL("ALTER TABLE Events ADD COLUMN dtstart INTEGER")
        db.execSQL("UPDATE Events SET dtstart=?", arrayOf(slot))
        val app = RuntimeEnvironment.getApplication()
        app.applicationInfo.flags = if (debug) ApplicationInfo.FLAG_DEBUGGABLE else 0
        val queries = mutableListOf<String>()
        val eventColumns = db.rawQuery("PRAGMA table_info(Events)", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(1)) }
        }
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                               selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
                val path = uri.pathSegments.first()
                synchronized(queries) { queries.add(path) }
                if (path != "instances") {
                    val cursor = MatrixCursor(projection!!)
                    if (path == "calendars") cursor.addRow(projection.map<String, Any?> { column ->
                        when (column) {
                            "_id" -> 84
                            "account_name", "ownerAccount" -> "me@example.com"
                            "account_type" -> "com.google"
                            "calendar_displayName" -> "Work"
                            "calendar_access_level" -> 700
                            "calendar_color" -> 0
                            "visible" -> 1
                            "sync_events" -> 0
                            else -> null
                        }
                    }.toTypedArray())
                    else check(path == "attendees" || path == "reminders") { "Unexpected query $uri" }
                    return cursor
                }
                val builder = SQLiteQueryBuilder().apply {
                    tables = join
                    setProjectionMap(projection!!.associateWith { column ->
                        when {
                            column in setOf("event_id", "begin", "end") -> "Instances.$column AS $column"
                            column in eventColumns -> "Events.$column AS $column"
                            column == "eventTimezone" || column == "eventEndTimezone" -> "'Europe/London' AS $column"
                            else -> "NULL AS $column"
                        }
                    })
                    val start = uri.pathSegments[2].toLong()
                    val end = uri.pathSegments[3].toLong()
                    appendWhere("Instances.begin <= $end AND Instances.end >= $start")
                }
                return builder.query(db, projection, selection, selectionArgs, null, null, sortOrder)
            }
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri = error("Read must not write")
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = error("Read must not write")
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = error("Read must not write")
        }
        ShadowContentResolver.registerProviderInternal("com.android.calendar", provider)
        // A fresh delegate has no in-memory replacement map; both reads must
        // independently decode the same persistent provider relations.
        repeat(2) {
            val result = AtomicReference<String>()
            CalendarDelegate(null, app).retrieveEvents("84", slot - day, slot + day, emptyList(),
                object : MethodChannel.Result {
                    override fun success(value: Any?) { result.set(value as String) }
                    override fun error(code: String, message: String?, details: Any?) { result.set("ERROR $code $message") }
                    override fun notImplemented() { result.set("NOT IMPLEMENTED") }
                })
            val deadline = System.nanoTime() + 5_000_000_000L
            while (result.get() == null && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(1)
            }
            assertNotNull("Native query did not complete", result.get())
            val events = JsonParser.parseString(result.get()).asJsonArray
            assertEquals(1, events.size())
            val exception = events.single().asJsonObject
            assertEquals("21050", exception["eventId"].asString)
            assertEquals("20377", exception["originalEventId"].asString)
            assertEquals(slot, exception["eventOriginalStartDate"].asLong)
        }
        assertEquals(2, queries.count { it == "instances" })
        assertEquals(2, queries.count { it == "calendars" })
        assertEquals(2, queries.count { it == "attendees" })
        assertEquals(2, queries.count { it == "reminders" })
        assertEquals(8, queries.size) // no extra per-master/exception lookups
    }
}
