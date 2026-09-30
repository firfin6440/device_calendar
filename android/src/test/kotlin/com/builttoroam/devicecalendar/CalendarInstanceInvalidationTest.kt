package com.builttoroam.devicecalendar

import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentValues
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.Looper
import android.os.RemoteException
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodCall
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
import java.time.ZonedDateTime
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/** Production delegate + transactional SQLite, with persistent Instances.
 * Expectations are explicit civil dates, not values returned by our planner.
 * This is a bounded provider-contract model, not a Samsung/cloud emulator. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CalendarInstanceInvalidationTest {
    private lateinit var provider: CachedInstancesProvider
    private val replies = mutableListOf<Reply>()
    private var first = ZonedDateTime.parse("2026-09-07T19:45:00+01:00[Europe/London]")
    private val start get() = first.toInstant().toEpochMilli()
    private fun day(n: Long) = first.plusDays(n).toInstant().toEpochMilli()
    private val resolver get() = RuntimeEnvironment.getApplication().contentResolver
    private fun delegate() = CalendarDelegate(null, RuntimeEnvironment.getApplication())

    @Before fun setup() {
        CalendarSplitDebugOptions.resetSessionForTesting()
        provider = CachedInstancesProvider().also { it.onCreate() }
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        seed()
    }
    private fun seed() {
        provider.seed(start, 7, false, false)
        provider.db.execSQL("INSERT INTO Attendees (event_id,attendeeEmail,attendeeStatus,attendeeRelationship,attendeeType) VALUES (21096,'owner@example.com',1,2,1)")
    }
    @After fun close() {
        replies.forEach { waitFor(it) }
        provider.db.close()
    }
    private class Reply : MethodChannel.Result {
        val done = AtomicBoolean()
        var value: Any? = null
        var error: String? = null
        override fun success(result: Any?) { value = result; done.set(true) }
        override fun error(code: String, message: String?, details: Any?) { error = "$code: $message"; done.set(true) }
        override fun notImplemented() { error = "notImplemented"; done.set(true) }
    }
    private fun waitFor(reply: Reply) {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (!reply.done.get() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        assertTrue("Native completion", reply.done.get())
    }
    private fun split(id: Long, at: Long, rsvp: Boolean = true, expectedStatus: Int = 1): Reply {
        val result = Reply().also { replies.add(it) }
        val scope = mapOf("scope" to "thisAndFollowing", "originalEventId" to "$id",
            "originalOccurrenceStart" to at, "selectedOccurrenceWasDetached" to false)
        if (rsvp) delegate().updateAttendeeStatus("7", "$id", "owner@example.com",
            expectedStatus, if (expectedStatus == 1) 4 else 1, scope, result)
        else delegate().applyEventChanges("7", "$id",
            mapOf("title" to mapOf("expected" to "test-rec", "requested" to "renamed")), scope, result)
        waitFor(result)
        return result
    }
    private fun outcome(reply: Reply): JsonObject {
        assertNull("Unexpected native failure", reply.error)
        return (if (reply.value is String) JsonParser.parseString(reply.value as String)
            else Gson().toJsonTree(reply.value)).asJsonObject
    }
    private fun updated(reply: Reply): Long {
        val json = outcome(reply)
        assertEquals(json.toString(), "updated", json["outcome"].asString)
        return json["resultingEventId"].asString.toLong()
    }
    private fun read(id: Long): List<Long> {
        // A real Instances query, not a helper that regenerates on every read.
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath("1788220800000").appendPath("1794355200000").build()
        return resolver.query(uri, arrayOf("begin"), "event_id=?", arrayOf("$id"), "begin")!!.use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }
    }

    private fun debugOption(enabled: Boolean? = null, persist: Boolean = false): Map<*, *> {
        // Exercise the actual plugin method handler, including a replacement
        // plugin instance as used by the separate/headless Flutter engine.
        val plugin = DeviceCalendarPlugin()
        DeviceCalendarPlugin::class.java.getDeclaredField("context").apply {
            isAccessible = true
            set(plugin, RuntimeEnvironment.getApplication())
        }
        val reply = Reply().also { replies.add(it) }
        plugin.onMethodCall(MethodCall(
            if (enabled == null) "getSplitDtstartOmissionForTesting" else "setSplitDtstartOmissionForTesting",
            enabled?.let { mapOf("enabled" to it, "persistAcrossRestarts" to persist) }), reply)
        waitFor(reply)
        assertNull(reply.error)
        return reply.value as Map<*, *>
    }

    // September 28 device sequence: a freshly split Wednesday/Thursday root
    // has no remote identity when Thursday is edited. Expected dates are civil
    // inputs, not projections returned by the mutation implementation.
    @Test fun unguardedUnsyncedExceptionReproducesMissingWednesday() =
        splitThenEditThursday(synced = false)

    @Test fun syncedSplitThursdayEditKeepsWednesdayInInstances() =
        splitThenEditThursday(synced = true)

    @Test fun guardedUnsyncedExceptionKeepsAllExistingInstances() =
        splitThenEditThursday(synced = false, guarded = true)

    @Test fun guardedSyncedExceptionKeepsWednesday() =
        splitThenEditThursday(synced = true, guarded = true)

    @Test fun keyedColourExceptionUsesOnlyTheNewExceptionRow() {
        provider.db.execSQL("UPDATE Events SET eventColor=-11238163, eventColor_index='9' WHERE _id=21096")
        val before = provider.row(21096)!!
        val unrelated = provider.row(900)!!
        assertEquals((0L..6L).map { day(it) }, read(21096))
        val reply = Reply().also { replies.add(it) }
        delegate().applyEventChanges("7", "21096",
            mapOf("color" to mapOf(
                "expected" to mapOf("color" to -11238163, "colorKey" to "9"),
                "requested" to mapOf("color" to -2350809, "colorKey" to "11"))),
            mapOf("scope" to "thisOccurrence", "originalEventId" to "21096",
                "originalOccurrenceStart" to day(1), "selectedOccurrenceWasDetached" to false,
                "requireExceptionSyncIdentity" to true), reply)
        waitFor(reply)
        val exception = updated(reply)
        val row = provider.row(exception)!!
        assertEquals("11", row.getAsString(Events.EVENT_COLOR_KEY))
        assertEquals(21096L, row.getAsLong(Events.ORIGINAL_ID).toLong())
        assertEquals(day(1), row.getAsLong(Events.ORIGINAL_INSTANCE_TIME).toLong())
        assertEquals(before, provider.row(21096))
        assertEquals(unrelated, provider.row(900))
        assertEquals(1, provider.commits)
        assertEquals(listOf(day(1)), read(exception))
        assertEquals(listOf(0L, 2L, 3L, 4L, 5L, 6L).map { day(it) }, read(21096))
    }

    private fun splitThenEditThursday(synced: Boolean, guarded: Boolean = false) {
        provider.db.execSQL("UPDATE Events SET rrule='FREQ=DAILY;COUNT=4' WHERE _id=21096")
        assertEquals(listOf(day(0), day(1), day(2), day(3)), read(21096))
        val successor = updated(split(21096, day(2)))
        assertNull(provider.row(successor)!!.getAsString(Events._SYNC_ID))
        assertEquals(listOf(day(0), day(1)), read(21096))
        assertEquals(listOf(day(2), day(3)), read(successor))
        if (synced) provider.db.execSQL(
            "UPDATE Events SET _sync_id='remote-successor', dirty=0 WHERE _id=?",
            arrayOf<Any>(successor)
        )
        val before = provider.row(successor)!!
        val unrelated = read(900)
        fun range(at: Long) = mapOf("startDate" to at, "endDate" to at + 3_600_000L,
            "startTimeZone" to "Europe/London", "endTimeZone" to "Europe/London", "allDay" to false)
        val commitsBefore = provider.commits
        val reply = Reply().also { replies.add(it) }
        delegate().applyEventChanges("7", "$successor",
            mapOf("dateRange" to mapOf("expected" to range(day(3)),
                "requested" to range(day(3) - 7_200_000L))),
            mapOf("scope" to "thisOccurrence", "originalEventId" to "$successor",
                "originalOccurrenceStart" to day(3), "selectedOccurrenceWasDetached" to false,
                "requireExceptionSyncIdentity" to guarded), reply)
        waitFor(reply)
        if (guarded && !synced) {
            assertTrue(reply.error.toString(), reply.error?.startsWith("425:") == true)
            assertEquals(commitsBefore, provider.commits)
            assertEquals(listOf(day(2), day(3)), read(successor))
            assertEquals(before, provider.row(successor))
            assertEquals(listOf(day(0), day(1)), read(21096))
            assertEquals(unrelated, read(900))
            return
        }
        val exception = updated(reply)
        assertEquals(successor, provider.row(exception)!!.getAsLong(Events.ORIGINAL_ID).toLong())
        assertEquals(day(3), provider.row(exception)!!.getAsLong(Events.ORIGINAL_INSTANCE_TIME).toLong())
        assertEquals(listOf(day(3) - 7_200_000L), read(exception))
        val after = provider.row(successor)!!
        assertEquals(before.getAsLong(Events.DTSTART), after.getAsLong(Events.DTSTART))
        assertEquals(before.getAsString(Events.RRULE), after.getAsString(Events.RRULE))
        assertEquals(0, after.getAsInteger(Events.DELETED).toInt())
        assertEquals(listOf(day(0), day(1)), read(21096))
        assertEquals(unrelated, read(900))
        // Query twice: already-expanded range reads must not heal the defect.
        val firstRead = read(successor)
        val secondRead = read(successor)
        val expected = if (synced) listOf(day(2)) else emptyList()
        assertEquals("Synced exception preserves Wednesday; unguarded no-sync reproduces the provider defect", expected, firstRead)
        assertEquals(expected, secondRead)
    }

    @Test fun guardedRsvpCannotEraseSiblingsBeforeSync() {
        provider.db.execSQL("UPDATE Events SET _sync_id=NULL WHERE _id=21096")
        val before = read(21096)
        val rows = provider.rows()
        val reply = Reply().also { replies.add(it) }
        delegate().updateAttendeeStatus("7", "21096", "owner@example.com", 1, 4,
            mapOf("scope" to "thisOccurrence", "originalEventId" to "21096",
                "originalOccurrenceStart" to day(3), "selectedOccurrenceWasDetached" to false,
                "requireExceptionSyncIdentity" to true), reply)
        waitFor(reply)
        assertTrue(reply.error.toString(), reply.error?.startsWith("425:") == true)
        assertEquals(0, provider.commits)
        assertEquals(rows, provider.rows())
        assertEquals(before, read(21096))
    }

    @Test fun syncIdentityDisappearingBetweenReadAndBatchCannotInsertException() {
        provider.db.execSQL("UPDATE Events SET _sync_id='remote-master' WHERE _id=21096")
        val before = read(21096)
        provider.beforeBatch = { provider.db.execSQL("UPDATE Events SET _sync_id=NULL WHERE _id=21096") }
        val reply = Reply().also { replies.add(it) }
        delegate().applyEventChanges("7", "21096",
            mapOf("title" to mapOf("expected" to "test-rec", "requested" to "changed")),
            mapOf("scope" to "thisOccurrence", "originalEventId" to "21096",
                "originalOccurrenceStart" to day(3), "selectedOccurrenceWasDetached" to false,
                "requireExceptionSyncIdentity" to true), reply)
        waitFor(reply)
        assertTrue(reply.error.toString(), reply.error?.startsWith("425:") == true)
        assertEquals(0, provider.commits)
        assertEquals(2, provider.rows().size)
        assertEquals(before, read(21096))
        assertEquals("test-rec", provider.row(21096)!!.getAsString(Events.TITLE))
    }

    @Test fun developerOmissionIsOffByDefault() {
        assertEquals(mapOf("enabled" to false, "persistAcrossRestarts" to false), debugOption())
        splitCase(true, true)
    }
    @Test fun developerOmissionIsSharedByReplacementEnginesAndLeavesCachedPrefixStale() {
        val seven = (0L..6L).map(::day)
        read(21096)
        val before = provider.rows()
        assertEquals(true, debugOption(true)["enabled"])
        assertEquals(true, debugOption()["enabled"])
        assertEquals(before, provider.rows())
        val next = updated(split(21096, day(1)))
        assertEquals(1, RecurrenceRule(provider.row(21096)!!.getAsString(Events.RRULE)).count)
        assertEquals(seven, read(21096))
        assertEquals((1L..6L).map(::day), read(next))
        assertEquals(listOf(day(1)), read(900))
        assertEquals(false, debugOption(false)["enabled"])
        // Disabling is not permission to mutate/repair existing calendar rows.
        assertEquals(seven, read(21096))
        val last = updated(split(next, day(2), expectedStatus = 4))
        assertEquals(listOf(day(1)), read(next))
        assertEquals((2L..6L).map(::day), read(last))
    }
    @Test fun developerOmissionDoesNotDisableTimingGuards() {
        debugOption(true)
        conflictingTiming(Events.DTSTART, day(1))
    }
    @Test fun developerOmissionDoesNotDisableRepairInvalidation() {
        debugOption(true)
        repairCase(true)
    }
    @Test fun developerOmissionDoesNotDisableDeletionInvalidation() {
        debugOption(true)
        legacyDeleteFollowingAlsoInvalidatesCachedOccurrences()
    }
    @Test fun sessionOnlyExperimentResetsOnProcessRestart() {
        assertEquals(mapOf("enabled" to true, "persistAcrossRestarts" to false), debugOption(true))
        CalendarSplitDebugOptions.resetSessionForTesting()
        assertEquals(mapOf("enabled" to false, "persistAcrossRestarts" to false), debugOption())
        splitCase(true, true)
    }
    @Test fun explicitPersistenceSurvivesProcessRestart() {
        debugOption(true, persist = true)
        CalendarSplitDebugOptions.resetSessionForTesting()
        assertEquals(mapOf("enabled" to true, "persistAcrossRestarts" to true), debugOption())
        val before = read(21096)
        updated(split(21096, day(1)))
        assertEquals(before, read(21096))
    }
    @Test fun removingPersistenceKeepsCurrentExperimentButRestoresSafeNextStartup() {
        debugOption(true, persist = true)
        assertEquals(mapOf("enabled" to true, "persistAcrossRestarts" to false), debugOption(true))
        CalendarSplitDebugOptions.resetSessionForTesting()
        assertEquals(false, debugOption()["enabled"])
    }
    @Test fun disablingExperimentClearsPersistenceToo() {
        debugOption(true, persist = true)
        assertEquals(mapOf("enabled" to false, "persistAcrossRestarts" to false), debugOption(false))
        CalendarSplitDebugOptions.resetSessionForTesting()
        assertEquals(false, debugOption()["enabled"])
    }

    @Test fun providerContractRruleOnlyStaysStaleAcrossReadsAndUnrelatedInsertion() {
        val seven = (0L..6L).map(::day)
        assertEquals(seven, read(21096))
        resolver.update(Events.CONTENT_URI, ContentValues().apply { put(Events.RRULE, "FREQ=DAILY;COUNT=1") }, "_id=21096", null)
        resolver.insert(Events.CONTENT_URI, ContentValues(provider.row(900)!!).apply { remove(Events._ID) })
        repeat(3) { assertEquals(seven, read(21096)) }
        resolver.update(Events.CONTENT_URI, ContentValues().apply {
            put(Events.RRULE, "FREQ=DAILY;COUNT=1"); put(Events.DTSTART, start)
        }, "_id=21096", null)
        assertEquals(listOf(start), read(21096))
    }
    @Test fun providerContractColdFirstReadDoesNotExposeTheMissingInvalidation() {
        resolver.update(Events.CONTENT_URI, ContentValues().apply { put(Events.RRULE, "FREQ=DAILY;COUNT=1") }, "_id=21096", null)
        assertEquals(listOf(start), read(21096))
    }
    @Test fun warmRsvpSplitImmediatelyReplacesCachedPrefix() = splitCase(true, true)
    @Test fun coldRsvpSplitExpandsCorrectlyOnFirstRead() = splitCase(false, true)
    @Test fun warmTitleSplitImmediatelyReplacesCachedPrefix() = splitCase(true, false)
    @Test fun coldTitleSplitExpandsCorrectlyOnFirstRead() = splitCase(false, false)
    private fun splitCase(warm: Boolean, rsvp: Boolean) {
        if (warm) assertEquals((0L..6L).map(::day), read(21096))
        val unrelated = provider.row(900)
        val next = updated(split(21096, day(1), rsvp))
        assertEquals(listOf(start), read(21096))
        assertEquals((1L..6L).map(::day), read(next))
        assertEquals(unrelated, provider.row(900))
        assertEquals(listOf(day(1)), read(900))
    }
    @Test fun consecutiveTuesdayWednesdaySplitsNeverRetainTuesdayThroughSunday() {
        read(21096)
        val tuesday = updated(split(21096, day(1)))
        val wednesday = updated(split(tuesday, day(2), expectedStatus = 4))
        assertEquals(listOf(start), read(21096))
        assertEquals(listOf(day(1)), read(tuesday))
        assertEquals((2L..6L).map(::day), read(wednesday))
        assertEquals(7, listOf(21096L, tuesday, wednesday).sumOf { read(it).size })
    }
    @Test fun allDaySplitRepeatsTheMasterAnchorNotTheSelectedOccurrence() {
        provider.db.execSQL("DELETE FROM Events")
        provider.db.execSQL("DELETE FROM Attendees")
        first = ZonedDateTime.parse("2026-09-07T00:00:00Z[UTC]")
        seed()
        provider.db.execSQL("UPDATE Events SET allDay=1,eventTimezone='UTC',eventEndTimezone=NULL,duration='P1D' WHERE _id=21096")
        splitCase(true, true)
        assertEquals(start, provider.row(21096)!!.getAsLong(Events.DTSTART))
        assertEquals("P1D", provider.row(21096)!!.getAsString(Events.DURATION))
    }
    @Test fun untilBoundedSplitKeepsItsExistingStorageAndRebuildsPrefix() {
        provider.db.execSQL("UPDATE Events SET rrule='FREQ=DAILY;UNTIL=20260913T184500Z' WHERE _id=21096")
        splitCase(true, true)
    }
    @Test fun dstCrossingSplitUsesOriginalZonedAnchor() {
        provider.db.execSQL("DELETE FROM Events")
        provider.db.execSQL("DELETE FROM Attendees")
        first = ZonedDateTime.parse("2026-10-23T19:45:00+01:00[Europe/London]")
        seed()
        read(21096)
        val next = updated(split(21096, day(3)))
        assertEquals((0L..2L).map(::day), read(21096))
        assertEquals((3L..6L).map(::day), read(next))
    }
    @Test fun warmSplitPrefixRepairImmediatelyRebuildsOccurrences() = repairCase(true)
    @Test fun coldSplitPrefixRepairExpandsCorrectly() = repairCase(false)
    private fun repairCase(warm: Boolean) {
        if (warm) read(21096)
        val roots = listOf("21096", "900")
        val rows = RecentRecurrenceRepair.read(resolver, "7", roots)
        assertTrue(RecentRecurrenceRepair.apply(resolver, "7", roots, rows, "21096", "FREQ=DAILY;COUNT=1"))
        assertEquals(listOf(start), read(21096))
        assertEquals(listOf(day(1)), read(900))
    }
    @Test fun repairRejectsAnExternalStartChangeWithoutTouchingCachedOccurrences() {
        val before = read(21096)
        val roots = listOf("21096", "900")
        val rows = RecentRecurrenceRepair.read(resolver, "7", roots)
        provider.beforeBatch = {
            provider.db.execSQL("UPDATE Events SET dtstart=? WHERE _id=21096", arrayOf(day(1)))
        }
        assertFalse(RecentRecurrenceRepair.apply(resolver, "7", roots, rows, "21096", "FREQ=DAILY;COUNT=1"))
        assertEquals(day(1), provider.row(21096)!!.getAsLong(Events.DTSTART))
        assertEquals(before, read(21096))
    }
    @Test fun legacyDeleteFollowingAlsoInvalidatesCachedOccurrences() {
        provider.db.execSQL("UPDATE Events SET lastDate=? WHERE _id=21096", arrayOf(day(6) + 3600000))
        read(21096)
        val result = Reply().also { replies.add(it) }
        delegate().deleteEvent("7", "21096", result, day(2), day(2) + 3600000, true)
        waitFor(result)
        assertNull(result.error)
        assertEquals(true, result.value)
        assertEquals((0L..1L).map(::day), read(21096))
        assertEquals(listOf(day(1)), read(900))
    }
    @Test fun externalStartChangeCannotBeOverwrittenByRepeatedDtstart() = conflictingTiming(Events.DTSTART, day(1))
    @Test fun externalTimezoneChangeRejectsSplitAtCommit() = conflictingTiming(Events.EVENT_TIMEZONE, "UTC")
    @Test fun externalDurationChangeRejectsSplitAtCommit() = conflictingTiming(Events.DURATION, "P7200S")
    @Test fun externalAllDayChangeRejectsSplitAtCommit() = conflictingTiming(Events.ALL_DAY, 1)
    private fun conflictingTiming(column: String, value: Any) {
        read(21096)
        var afterExternal: List<ContentValues>? = null
        provider.beforeBatch = {
            provider.db.execSQL("UPDATE Events SET $column=? WHERE _id=21096", arrayOf(value))
            afterExternal = provider.rows()
        }
        assertEquals("conflict", outcome(split(21096, day(1)))["outcome"].asString)
        assertEquals("The batch must not overwrite newer data or insert a successor", afterExternal, provider.rows())
    }
    @Test fun rejectedSuccessorInsertRollsBackPrefixAndCachedInstances() {
        val before = read(21096)
        provider.db.execSQL("CREATE TRIGGER reject_successor BEFORE INSERT ON Events BEGIN SELECT RAISE(ABORT, 'injected successor failure'); END")
        val result = split(21096, day(1))
        assertTrue(result.error != null || outcome(result)["outcome"].asString != "updated")
        assertEquals("FREQ=DAILY;COUNT=7;WKST=MO", provider.row(21096)!!.getAsString(Events.RRULE))
        assertEquals(before, read(21096))
        assertEquals(2, provider.rows().size)
    }
    @Test fun lostReplyAfterCommitDoesNotRequireAppLifetimeOrAnotherWrite() {
        read(21096)
        provider.loseReply = true
        val result = split(21096, day(1))
        assertNotNull(result.error)
        val successor = provider.rows().single { it.getAsLong(Events._ID) !in listOf(21096L, 900L) }.getAsLong(Events._ID)
        assertEquals(listOf(start), read(21096))
        assertEquals((1L..6L).map(::day), read(successor))
        assertEquals(1, provider.commits)
    }
}

/** Models the crucial native contract independently of the write planner:
 * CalendarInstancesHelper.updateInstancesLocked gets MODIFIED ContentValues,
 * returns on missing DTSTART, otherwise rebuilds only the affected family.
 * Source: AOSP blob 8e2fdc7249b088ada1203433916a2b901c42ca67 and the connected
 * Samsung provider (2026-09-26), a.f.i offsets 0x18..0x3c. Cached range reads
 * never expand again; a successor insertion cannot repair a stale predecessor.
 * Bounded September–early-November 2026, timed/all-day, COUNT/UNTIL fixture. */
internal class CachedInstancesProvider : TombstoneCalendarProvider() {
    private var expanded = false
    var loseReply = false
    var commits = 0
    override fun onCreate(): Boolean {
        super.onCreate()
        db.execSQL("ALTER TABLE Events ADD COLUMN lastDate INTEGER")
        db.execSQL("ALTER TABLE Events ADD COLUMN visible INTEGER DEFAULT 1")
        return true
    }
    override fun ensureInstances() {
        if (!expanded) { refresh(null); expanded = true }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val bounds = uri.pathSegments.takeIf { it.first() == "instances" }?.takeLast(2)
        val from = bounds?.get(0)?.toLongOrNull()
        val to = bounds?.get(1)?.toLongOrNull()
        val bounded = if (from != null && to != null)
            (selection?.let { "($it) AND " } ?: "") + "begin <= $to AND end >= $from" else selection
        return super.query(uri, projection, bounded, selectionArgs, sortOrder)
    }
    private fun family(event: ContentValues): Set<Long> {
        val all = rows()
        val calendar = event.getAsLong(Events.CALENDAR_ID)
        val root = event.getAsLong(Events.ORIGINAL_ID)
            ?: all.singleOrNull { it.getAsLong(Events.CALENDAR_ID) == calendar &&
                event.getAsString(Events.ORIGINAL_SYNC_ID) != null &&
                it.getAsString(Events._SYNC_ID) == event.getAsString(Events.ORIGINAL_SYNC_ID) }?.getAsLong(Events._ID)
            ?: event.getAsLong(Events._ID)
        val sync = all.singleOrNull { it.getAsLong(Events._ID) == root }?.getAsString(Events._SYNC_ID)
        return all.filter { it.getAsLong(Events.CALENDAR_ID) == calendar &&
            (it.getAsLong(Events._ID) == root || it.getAsLong(Events.ORIGINAL_ID) == root ||
                (sync != null && it.getAsString(Events.ORIGINAL_SYNC_ID) == sync)) }
            .map { it.getAsLong(Events._ID) }.toSet() + event.getAsLong(Events._ID)
    }
    private fun refresh(ids: Set<Long>?, expansionIds: Set<Long>? = ids) {
        if (ids == null) db.delete("Instances", null, null)
        else ids.forEach { db.delete("Instances", "event_id=?", arrayOf("$it")) }
        val all = rows()
        val exceptions = all.filter { it.getAsLong(Events.ORIGINAL_INSTANCE_TIME) != null }
        for (row in all) {
            val id = row.getAsLong(Events._ID)
            if (expansionIds != null && id !in expansionIds || row.getAsInteger(Events.DELETED) == 1 || row.getAsInteger(Events.STATUS) == 2) continue
            val start = row.getAsLong(Events.DTSTART)
            val duration = row.getAsLong(Events.DTEND)?.minus(start)
                ?: parseDurationMillis(row.getAsString(Events.DURATION))!!
            val rule = row.getAsString(Events.RRULE)
            fun add(at: Long) {
                if (at < 1788220800000L || at > 1794355200000L) return
                if (rule != null && exceptions.any {
                    it.getAsLong(Events.CALENDAR_ID) == row.getAsLong(Events.CALENDAR_ID) &&
                    (it.getAsLong(Events.ORIGINAL_ID) == id ||
                        (row.getAsString(Events._SYNC_ID) != null && it.getAsString(Events.ORIGINAL_SYNC_ID) == row.getAsString(Events._SYNC_ID))) &&
                    it.getAsLong(Events.ORIGINAL_INSTANCE_TIME) == at }) return
                db.insertOrThrow("Instances", null, ContentValues().apply {
                    put("event_id", id); put("begin", at); put("end", at + duration)
                })
            }
            if (rule == null) add(start) else {
                val iterator = RecurrenceRule(rule).iterator(start, TimeZone.getTimeZone(row.getAsString(Events.EVENT_TIMEZONE)))
                var budget = 1000
                while (iterator.hasNext()) {
                    check(budget-- > 0) { "Unbounded fixture recurrence" }
                    val at = iterator.nextMillis()
                    if (at > 1794355200000L) break
                    add(at)
                }
            }
        }
    }
    private fun selected(uri: Uri, selection: String?, args: Array<out String>?): List<ContentValues> =
        super.query(uri, null, selection, args, null).use { c ->
            buildList { while (c.moveToNext()) add(ContentValues().also { android.database.DatabaseUtils.cursorRowToContentValues(c, it) }) }
        }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
        val events = uri.pathSegments.first() == "events"
        val before = if (events && expanded) selected(uri, selection, args) else emptyList()
        val count = super.update(uri, values, selection, args)
        if (expanded && events && values?.getAsLong(Events.DTSTART) != null) {
            check(values.getAsString(Events.RRULE) != null) { "This fixture's incremental update contract is recurrence-only" }
            refresh(before.flatMap { family(it) }.toSet())
        }
        return count
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri {
        if (uri.pathSegments.dropLast(1) == Events.CONTENT_EXCEPTION_URI.pathSegments) {
            val masterId = uri.lastPathSegment!!.toLong()
            val master = row(masterId)!!
            val exception = ContentValues(master).apply {
                remove(Events._ID)
                remove(Events._SYNC_ID)
                remove(Events.RRULE)
                remove(Events.RDATE)
                remove(Events.EXRULE)
                remove(Events.EXDATE)
                remove(Events.DURATION)
                putAll(values!!)
                put(Events.ORIGINAL_ID, masterId)
                put(Events.ORIGINAL_SYNC_ID, master.getAsString(Events._SYNC_ID))
                put(Events.ORIGINAL_ALL_DAY, master.getAsInteger(Events.ALL_DAY))
                put(Events.DIRTY, 1)
            }
            val inserted = super.insert(Events.CONTENT_URI, exception)
            if (expanded) {
                val changedId = ContentUris.parseId(inserted)
                val familyIds = family(row(changedId)!!)
                // Installed Samsung CalendarProvider a.f.i: offsets 0xef..0x10d
                // delete by original_id when both sync identities are absent;
                // 0x13b..0x14d then expand _id=changedId, NOT the whole family.
                // The sync-ID branch expands the family. Keep these two sets
                // separate; refreshing the family unconditionally hides the bug.
                refresh(familyIds, if (master.getAsString(Events._SYNC_ID) == null)
                    setOf(changedId) else familyIds)
            }
            return inserted
        }
        val inserted = super.insert(uri, values)
        if (expanded && uri.pathSegments.first() == "events") refresh(family(row(ContentUris.parseId(inserted))!!))
        return inserted
    }
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
        val before = if (expanded && uri.pathSegments.first() == "events") selected(uri, selection, args) else emptyList()
        val ids = before.flatMap { family(it) }.toSet()
        val count = super.delete(uri, selection, args)
        if (expanded && ids.isNotEmpty()) refresh(ids)
        return count
    }
    override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
        val result = super.applyBatch(operations)
        commits++
        if (loseReply) { loseReply = false; throw RemoteException("injected reply loss after commit") }
        return result
    }
}
