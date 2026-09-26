package com.builttoroam.devicecalendar

import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.provider.Settings
import io.flutter.plugin.common.MethodCall
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
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual production snapshot, RRULE parser, SQL assert/update batch and native
 * method handler. Not an Android/Google sync emulator or an Instances oracle. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RecentRecurrenceRepairBoundaryTest {
    private lateinit var provider: RepairBoundaryProvider
    private val app get() = RuntimeEnvironment.getApplication()
    private val resolver get() = app.contentResolver
    private val roots = listOf("21096", "900")
    private val replies = mutableListOf<RepairReply>()
    private val release = CountDownLatch(1)
    @Before fun setup() {
        provider = RepairBoundaryProvider()
        provider.onCreate()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        seed()
        Settings.Secure.putString(resolver, Settings.Secure.ANDROID_ID, "device")
        Settings.Global.putInt(resolver, Settings.Global.BOOT_COUNT, 3)
    }
    @After fun close() {
        release.countDown()
        replies.forEach { waitFor { it.done } }
        provider.db.close()
    }
    private fun seed() {
        provider.db.delete("Events", null, null)
        provider.seed(1788806700000, 6, true, true)
    }
    private fun snapshot() = RecentRecurrenceRepair.read(resolver, "7", roots)
    private fun repair(rows: List<Map<String, String?>> = snapshot(), rule: String = "FREQ=DAILY;COUNT=1") =
        RecentRecurrenceRepair.apply(resolver, "7", roots, rows, "21096", rule)
    private fun waitFor(predicate: () -> Boolean) {
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!predicate() && System.nanoTime() < limit) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        assertTrue("Native completion timed out", predicate())
    }
    private fun args() = mutableMapOf<String, Any?>(
        "calendarId" to "7", "roots" to roots, "rows" to snapshot(),
        "target" to "21096", "rule" to "FREQ=DAILY;COUNT=1", "epoch" to "device:3",
        "deadlineUs" to SystemClock.elapsedRealtimeNanos() / 1000 + 60_000_000L)
    private fun call(method: String, arguments: Any?): RepairReply = RepairReply().also {
        replies.add(it)
        RecentRecurrenceRepair.handle(app, MethodCall(method, arguments), it)
    }

    @Test fun creationRepairGuardsSurvivingSplitChildAndOnlyRestoresOriginalRoot() {
        provider.db.delete("Events", "original_id=?", arrayOf("21096"))
        provider.db.execSQL(
            "UPDATE Events SET rrule=NULL, dtend=dtstart+3600000, duration=NULL WHERE _id=21096")
        val rows = snapshot()
        assertTrue(RecentRecurrenceRepair.apply(resolver, "7", roots, rows,
            "21096", "FREQ=DAILY;COUNT=1", kind = "recurrence-creation"))
        assertEquals("FREQ=DAILY;COUNT=1", provider.row(21096)!!.getAsString(Events.RRULE))
        assertEquals("FREQ=DAILY;COUNT=1;WKST=MO", provider.row(900)!!.getAsString(Events.RRULE))

        seed()
        provider.db.delete("Events", "original_id=?", arrayOf("21096"))
        provider.db.execSQL(
            "UPDATE Events SET rrule=NULL, dtend=dtstart+3600000, duration=NULL WHERE _id=21096")
        val guardedRows = snapshot()
        provider.beforeBatch = {
            provider.db.execSQL("UPDATE Events SET rrule='FREQ=DAILY;COUNT=2' WHERE _id=900")
        }
        assertFalse(RecentRecurrenceRepair.apply(resolver, "7", roots, guardedRows,
            "21096", "FREQ=DAILY;COUNT=1", kind = "recurrence-creation"))
        assertNull(provider.row(21096)!!.getAsString(Events.RRULE))
    }

    @Test fun everyStructuralColumnOnRootCompanionAndExceptionIsGuardedAtCommit() {
        val changes = mapOf(
            Events.CALENDAR_ID to "8", Events.DTSTART to "123456", Events.DTEND to "654321",
            Events.DURATION to "P7200S", Events.ALL_DAY to "1", Events.EVENT_TIMEZONE to "UTC",
            Events.EVENT_END_TIMEZONE to "UTC", Events.RRULE to "FREQ=WEEKLY;COUNT=2",
            Events.RDATE to "20261201T180000Z", Events.EXRULE to "FREQ=DAILY;COUNT=1",
            Events.EXDATE to "20260908T180000Z", Events.ORIGINAL_ID to "30000",
            Events.ORIGINAL_SYNC_ID to "different-origin", Events.ORIGINAL_INSTANCE_TIME to "11111",
            Events.ORIGINAL_ALL_DAY to "1", Events.STATUS to "2", Events.DELETED to "1",
            Events._SYNC_ID to "replaced-sync")
        for (id in listOf(21096L, 900L, 21098L)) for ((column, value) in changes) {
            seed()
            val rows = snapshot()
            provider.beforeBatch = {
                provider.db.update("Events", ContentValues().apply { put(column, value) }, "_id=?", arrayOf("$id"))
            }
            assertFalse("CAS missed $id/$column", repair(rows))
            val expected = if (id == 21096L && column == Events.RRULE) value else "FREQ=DAILY;COUNT=6;WKST=MO"
            assertEquals("CAS changed target while $id/$column conflicted", expected,
                provider.row(21096)!!.getAsString(Events.RRULE))
        }
    }
    @Test fun physicalDeletionAndIdReplacementAreNotGuessedFromMatchingTimes() {
        for (id in listOf(21096L, 900L, 21098L)) for (replace in listOf(false, true)) {
            seed()
            val rows = snapshot()
            provider.beforeBatch = {
                if (replace) provider.db.execSQL("UPDATE Events SET _id=30000 WHERE _id=$id")
                else provider.db.delete("Events", "_id=?", arrayOf("$id"))
            }
            assertFalse("Missing/replaced $id", repair(rows))
        }
    }
    @Test fun syncOnlyExceptionAndTombstoneAreIncludedOutsideAnyVisibleRange() {
        provider.db.execSQL("UPDATE Events SET original_id=NULL, dtstart=9999999999999, deleted=1 WHERE _id=21098")
        val rows = snapshot()
        val hidden = rows.single { it[Events._ID] == "21098" }
        assertEquals("1", hidden[Events.DELETED])
        assertEquals("9999999999999", hidden[Events.DTSTART])
        provider.beforeBatch = { provider.db.execSQL("UPDATE Events SET originalInstanceTime=12345 WHERE _id=21098") }
        assertFalse(repair(rows))
    }
    @Test fun exceptionInsertedBySyncLinkOnlyBreaksCardinalityGuard() {
        val rows = snapshot()
        provider.beforeBatch = {
            provider.db.insertOrThrow("Events", null, ContentValues(provider.row(21098)!!).apply {
                put(Events._ID, 30000); putNull(Events.ORIGINAL_ID); put(Events.DTSTART, 9999999999999L)
            })
        }
        assertFalse(repair(rows))
    }
    @Test fun unrelatedCalendarRowsWithSameSyncIdentityDoNotBlockOrChange() {
        provider.db.insertOrThrow("Events", null, ContentValues(provider.row(21098)!!).apply {
            put(Events._ID, 30000); put(Events.CALENDAR_ID, 8); putNull(Events.ORIGINAL_ID)
        })
        val unrelated = provider.row(30000)
        assertFalse(snapshot().any { it[Events._ID] == "30000" })
        assertTrue(repair())
        assertEquals(unrelated, provider.row(30000))
    }
    @Test fun changedContentAndChildTablesAreNeverRestoredFromOldProof() {
        val rows = snapshot()
        provider.beforeBatch = {
            provider.db.execSQL("UPDATE Events SET title='new title', description='new description', selfAttendeeStatus=4, eventColor=42, eventLocation='new place' WHERE _id IN (21096,900,21098)")
            provider.db.execSQL("INSERT INTO Attendees (event_id,attendeeEmail,attendeeStatus) VALUES (21096,'a@example.com',4)")
            provider.db.execSQL("INSERT INTO Reminders (event_id,minutes,method) VALUES (21096,120,1)")
        }
        assertTrue(repair(rows))
        for (id in listOf(21096L, 900L, 21098L)) {
            val row = provider.row(id)!!
            assertEquals("new title", row.getAsString(Events.TITLE))
            assertEquals("new description", row.getAsString(Events.DESCRIPTION))
            assertEquals(4, row.getAsInteger(Events.SELF_ATTENDEE_STATUS))
            assertEquals(42, row.getAsInteger(Events.EVENT_COLOR))
            assertEquals("new place", row.getAsString(Events.EVENT_LOCATION))
        }
        provider.db.rawQuery("SELECT attendeeStatus FROM Attendees", null).use { assertTrue(it.moveToFirst()); assertEquals(4,it.getInt(0)) }
        provider.db.rawQuery("SELECT minutes FROM Reminders", null).use { assertTrue(it.moveToFirst()); assertEquals(120,it.getInt(0)) }
    }
    @Test fun recurrenceFamiliesAndAllDayDstMetadataRemainByteForByteUnchanged() {
        val patterns = listOf("FREQ=DAILY", "FREQ=WEEKLY;BYDAY=MO,WE", "FREQ=MONTHLY;BYDAY=1MO", "FREQ=YEARLY;BYMONTH=3;BYMONTHDAY=29")
        for (pattern in patterns) for (kind in listOf("all-day", "spring", "autumn")) {
            seed()
            val start = when(kind) { "spring" -> 1774744200000L; "autumn" -> 1792888200000L; else -> 1788739200000L }
            provider.db.update("Events", ContentValues().apply {
                put(Events.RRULE, "$pattern;COUNT=6")
                put(Events.DTSTART, start)
                put(Events.ALL_DAY, if (kind == "all-day") 1 else 0)
                put(Events.DURATION, if (kind == "all-day") "P1D" else "PT1H")
                put(Events.EVENT_TIMEZONE, if (kind == "all-day") "UTC" else "Europe/London")
            }, "_id=21096", null)
            val before = ContentValues(provider.row(21096)!!)
            assertTrue("$pattern/$kind", repair(rule = "$pattern;COUNT=1"))
            before.put(Events.RRULE, "$pattern;COUNT=1")
            assertEquals(before, provider.row(21096))
        }
    }
    @Test fun boundedUntilAndFloatingAllDayUntilOnlyShorten() {
        for (dates in listOf("20261001T180000Z" to "20260908T180000Z", "20261001" to "20260908")) {
            seed()
            provider.db.execSQL("UPDATE Events SET rrule='FREQ=DAILY;UNTIL=${dates.first}' WHERE _id=21096")
            assertTrue(repair(rule = "FREQ=DAILY;UNTIL=${dates.second}"))
        }
    }
    @Test fun mixedCountUntilPatternsAndZeroCountsNeverExpandASeries() {
        val invalid = listOf("FREQ=DAILY;COUNT=0", "FREQ=DAILY;COUNT=6", "FREQ=DAILY;COUNT=7", "FREQ=DAILY",
            "FREQ=DAILY;UNTIL=20261001T180000Z", "FREQ=DAILY;COUNT=1;BYHOUR=12", "FREQ=DAILY;COUNT=1;WKST=SU")
        for (rule in invalid) {
            seed()
            val before = provider.rows()
            assertThrows("$rule", Exception::class.java) { repair(rule = rule) }
            assertEquals(before, provider.rows())
        }
    }
    @Test fun incompleteExtraDuplicateAndWrongCalendarProofsFailClosed() {
        val rows = snapshot()
        val invalid = listOf(rows.drop(1), rows + rows.first(), rows.map { it - Events.DELETED },
            rows.map { it + (Events.CALENDAR_ID to "8") }, rows.map { it + ("untrusted" to "extra") })
        for (evidence in invalid) {
            val before = provider.rows()
            // Missing exception coverage can pass shape validation, but MUST
            // fail the native count assertion rather than authorize a write.
            try { assertFalse(repair(evidence)) } catch (_: IllegalArgumentException) { }
            assertEquals(before, provider.rows())
        }
    }
    @Test fun oversizedProofAndRootListsAreRejectedWithoutTruncation() {
        for (i in 0..252) provider.db.insertOrThrow("Events", null,
            ContentValues(provider.row(21098)!!).apply { put(Events._ID, 30000 + i) })
        assertThrows(IllegalStateException::class.java) { snapshot() }
        assertThrows(IllegalArgumentException::class.java) { RecentRecurrenceRepair.read(resolver, "7", (0..64).map { "$it" }) }
        assertThrows(IllegalArgumentException::class.java) { RecentRecurrenceRepair.read(resolver, "7", listOf("900", "900")) }
    }
    @Test fun expiredUnknownAndDifferentEpochRequestsNeverEnterProviderBatch() {
        for (mode in listOf("expired", "unknown", "other-boot")) {
            val input = args()
            when(mode) {
                "expired" -> input["deadlineUs"] = SystemClock.elapsedRealtimeNanos() / 1000
                "unknown" -> Settings.Global.putInt(resolver, Settings.Global.BOOT_COUNT, -1)
                "other-boot" -> { Settings.Global.putInt(resolver, Settings.Global.BOOT_COUNT, 3); input["epoch"] = "device:2" }
            }
            var batch = false
            provider.beforeBatch = { batch = true }
            val result = call("repairRecurrencePrefix", input)
            waitFor { result.done }
            assertEquals("expired", result.value)
            assertFalse(batch)
            assertTrue(result.onMain)
        }
    }
    @Test fun deadlineIsRecheckedAfterWaitingBehindAnotherNativeWrite() {
        val entered = CountDownLatch(1)
        val blocker = RepairReply().also { replies.add(it) }
        CalendarWriteExecutor.submit(blocker, {}) {
            entered.countDown()
            check(release.await(8, TimeUnit.SECONDS))
            it.success("done")
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val result = call("repairRecurrencePrefix", args())
        ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
        release.countDown()
        waitFor { result.done }
        assertEquals("expired", result.value)
        assertEquals("FREQ=DAILY;COUNT=6;WKST=MO", provider.row(21096)!!.getAsString(Events.RRULE))
    }
    @Test fun snapshotAndRepairHandlerRunProviderWorkOffMainAndReplyOnMain() {
        val input = args()
        provider.queryThreads.clear()
        val read = call("readRecurrenceRepairSnapshot", input)
        waitFor { read.done }
        assertNull(read.error)
        val write = call("repairRecurrencePrefix", input)
        waitFor { write.done }
        assertEquals("updated", write.value)
        assertTrue(read.onMain && write.onMain)
        assertTrue(provider.queryThreads.isNotEmpty())
        assertTrue(provider.queryThreads.all { !it })
    }
    @Test fun revokedPermissionDoesNotBecomeASuccessAndDoesNotStrandNativeLane() {
        val input = args()
        provider.deny = true
        val read = call("readRecurrenceRepairSnapshot", input)
        waitFor { read.done }
        assertNotNull(read.error)
        provider.deny = false
        val next = call("repairRecurrencePrefix", input)
        waitFor { next.done }
        assertEquals("updated", next.value)
    }
}

private class RepairBoundaryProvider : TombstoneCalendarProvider() {
    val queryThreads = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
    @Volatile var deny = false
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        if (deny) throw SecurityException("Calendar permission revoked")
        queryThreads.add(Looper.myLooper() == Looper.getMainLooper())
        return super.query(uri, projection, selection, selectionArgs, sortOrder)
    }
}
private class RepairReply : MethodChannel.Result {
    @Volatile var done = false
    var value: Any? = null
    var error: String? = null
    var onMain = false
    override fun success(result: Any?) { value = result; finish() }
    override fun error(code: String, message: String?, details: Any?) { error = "$code:$message"; finish() }
    override fun notImplemented() { error = "not implemented"; finish() }
    private fun finish() { onMain = Looper.myLooper() == Looper.getMainLooper(); done = true }
}
