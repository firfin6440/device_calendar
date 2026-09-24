package com.builttoroam.devicecalendar

import android.os.Looper
import android.provider.CalendarContract
import com.google.gson.Gson
import com.google.gson.JsonParser
import io.flutter.plugin.common.MethodChannel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowContentResolver
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean

/** Regression contract: actual delegate and transactional SQLite provider.
 * Only the external writer's scheduling is controlled. No canned outcomes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CalendarRecurrenceGuardTest {
    @Test fun oneOccurrenceTitleSurvivesNormalization() = edit("occurrence", false)
    @Test fun oneOccurrenceTitleRejectsChangedDefinition() = edit("occurrence", true)
    @Test fun followingTitleSurvivesNormalization() = edit("following", false)
    @Test fun followingTitleRejectsChangedDefinition() = edit("following", true)
    @Test fun wholeSeriesTimeSurvivesNormalization() = edit("all-time", false)
    @Test fun wholeSeriesTimeRejectsChangedDefinition() = edit("all-time", true)
    @Test fun wholeSeriesResetCopiesThroughNormalizedTemplate() = edit("all-reset-title", false)
    @Test fun wholeSeriesResetRejectsChangedDefinition() = edit("all-reset-title", true)
    @Test fun removeRecurrenceSurvivesNormalization() = edit("remove", false)
    @Test fun removeRecurrenceRejectsChangedDefinition() = edit("remove", true)
    @Test fun extendRecurrenceSurvivesNormalization() = edit("extend", false)
    @Test fun extendRecurrenceRejectsChangedDefinition() = edit("extend", true)
    @Test fun extensionAlreadyPerformedExternallyIsNotReplayed() = edit("extend", true, true)
    @Test fun normalizationRemovingDefaultAlsoSucceeds() =
        split(true, "FREQ=DAILY;COUNT=7", "updated", "FREQ=DAILY;COUNT=7;WKST=MO")
    @Test fun normalizationPartOrderAndIntervalAlsoSucceeds() =
        split(true, "INTERVAL=1;COUNT=7;WKST=MO;FREQ=DAILY", "updated")
    @Test fun nonDefaultWeekStartRemainsAConflict() =
        split(true, "FREQ=DAILY;COUNT=7;WKST=SU", "conflict")
    @Test fun normalizedBeforeReadSucceeds() = split(false, "FREQ=DAILY;COUNT=7;WKST=MO", "updated")
    @Test fun normalizationBetweenReadAndBatchMustRecover() = split(true, "FREQ=DAILY;COUNT=7;WKST=MO", "updated")
    @Test fun genuineDefinitionChangeMustNotBeOverwritten() = split(true, "FREQ=DAILY;COUNT=8;WKST=MO", "conflict")

    private fun split(duringBatch: Boolean, rewritten: String, expected: String,
                      initial: String = "FREQ=DAILY;COUNT=7") {
        val provider = WriteThreadProvider()
        provider.onCreate()
        val start = ZonedDateTime.parse("2026-09-07T19:45:00+01:00[Europe/London]").toInstant().toEpochMilli()
        provider.seed(start, 7, false, false)
        provider.db.execSQL("UPDATE Events SET rrule=? WHERE _id=21096", arrayOf(initial))
        provider.db.execSQL("INSERT INTO Attendees (event_id, attendeeEmail, attendeeStatus, attendeeRelationship, attendeeType) VALUES (21096, 'owner@example.com', 2, 2, 1)")
        val externalWrite = { provider.db.execSQL("UPDATE Events SET rrule=? WHERE _id=21096", arrayOf(rewritten)) }
        if (duringBatch) provider.beforeBatch = externalWrite else externalWrite()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val done = AtomicBoolean(false)
        var response: Any? = null
        var failure: String? = null
        val delegate = CalendarDelegate(null, RuntimeEnvironment.getApplication())
        delegate.updateAttendeeStatus("7", "21096", "owner@example.com", 2, 1,
            mapOf("scope" to "thisAndFollowing", "originalEventId" to "21096",
                "originalOccurrenceStart" to start + 86400000,
                "selectedOccurrenceWasDetached" to false), object : MethodChannel.Result {
                override fun success(result: Any?) { response = result; done.set(true) }
                override fun error(code: String, message: String?, details: Any?) { failure = "$code: $message"; done.set(true) }
                override fun notImplemented() { failure = "notImplemented"; done.set(true) }
            })
        try {
            val deadline = System.nanoTime() + 8_000_000_000L
            while (!done.get() && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(1)
            }
            assertTrue("Native callback did not complete", done.get())
            assertNull(failure)
            val result = (if (response is String) JsonParser.parseString(response as String)
                else Gson().toJsonTree(response)).asJsonObject
            assertEquals("duringBatch=$duringBatch rule=$rewritten result=$result",
                expected, result["outcome"].asString)
            if (expected == "updated") {
                val following = result["resultingEventId"].asString.toLong()
                assertEquals(listOf(start), provider.starts(21096))
                assertEquals((1L..6L).map { start + it * 86400000 }, provider.starts(following))
            } else {
                assertEquals(rewritten, provider.row(21096)!!.getAsString(CalendarContract.Events.RRULE))
                assertEquals(2, provider.rows().size) // source + unrelated fixture event
            }
        } finally {
            if (done.get()) provider.db.close()
        }
    }

    private fun edit(path: String, conflicting: Boolean, alreadyRequested: Boolean = false) {
        val provider = WriteThreadProvider().also { it.onCreate() }
        val start = ZonedDateTime.parse("2026-09-07T19:45:00+01:00[Europe/London]").toInstant().toEpochMilli()
        provider.seed(start, 7, path.startsWith("all-"), false)
        provider.db.execSQL("UPDATE Events SET rrule='FREQ=DAILY;COUNT=7' WHERE _id=21096")
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val unrelated = provider.row(900)
        var afterExternal: List<android.content.ContentValues>? = null
        provider.beforeBatch = {
            val count = if (!conflicting) 7 else if (path == "extend" && !alreadyRequested) 9 else 8
            provider.db.execSQL("UPDATE Events SET rrule=? WHERE _id=21096",
                arrayOf("INTERVAL=1;WKST=MO;COUNT=$count;FREQ=DAILY"))
            afterExternal = provider.rows()
        }
        fun range(at: Long) = EventDateRangeValue(at, "Europe/London",
            at + 3600000, "Europe/London", false).toMap()
        val changes = when (path) {
            "all-time" -> mapOf("dateRange" to mapOf("expected" to range(start),
                "requested" to range(start + 3600000)))
            "remove", "extend" -> mapOf("recurrence" to mapOf(
                "expected" to mapOf("rule" to mapOf("freq" to "DAILY", "count" to 7)),
                "requested" to mapOf("rule" to if (path == "remove") null
                    else mapOf("freq" to "DAILY", "count" to 8))))
            else -> mapOf("title" to mapOf("expected" to "test-rec", "requested" to "changed"))
        }
        val scope = when (path) {
            "occurrence" -> "thisOccurrence"
            "following" -> "thisAndFollowing"
            else -> "entireSeries"
        }
        val done = AtomicBoolean(false)
        var response: Any? = null
        var failure: String? = null
        CalendarDelegate(null, RuntimeEnvironment.getApplication()).applyEventChanges(
            "7", "21096", changes, mapOf(
                "scope" to scope, "originalEventId" to "21096",
                "originalOccurrenceStart" to if (path in listOf("occurrence", "following")) start + 86400000 else start,
                "selectedOccurrenceWasDetached" to false, "resetDetachedOverrides" to true),
            object : MethodChannel.Result {
                override fun success(result: Any?) { response = result; done.set(true) }
                override fun error(code: String, message: String?, details: Any?) { failure = "$code: $message"; done.set(true) }
                override fun notImplemented() { failure = "notImplemented"; done.set(true) }
            })
        try {
            val deadline = System.nanoTime() + 8_000_000_000L
            while (!done.get() && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(1)
            }
            assertTrue("callback $path", done.get())
            assertNull(failure)
            assertNotNull("Must exercise actual applyBatch, not a preflight-only result", afterExternal)
            val result = (if (response is String) JsonParser.parseString(response as String)
                else Gson().toJsonTree(response)).asJsonObject
            val outcome = if (alreadyRequested) "alreadyCurrent" else if (conflicting) "conflict" else "updated"
            assertEquals("$path: $result", outcome, result["outcome"].asString)
            assertEquals("Unrelated root", unrelated, provider.row(900))
            if (conflicting) {
                assertEquals("Rollback must preserve every source/exception row", afterExternal, provider.rows())
            } else {
                when (path) {
                    "following" -> {
                        val id = result["resultingEventId"].asString.toLong()
                        assertEquals(listOf(start), provider.starts(21096))
                        assertEquals((1L..6L).map { start + it * 86400000 }, provider.starts(id))
                        assertEquals("changed", provider.row(id)!!.getAsString(CalendarContract.Events.TITLE))
                    }
                    "remove" -> {
                        val id = result["resultingEventId"].asString.toLong()
                        assertEquals(listOf(start), provider.starts(id))
                        assertTrue(provider.starts(21096).isEmpty())
                        assertNull(provider.row(id)!!.getAsString(CalendarContract.Events.RRULE))
                    }
                    else -> {
                        val count = if (path == "extend") 8L else 7L
                        val offset = if (path == "all-time") 3600000 else 0
                        assertEquals((0L until count).map { start + it * 86400000 + offset }, provider.starts(21096))
                        if (path == "occurrence") {
                            assertEquals("test-rec", provider.row(21096)!!.getAsString(CalendarContract.Events.TITLE))
                            assertEquals(1, provider.rows().count {
                                it.getAsString(CalendarContract.Events.TITLE) == "changed" })
                        }
                        if (path == "all-reset-title") {
                            assertEquals("changed", provider.row(21096)!!.getAsString(CalendarContract.Events.TITLE))
                            assertEquals("changed", provider.row(21098)!!.getAsString(CalendarContract.Events.TITLE))
                        }
                    }
                }
            }
        } finally { if (done.get()) provider.db.close() }
    }

    @Test fun repeatedFollowingSplitsSurviveNormalizationAtEveryTransaction() {
        val provider = WriteThreadProvider().also { it.onCreate() }
        val start = ZonedDateTime.parse("2026-09-07T19:45:00+01:00[Europe/London]").toInstant().toEpochMilli()
        provider.seed(start, 7, false, false)
        provider.db.execSQL("INSERT INTO Attendees (event_id, attendeeEmail, attendeeStatus, attendeeRelationship, attendeeType) VALUES (21096, 'owner@example.com', 2, 2, 1)")
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val unrelated = provider.row(900)
        val committedRoots = mutableListOf<Long>()
        var root = 21096L
        var status = 2
        try {
            for (day in 1L..6L) {
                val source = root
                val raw = "FREQ=DAILY;COUNT=${8 - day}"
                provider.db.execSQL("UPDATE Events SET rrule=? WHERE _id=?", arrayOf<Any>(raw, source))
                provider.beforeBatch = {
                    provider.db.execSQL("UPDATE Events SET rrule=? WHERE _id=?",
                        arrayOf<Any>("$raw;WKST=MO;INTERVAL=1", source))
                }
                val nextStatus = if (status == 2) 1 else 2
                val result = awaitNative { reply ->
                    // Fresh delegates prevent an in-memory parser/cache from
                    // concealing missing persisted identities or children.
                    CalendarDelegate(null, RuntimeEnvironment.getApplication()).updateAttendeeStatus(
                        "7", source.toString(), "owner@example.com", status, nextStatus,
                        mapOf("scope" to "thisAndFollowing", "originalEventId" to source.toString(),
                            "originalOccurrenceStart" to start + day * 86400000,
                            "selectedOccurrenceWasDetached" to false), reply)
                }
                assertEquals("day $day: $result", "updated", result["outcome"].asString)
                root = result["resultingEventId"].asString.toLong()
                assertEquals(listOf(start + (day - 1) * 86400000), provider.starts(source))
                assertEquals((day..6L).map { start + it * 86400000 }, provider.starts(root))
                provider.db.rawQuery("SELECT attendeeStatus FROM Attendees WHERE event_id=?",
                    arrayOf(root.toString())).use { row ->
                    assertTrue(row.moveToFirst()); assertEquals(nextStatus, row.getInt(0))
                    assertFalse(row.moveToNext())
                }
                committedRoots.add(source)
                status = nextStatus
            }
            committedRoots.add(root)
            assertEquals(7, committedRoots.distinct().size)
            assertEquals((0L..6L).map { start + it * 86400000 },
                committedRoots.flatMap(provider::starts).sorted())
            assertEquals("No duplicate roots", 8, provider.rows().size) // seven + unrelated
            assertEquals(unrelated, provider.row(900))
        } finally { provider.db.close() }
    }

    private fun awaitNative(invoke: (MethodChannel.Result) -> Unit): com.google.gson.JsonObject {
        val done = AtomicBoolean(false)
        var response: Any? = null
        var failure: String? = null
        invoke(object : MethodChannel.Result {
            override fun success(result: Any?) { response = result; done.set(true) }
            override fun error(code: String, message: String?, details: Any?) { failure = "$code: $message"; done.set(true) }
            override fun notImplemented() { failure = "notImplemented"; done.set(true) }
        })
        val deadline = System.nanoTime() + 8_000_000_000L
        while (!done.get() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(1)
        }
        assertTrue("Native callback did not complete", done.get())
        assertNull(failure)
        return (if (response is String) JsonParser.parseString(response as String)
            else Gson().toJsonTree(response)).asJsonObject
    }
}
