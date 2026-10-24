package com.builttoroam.devicecalendar

import android.content.ContentValues
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.database.Cursor
import android.net.Uri
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.atomic.AtomicReference
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

/** Audit regressions. Production CalendarDelegate/repair and atomic SQLite
 * operations run unchanged. Only the
 * bounded provider metadata/expansion behavior below is modeled from AOSP. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ZeroEndProviderMetadataAuditTest {
    private lateinit var provider: EndMetadataProvider
    private val start = 1788720300000L
    private val hour = 3600000L
    private val day = 86400000L
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun setup() {
        provider = EndMetadataProvider(start).also { it.onCreate() }
        provider.seed(start, 3, false, false)
        provider.db.execSQL("UPDATE Events SET lastDate=dtstart+2*86400000+3600000")
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
    }
    @After fun close() { provider.db.close() }

    @Test fun nullSplitKeepsPrefixDiscoverable() = split(null)
    @Test fun zeroSplitKeepsPrefixDiscoverable() = split(0L)
    private fun split(end: Long?) {
        prepare(end)
        val result = call { d, r -> d.applyEventChanges("7", "21096",
            mapOf("title" to mapOf("expected" to "test-rec", "requested" to "future")),
            mapOf("scope" to "thisAndFollowing", "originalEventId" to "21096",
                "originalOccurrenceStart" to start + day, "selectedOccurrenceWasDetached" to false), r)
        }.asJsonObject
        assertEquals("updated", result["outcome"].asString)
        assertPrefix(listOf(start))
    }

    @Test fun nullFollowingDeleteKeepsPrefixDiscoverable() = deleteFollowing(null)
    @Test fun zeroFollowingDeleteKeepsPrefixDiscoverable() = deleteFollowing(0L)
    @Test fun warmZeroFollowingDeleteKeepsPrefixDiscoverable() {
        provider.retainWindow = true
        deleteFollowing(0L)
    }
    @Test fun warmNullFollowingDeleteKeepsPrefixDiscoverable() {
        provider.retainWindow = true
        deleteFollowing(null)
    }
    @Test fun warmZeroLimitRepairKeepsSeriesDiscoverable() {
        provider.retainWindow = true
        repair(0L, false)
    }
    @Test fun warmZeroSplitKeepsPrefixDiscoverable() {
        provider.retainWindow = true
        split(0L)
    }
    private fun deleteFollowing(end: Long?) {
        prepare(end)
        assertTrue(call { d, r -> d.deleteEvent("7", "21096", r,
            start + day, start + day + hour, true) }.asBoolean)
        assertPrefix(listOf(start))
    }

    @Test fun zeroFollowingDeletePreservesEarlierDetachedExceptionAndUnrelatedRoot() {
        provider.db.execSQL("DELETE FROM Events")
        provider.seed(start, 4, true, true)
        provider.db.execSQL("UPDATE Events SET lastDate=dtstart+3*86400000+3600000")
        provider.db.execSQL("UPDATE Events SET dtend=0 WHERE _id=21096")
        val retained = provider.row(21098)
        val unrelated = provider.row(900)
        assertTrue(call { d, r -> d.deleteEvent("7", "21096", r,
            start + 3 * day, start + 3 * day + hour, true) }.asBoolean)
        assertPrefix(listOf(start, start + day - hour, start + 2 * day))
        assertEquals(retained, provider.row(21098))
        assertEquals(unrelated, provider.row(900))
        assertEquals(1, provider.row(21097)!!.getAsInteger(Events.DELETED))
    }

    @Test fun nullLimitRepairKeepsSeriesDiscoverable() = repair(null, false)
    @Test fun zeroLimitRepairKeepsSeriesDiscoverable() = repair(0L, false)
    @Test fun nullSplitRepairKeepsPrefixDiscoverable() = repair(null, true)
    @Test fun zeroSplitRepairKeepsPrefixDiscoverable() = repair(0L, true)
    private fun repair(end: Long?, split: Boolean) {
        prepare(end)
        val roots = if (split) listOf("21096", "900") else listOf("21096")
        val rows = RecentRecurrenceRepair.read(app.contentResolver, "7", roots)
        assertTrue(RecentRecurrenceRepair.apply(app.contentResolver, "7", roots, rows,
            "21096", "FREQ=DAILY;COUNT=${if (split) 1 else 2}",
            "zero-end-provider-audit", false, if (split) "split-prefix" else "recurrence-limit"))
        assertPrefix(if (split) listOf(start) else listOf(start, start + day))
    }

    @Test fun zeroDeletionRepairKeepsPrefixDiscoverable() = deletionRepair(0L)
    @Test fun nullDeletionRepairKeepsPrefixDiscoverable() = deletionRepair(null)
    private fun deletionRepair(end: Long?) {
        prepare(end)
        val rows = RecentDeletionRepair.read(app.contentResolver, "7", "21096")
        assertTrue(RecentDeletionRepair.apply(app.contentResolver, "7", "21096", "following",
            start + day, rows, rows, "zero-end-deletion-audit"))
        assertPrefix(listOf(start))
    }

    @Test fun prefixNormalizationRequiresKnownNonnegativeNonoverflowingDuration() {
        for (duration in listOf(null, "", "P", "PT", "invalid", "-P3600S", "P9223372036854775807S")) {
            assertThrows(IllegalArgumentException::class.java) {
                recurrencePrefixStorageChanges("FREQ=DAILY;COUNT=1", start, 0L, duration)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            recurrencePrefixStorageChanges("FREQ=DAILY;COUNT=1", Long.MAX_VALUE - 1000, 0L, "P3600S")
        }
        for (duration in listOf("P0S", "P1D", "PT1H", "P3600S")) {
            val values = recurrencePrefixStorageChanges("FREQ=DAILY;COUNT=1", start, 0L, duration)
            assertTrue(values.containsKey(Events.DTEND))
            assertNull(values.get(Events.DTEND))
            assertEquals(setOf(Events.RRULE, Events.DTSTART, Events.DTEND), values.keySet())
        }
        for (end in listOf(null, start + hour)) {
            assertFalse(recurrencePrefixStorageChanges("FREQ=DAILY;COUNT=1", start, end, "P3600S")
                .containsKey(Events.DTEND))
        }
    }

    @Test fun prefixRepairGuardsRawEndAndDurationWithoutPartialMetadataWrites() {
        for (kind in listOf("recurrence-limit", "split-prefix", "deletion")) {
            for (change in listOf("dtend=NULL", "duration='P7200S'")) {
                provider.db.execSQL("UPDATE Events SET dtend=0,duration='P3600S' WHERE _id=21096")
                val roots = if (kind == "split-prefix") listOf("21096", "900") else listOf("21096")
                val rows = if (kind == "deletion") RecentDeletionRepair.read(app.contentResolver, "7", "21096")
                    else RecentRecurrenceRepair.read(app.contentResolver, "7", roots)
                var external: List<ContentValues>? = null
                provider.beforeBatch = {
                    provider.db.execSQL("UPDATE Events SET $change WHERE _id=21096")
                    external = provider.rows()
                }
                val updated = if (kind == "deletion") RecentDeletionRepair.apply(app.contentResolver, "7",
                    "21096", "following", start + day, rows, rows, "zero-cas")
                    else RecentRecurrenceRepair.apply(app.contentResolver, "7", roots, rows, "21096",
                        "FREQ=DAILY;COUNT=1", "zero-cas", false, kind)
                assertFalse(updated)
                assertNotNull(external)
                assertEquals(external, provider.rows())
            }
        }
    }

    @Test fun prefixAndMetadataRollBackWhenALaterBatchOperationFails() {
        prepare(0L)
        val before = provider.rows()
        val operations = arrayListOf(
            ContentProviderOperation.newUpdate(Events.CONTENT_URI)
                .withSelection("_id=? AND dtend=0", arrayOf("21096"))
                .withValues(recurrencePrefixStorageChanges("FREQ=DAILY;COUNT=1", start, 0L, "P3600S"))
                .withExpectedCount(1).build(),
            ContentProviderOperation.newAssertQuery(Events.CONTENT_URI)
                .withSelection("_id=?", arrayOf("missing"))
                .withExpectedCount(1).build())
        assertThrows(android.content.OperationApplicationException::class.java) {
            app.contentResolver.applyBatch(CalendarContract.AUTHORITY, operations)
        }
        assertEquals(before, provider.rows())
        assertEquals(listOf(start, start + day, start + 2 * day), provider.starts(21096))
    }

    @Test fun lostRepairReplyDoesNotUndoCanonicalPrefixOrMembership() {
        prepare(0L)
        val rows = RecentRecurrenceRepair.read(app.contentResolver, "7", listOf("21096"))
        provider.loseReply = true
        assertThrows(android.os.RemoteException::class.java) {
            RecentRecurrenceRepair.apply(app.contentResolver, "7", listOf("21096"), rows,
                "21096", "FREQ=DAILY;COUNT=2", "zero-lost-ack", false, "recurrence-limit")
        }
        assertPrefix(listOf(start, start + day))
    }

    private fun prepare(end: Long?) {
        provider.db.execSQL("UPDATE Events SET dtend=? WHERE _id=21096", arrayOf<Any?>(end))
        assertEquals(listOf(start, start + day, start + 2 * day), provider.starts(21096))
    }
    private fun assertPrefix(expected: List<Long>) {
        val row = provider.row(21096)!!
        assertEquals("Acknowledged write must retain the surviving occurrences; lastDate=" +
            row.getAsLong(Events.LAST_DATE) + ", dtend=" + row.getAsLong(Events.DTEND),
            expected, provider.starts(21096))
        assertEquals(expected.last() + hour, row.getAsLong(Events.LAST_DATE))
        assertNull("Authorized prefix write must use canonical NULL", row.getAsLong(Events.DTEND))
    }
    private fun call(action: (CalendarDelegate, MethodChannel.Result) -> Unit): JsonElement {
        val answer = AtomicReference<Any?>()
        action(CalendarDelegate(null, app), object : MethodChannel.Result {
            override fun success(result: Any?) { answer.set(result ?: AssertionError("Missing reply")) }
            override fun error(code: String, message: String?, details: Any?) { answer.set(AssertionError("$code $message")) }
            override fun notImplemented() { answer.set(AssertionError("Not implemented")) }
        })
        val deadline = System.nanoTime() + 8_000_000_000L
        while (answer.get() == null && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(1)
        }
        val result = answer.get()
        assertNotNull(result)
        if (result is Throwable) throw result
        return if (result is String) JsonParser.parseString(result) else Gson().toJsonTree(result)
    }
}

/** Source-derived fixture, not an installed OEM provider or full emulator.
 * CalendarProvider2.handleUpdateEvents merges the stored row and modified
 * columns for calculateLastDate. calculateLastDate returns ANY nonnull DTEND
 * directly, including zero. CalendarInstancesHelper.getEntries excludes a
 * root whose lastDate precedes the requested range (unless it is an exception).
 * Source: android.googlesource.com/platform/packages/providers/CalendarProvider
 * refs/heads/main, CalendarProvider2.java and CalendarInstancesHelper.java,
 * inspected 14 October 2026. The existing fixture always expands every root and
 * invents one-hour durations, so it cannot expose this metadata omission.
 */
private class EndMetadataProvider(private val windowStart: Long) : TombstoneCalendarProvider() {
    var retainWindow = false
    var loseReply = false
    private var expanded = false
    override fun onCreate(): Boolean {
        super.onCreate()
        db.execSQL("ALTER TABLE Events ADD COLUMN lastDate INTEGER")
        db.execSQL("ALTER TABLE Events ADD COLUMN customAppPackage TEXT")
        db.execSQL("ALTER TABLE Events ADD COLUMN mutators TEXT")
        db.execSQL("ALTER TABLE Attendees ADD COLUMN attendeeIdentity TEXT")
        db.execSQL("ALTER TABLE Attendees ADD COLUMN attendeeIdNamespace TEXT")
        db.execSQL("CREATE TABLE ExtendedProperties (_id INTEGER PRIMARY KEY,event_id INTEGER,name TEXT,value TEXT)")
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        if (uri.pathSegments.first() == "extendedproperties") {
            return db.query("ExtendedProperties", projection, selection, selectionArgs, null, null, sortOrder)
        }
        return super.query(uri, projection, selection, selectionArgs, sortOrder)
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
        if (uri.pathSegments.first() != "events" || values == null ||
            listOf(Events.DTSTART, Events.DTEND, Events.DURATION, Events.RRULE).none(values::containsKey)) {
            return super.update(uri, values, selection, args)
        }
        val selected = super.query(uri, arrayOf(Events._ID), selection, args, null).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }
        // Each metadata-affecting production operation targets one root.
        if (selected.isEmpty()) return 0
        check(selected.size == 1)
        val merged = ContentValues(row(selected.single())!!).apply { putAll(values) }
        val end = merged.getAsLong(Events.DTEND)
        val last = if (end != null) end else {
            check(merged.getAsString(Events.DURATION) == "P3600S")
            val count = Regex("(?:^|;)COUNT=(\\d+)(?:;|$)").find(merged.getAsString(Events.RRULE))!!
                .groupValues[1].toLong()
            merged.getAsLong(Events.DTSTART) + (count - 1) * 86400000L + 3600000L
        }
        val count = super.update(uri, ContentValues(values).apply { put(Events.LAST_DATE, last) }, selection, args)
        if (retainWindow && expanded && values.containsKey(Events.DTSTART)) {
            // AOSP deletes the changed root's cached rows BEFORE checking the
            // merged LAST_DATE. Reading the same window must not repair this.
            val id = selected.single()
            db.delete("Instances", "event_id=?", arrayOf(id.toString()))
            if (last >= windowStart) {
                val root = row(id)!!
                val slots = Regex("(?:^|;)COUNT=(\\d+)(?:;|$)").find(root.getAsString(Events.RRULE))!!
                    .groupValues[1].toInt()
                repeat(slots) { index ->
                    val at = root.getAsLong(Events.DTSTART) + index * 86400000L
                    db.insertOrThrow("Instances", null, ContentValues().apply {
                        put("event_id", id); put("begin", at); put("end", at + 3600000L)
                    })
                }
            }
        }
        return count
    }
    override fun ensureInstances() {
        if (retainWindow && expanded) return
        super.ensureInstances()
        // Restrict generated roots exactly at the LAST_DATE discovery gate.
        // Exceptions are out of scope for this fixture; tests seed none.
        db.execSQL("DELETE FROM Instances WHERE event_id IN " +
            "(SELECT _id FROM Events WHERE originalInstanceTime IS NULL AND lastDate < ?)",
            arrayOf(windowStart))
        expanded = true
    }
    override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
        val result = super.applyBatch(operations)
        if (loseReply) { loseReply = false; throw android.os.RemoteException("injected lost reply") }
        return result
    }
}
