package com.builttoroam.devicecalendar

import android.content.ContentValues
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import com.builttoroam.devicecalendar.models.Event
import com.builttoroam.devicecalendar.models.RecurrenceRule
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.flutter.plugin.common.MethodChannel
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import org.dmfs.rfc5545.recur.Freq
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

/** Real delegate -> SQLite cursor -> JSON, plus real transactional write guards.
 * The provider fixture does not normalize DTEND. Expected endpoints come from
 * explicit instants, not the production duration resolver or recurrence engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class StoredEventEndContractTest {
    private lateinit var provider: WriteThreadProvider
    private val start = Instant.parse("2026-09-07T18:45:00Z").toEpochMilli()
    private val hour = 3_600_000L
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        provider = WriteThreadProvider().also { it.onCreate() }
        provider.seed(start, 2, true, false)
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
    }
    @After fun close() { provider.release.countDown(); provider.db.close() }

    @Test fun literalZeroMasterEndUsesDurationLikeSqlNullWithoutWriting() {
        for (rawEnd in listOf(null, 0L)) {
            store(rawEnd)
            val before = provider.rows()
            val row = read()
            assertEquals(start, row["eventStartDate"].asLong)
            assertEquals(start + hour, row["eventEndDate"].asLong)
            assertEquals("21096", row["eventId"].asString)
            assertEquals("remote-master", row["syncId"].asString)
            assertEquals("Europe/London", row["eventStartTimeZone"].asString)
            assertEquals("Reading is not a repair write", before, provider.rows())
            assertEquals(0, provider.batches.get())
        }
    }

    @Test fun explicitEndWinsOverConflictingDuration() {
        store(start + 2 * hour)
        assertEquals(start + 2 * hour, read()["eventEndDate"].asLong)
    }

    @Test fun backwardsNonzeroEndIsNotSilentlyRepaired() {
        store(start - hour)
        assertEquals(start - hour, read()["eventEndDate"].asLong)
    }

    @Test fun absentEndAndUnusableDurationRemainUnknown() {
        for (rawEnd in listOf(null, 0L)) {
            for (duration in listOf(null, "", "broken", "P", "PT", "-P3600S",
                "P9223372036854775807S", "PT18446744073709551617H1S")) {
                store(rawEnd, duration)
                val end = read()["eventEndDate"]
                assertTrue("end=$rawEnd duration=$duration must not invent an end: $end",
                    end == null || end.isJsonNull)
            }
        }
    }

    @Test fun explicitlyKnownZeroDurationIsNotConfusedWithMissingDuration() {
        for (rawEnd in listOf(null, 0L)) {
            store(rawEnd, "P0S")
            assertEquals(start, read()["eventEndDate"].asLong)
        }
    }

    @Test fun durationAdditionOverflowRemainsUnknown() {
        store(0L)
        provider.db.execSQL("UPDATE Events SET dtstart=? WHERE _id=21096", arrayOf(Long.MAX_VALUE - 1000))
        val end = read()["eventEndDate"]
        assertTrue(end == null || end.isJsonNull)
    }

    @Test fun timedDurationCrossesSpringAndAutumnDstWithoutChangingElapsedLength() {
        val cases = listOf(
            "2026-03-29T00:30:00Z" to "2026-03-29T01:30:00Z",
            "2026-10-25T00:30:00Z" to "2026-10-25T01:30:00Z"
        )
        for ((from, to) in cases) for (rawEnd in listOf(null, 0L)) {
            store(rawEnd, "PT1H")
            provider.db.execSQL("UPDATE Events SET dtstart=? WHERE _id=21096",
                arrayOf(Instant.parse(from).toEpochMilli()))
            assertEquals(Instant.parse(to).toEpochMilli(), read()["eventEndDate"].asLong)
        }
    }

    @Test fun allDayDurationKeepsUtcExclusiveEndAcrossDstDates() {
        for (rawEnd in listOf(null, 0L)) for (days in 1..2) {
            store(rawEnd, "P${days}D")
            provider.db.execSQL("UPDATE Events SET dtstart=?, allDay=1, eventTimezone='UTC', eventEndTimezone=NULL WHERE _id=21096",
                arrayOf(Instant.parse("2026-10-25T00:00:00Z").toEpochMilli()))
            val row = read()
            assertEquals(Instant.parse("2026-10-${25 + days}T00:00:00Z").toEpochMilli(), row["eventEndDate"].asLong)
            assertTrue(row["eventAllDay"].asBoolean)
        }
    }

    @Test fun zeroStartIsStillARealTimestamp() {
        store(null)
        provider.db.execSQL("UPDATE Events SET dtstart=0 WHERE _id=21096")
        val row = read()
        assertEquals(0L, row["eventStartDate"].asLong)
        assertEquals(hour, row["eventEndDate"].asLong)
    }

    @Test fun materializedExceptionKeepsItsRealEnd() {
        val row = read("21098")
        assertEquals(start + 86_400_000L, row["eventEndDate"].asLong)
        assertEquals("21096", row["originalEventId"].asString)
        assertEquals(start + 86_400_000L, row["eventOriginalStartDate"].asLong)
    }

    @Test fun cancelledExceptionWithNoDurationDoesNotInventAnEnd() {
        provider.db.execSQL("UPDATE Events SET dtend=0, duration=NULL, eventStatus=2 WHERE _id=21098")
        val row = read("21098")
        val end = row["eventEndDate"]
        assertTrue(end == null || end.isJsonNull)
        assertEquals(start + 86_400_000L, row["eventOriginalStartDate"].asLong)
    }

    @Test fun timeEditReadsZeroButWritesSqlNullAndDuration() {
        store(0L)
        val unrelated = provider.row(900)
        assertEquals("updated", move()["outcome"].asString)
        assertEquals(start + hour, provider.row(21096)!!.getAsLong(Events.DTSTART))
        assertNull(provider.row(21096)!!.getAsLong(Events.DTEND))
        assertEquals("P3600S", provider.row(21096)!!.getAsString(Events.DURATION))
        assertEquals(unrelated, provider.row(900))
    }

    @Test fun fieldResetReadsZeroTemplateWithoutRewritingUnrequestedMasterDates() {
        store(0L)
        val result = invoke { delegate, reply -> delegate.applyEventChanges("7", "21096",
            mapOf("title" to mapOf("expected" to "test-rec", "requested" to "changed")),
            target(reset = true), reply) }.asJsonObject
        assertEquals("updated", result["outcome"].asString)
        assertEquals("changed", provider.row(21098)!!.getAsString(Events.TITLE))
        assertEquals(start + 86_400_000L + hour, provider.row(21098)!!.getAsLong(Events.DTEND))
        assertEquals(0L, provider.row(21096)!!.getAsLong(Events.DTEND))
        assertEquals(start, provider.row(21096)!!.getAsLong(Events.DTSTART))
    }

    @Test fun rawZeroToNullRaceStillAbortsTheTransaction() = race("dtend=NULL")
    @Test fun changedDurationStillAbortsTheTransaction() = race("duration='P7200S'")
    private fun race(change: String) {
        store(0L)
        var afterExternal: List<ContentValues>? = null
        provider.beforeBatch = {
            provider.db.execSQL("UPDATE Events SET $change WHERE _id=21096")
            afterExternal = provider.rows()
        }
        val result = move()
        assertNotNull("Must reach the actual guarded batch", afterExternal)
        assertEquals("conflict", result["outcome"].asString)
        assertEquals("No partial master/exception writes", afterExternal, provider.rows())
    }

    @Test fun recurringCreateAndFullUpdateWriteNullNotZero() {
        for (id in listOf(null, "21096")) {
            val event = newEvent().apply {
                eventId = id
                recurrenceRule = RecurrenceRule(Freq.DAILY).apply { count = 2 }
            }
            val result = invoke { delegate, reply -> delegate.createOrUpdateEvent("7", event, reply) }
            val row = provider.row(result.asString.toLong())!!
            assertNull(row.getAsLong(Events.DTEND))
            assertEquals("PT1H", row.getAsString(Events.DURATION))
        }
    }

    @Test fun standaloneCreateWritesItsActualEndNotNull() {
        val result = invoke { delegate, reply -> delegate.createOrUpdateEvent("7", newEvent(), reply) }
        val row = provider.row(result.asString.toLong())!!
        assertEquals(start + hour, row.getAsLong(Events.DTEND))
        assertNull(row.getAsString(Events.DURATION))
    }

    @Test fun followingSplitCanonicalizesPrefixAndSuccessorWithoutLosingTiming() {
        provider.db.execSQL("DELETE FROM Events WHERE _id=21098")
        store(0L)
        val unrelated = provider.row(900)
        val result = invoke { delegate, reply -> delegate.applyEventChanges("7", "21096",
            mapOf("title" to mapOf("expected" to "test-rec", "requested" to "future")),
            target() + mapOf("scope" to "thisAndFollowing", "originalOccurrenceStart" to start + 86_400_000L),
            reply) }.asJsonObject
        assertEquals("updated", result["outcome"].asString)
        val successor = provider.row(result["resultingEventId"].asLong)!!
        assertEquals(start + 86_400_000L, successor.getAsLong(Events.DTSTART))
        assertNull(successor.getAsLong(Events.DTEND))
        assertEquals("PT1H", successor.getAsString(Events.DURATION))
        assertEquals("future", successor.getAsString(Events.TITLE))
        assertNull(provider.row(21096)!!.getAsLong(Events.DTEND))
        assertEquals("P3600S", provider.row(21096)!!.getAsString(Events.DURATION))
        assertEquals(unrelated, provider.row(900))
    }

    @Test fun recoveryReadsZeroWritesNullAndRetainsRawSnapshot() = recovery(false)
    @Test fun recoveryRejectsRawZeroToNullRace() = recovery(true)
    private fun recovery(race: Boolean) {
        provider.db.execSQL("DELETE FROM Events WHERE _id=21098")
        provider.db.execSQL("UPDATE Events SET rrule=NULL, dtend=0, eventEndTimezone=NULL WHERE _id=21096")
        val resolver = app.contentResolver
        val roots = listOf("21096")
        val rows = RecentRecurrenceRepair.read(resolver, "7", roots)
        assertEquals("0", rows.single()[Events.DTEND])
        val unrelated = provider.row(900)
        if (race) provider.beforeBatch = {
            provider.db.execSQL("UPDATE Events SET dtend=NULL WHERE _id=21096")
        }
        assertEquals(!race, RecentRecurrenceRepair.apply(resolver, "7", roots, rows,
            "21096", "FREQ=DAILY;COUNT=2", "zero-end-contract", false, "recurrence-creation"))
        val row = provider.row(21096)!!
        assertNull(row.getAsLong(Events.DTEND))
        assertEquals("P3600S", row.getAsString(Events.DURATION))
        assertEquals(if (race) null else "FREQ=DAILY;COUNT=2", row.getAsString(Events.RRULE))
        assertEquals(unrelated, provider.row(900))
    }

    // Audit regressions: an external writer runs after native planning
    // and before the real atomic batch. Never normalize the fixture for AML.
    @Test fun auditLimitRewriteGuardsRawZeroToNullRace() = auditRace("dtend=NULL") { delegate, reply ->
        delegate.applyEventChanges("7", "21096", limitChange(), target(), reply)
    }

    @Test fun auditLimitRewritePreservesConcurrentDuration() = auditRace("duration='P7200S'") { delegate, reply ->
        delegate.applyEventChanges("7", "21096", limitChange(), target(), reply)
    }

    @Test fun auditLimitResetGuardsRawZeroToNullRace() = auditRace("dtend=NULL") { delegate, reply ->
        delegate.applyEventChanges("7", "21096", limitChange(), target(reset = true), reply)
    }

    @Test fun auditRecurrenceRemovalGuardsRawZeroToNullRace() = auditRace("dtend=NULL") { delegate, reply ->
        delegate.applyEventChanges("7", "21096", limitChange(remove = true), target(), reply)
    }

    @Test fun auditRecurrenceRemovalPreservesConcurrentDuration() = auditRace("duration='P7200S'") { delegate, reply ->
        delegate.applyEventChanges("7", "21096", limitChange(remove = true), target(), reply)
    }

    @Test fun auditGeneratedTimeEditGuardsRawZeroToNullRace() = auditRace("dtend=NULL") { delegate, reply ->
        delegate.applyEventChanges("7", "21096",
            mapOf("dateRange" to mapOf("expected" to range(start), "requested" to range(start + hour))),
            target() + mapOf("scope" to "thisOccurrence", "requireExceptionSyncIdentity" to true), reply)
    }

    @Test fun auditGeneratedTitleEditPreservesConcurrentDuration() = auditRace("duration='P7200S'") { delegate, reply ->
        delegate.applyEventChanges("7", "21096",
            mapOf("title" to mapOf("expected" to "test-rec", "requested" to "new title")),
            target() + mapOf("scope" to "thisOccurrence", "requireExceptionSyncIdentity" to true), reply)
    }

    @Test fun auditLimitWriteWithoutRaceCanonicallyClearsEnd() {
        store(0L)
        val result = invoke { delegate, reply ->
            delegate.applyEventChanges("7", "21096", limitChange(), target(), reply)
        }.asJsonObject
        assertEquals("updated", result["outcome"].asString)
        assertNull(provider.row(21096)!!.getAsLong(Events.DTEND))
        assertEquals("P3600S", provider.row(21096)!!.getAsString(Events.DURATION))
    }

    @Test fun auditGeneratedTitleEditWithoutRaceKeepsKnownDuration() {
        store(0L)
        val result = invoke { delegate, reply -> delegate.applyEventChanges("7", "21096",
            mapOf("title" to mapOf("expected" to "test-rec", "requested" to "new title")),
            target() + mapOf("scope" to "thisOccurrence", "requireExceptionSyncIdentity" to true), reply)
        }.asJsonObject
        assertEquals("updated", result["outcome"].asString)
        val created = provider.row(result["resultingEventId"].asLong)!!
        assertEquals(start + hour, created.getAsLong(Events.DTEND))
        assertEquals(0L, provider.row(21096)!!.getAsLong(Events.DTEND))
    }

    @Test fun copiedTimingGuardsEveryRawColumnForZeroAndNullEnds() {
        for (rawEnd in listOf(0L, null)) for (path in listOf("limit", "remove", "title")) {
            for (change in listOf("dtstart=dtstart+60000", "dtend=dtstart+7200000",
                "duration='P7200S'", "eventTimezone='UTC'", "eventEndTimezone=NULL", "allDay=1",
                if (rawEnd == null) "dtend=0" else "dtend=NULL")) {
                // Reset only the row changed by the synthetic external writer.
                provider.db.execSQL("UPDATE Events SET dtstart=?, eventTimezone='Europe/London', " +
                    "eventEndTimezone='Europe/London', allDay=0 WHERE _id=21096", arrayOf(start))
                auditRace(change, rawEnd) { delegate, reply ->
                    if (path == "title") delegate.applyEventChanges("7", "21096",
                        mapOf("title" to mapOf("expected" to "test-rec", "requested" to "new title")),
                        target() + mapOf("scope" to "thisOccurrence", "requireExceptionSyncIdentity" to true), reply)
                    else delegate.applyEventChanges("7", "21096", limitChange(remove = path == "remove"), target(), reply)
                }
            }
        }
    }

    @Test fun recurrenceRemovalGuardsSelectedDetachedTiming() {
        for (change in listOf("dtstart=dtstart+60000", "dtend=dtend+3600000", "eventTimezone='UTC'")) {
            var external: List<ContentValues>? = null
            provider.beforeBatch = {
                provider.db.execSQL("UPDATE Events SET $change WHERE _id=21098")
                external = provider.rows()
            }
            val result = invoke { delegate, reply -> delegate.applyEventChanges("7", "21098",
                limitChange(remove = true), target() + mapOf("selectedOccurrenceWasDetached" to true,
                    "originalOccurrenceStart" to start + 86400000L), reply) }.asJsonObject
            assertNotNull(external)
            assertEquals("Selected detached timing must be guarded: $result", external, provider.rows())
            assertEquals("conflict", result["outcome"].asString)
        }
    }

    private fun limitChange(remove: Boolean = false): Map<String, Any?> = mapOf(
        "recurrence" to mapOf(
            "expected" to mapOf("rule" to mapOf("freq" to "DAILY", "count" to 2, "wkst" to "MO")),
            "requested" to mapOf("rule" to if (remove) null else mapOf("freq" to "DAILY", "count" to 3, "wkst" to "MO"))))

    private fun auditRace(change: String, rawEnd: Long? = 0L, action: (CalendarDelegate, MethodChannel.Result) -> Unit) {
        store(rawEnd)
        var external: List<ContentValues>? = null
        provider.beforeBatch = {
            provider.db.execSQL("UPDATE Events SET $change WHERE _id=21096")
            external = provider.rows()
        }
        val result = invoke(action).asJsonObject
        assertNotNull("Regression must reach the native transaction", external)
        assertEquals("The exact externally changed rows must survive, reply=$result", external, provider.rows())
        assertEquals("conflict", result["outcome"].asString)
    }

    private fun newEvent() = Event().apply {
        eventTitle = "synthetic end-date regression"
        eventStartDate = start; eventEndDate = start + hour
        eventStartTimeZone = "Europe/London"; eventEndTimeZone = "Europe/London"
    }
    private fun store(end: Long?, duration: String? = "P3600S") {
        provider.db.execSQL("UPDATE Events SET dtend=?, duration=? WHERE _id=21096", arrayOf<Any?>(end, duration))
    }
    private fun read(id: String = "21096"): JsonObject = invoke { delegate, reply ->
        delegate.retrieveMasterEvent("7", id, reply)
    }.asJsonObject
    private fun target(reset: Boolean = false) = mapOf(
        "scope" to "entireSeries", "originalEventId" to "21096",
        "originalOccurrenceStart" to start, "selectedOccurrenceWasDetached" to false,
        "resetDetachedOverrides" to reset)
    private fun range(at: Long) = EventDateRangeValue(at, "Europe/London",
        at + hour, "Europe/London", false).toMap()
    private fun move(): JsonObject = invoke { delegate, reply -> delegate.applyEventChanges("7", "21096",
        mapOf("dateRange" to mapOf("expected" to range(start), "requested" to range(start + hour))),
        target(), reply)
    }.asJsonObject
    private fun invoke(action: (CalendarDelegate, MethodChannel.Result) -> Unit): JsonElement {
        val response = AtomicReference<Any?>()
        action(CalendarDelegate(null, app), object : MethodChannel.Result {
            override fun success(result: Any?) { response.set(result ?: AssertionError("Null native reply")) }
            override fun error(code: String, message: String?, details: Any?) { response.set(AssertionError("$code: $message")) }
            override fun notImplemented() { response.set(AssertionError("Not implemented")) }
        })
        val deadline = System.nanoTime() + 8_000_000_000L
        while (response.get() == null && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        val result = response.get()
        assertNotNull("Native callback did not finish", result)
        if (result is Throwable) throw result
        return if (result is String) JsonParser.parseString(result) else Gson().toJsonTree(result)
    }
}
