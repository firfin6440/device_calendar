package com.builttoroam.devicecalendar

import android.content.ContentValues
import android.os.Looper
import android.util.Log
import io.flutter.plugin.common.MethodChannel
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.Executor
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RecentRecurrenceRepairTest {
    private lateinit var provider: TombstoneCalendarProvider
    private val resolver get() = RuntimeEnvironment.getApplication().contentResolver
    @Before fun setup() {
        provider = TombstoneCalendarProvider()
        provider.onCreate()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        provider.seed(1788806700000, 6, true, true)
        ShadowLog.clear()
    }
    @After fun close() { provider.db.close() }
    private fun snapshot() = RecentRecurrenceRepair.read(resolver, "7", listOf("21096", "900"))
    private fun repair(rows: List<Map<String, String?>>) = RecentRecurrenceRepair.apply(
        resolver, "7", listOf("21096", "900"), rows, "21096", "FREQ=DAILY;COUNT=1", "test-action:2")

    @Test fun recurrenceCreationSyncBackRestoresMinimalStorageOnceWithGuards() {
        provider.db.execSQL("DELETE FROM Events WHERE _id!=21096")
        provider.db.execSQL("UPDATE Events SET rrule=NULL,duration=NULL,dtend=dtstart+3600000 WHERE _id=21096")
        val rows = RecentRecurrenceRepair.read(resolver, "7", listOf("21096"))
        assertTrue(RecentRecurrenceRepair.apply(resolver, "7", listOf("21096"), rows,
            "21096", "FREQ=DAILY;COUNT=7", "create-action:1", false, "recurrence-creation"))
        assertEquals("FREQ=DAILY;COUNT=7", provider.row(21096)!!.getAsString(Events.RRULE))
        assertNull(provider.row(21096)!!.getAsString(Events.DTEND))
        assertNull(provider.row(21096)!!.getAsString(Events.EVENT_END_TIMEZONE))
        assertEquals("P3600S", provider.row(21096)!!.getAsString(Events.DURATION))
        assertFalse(RecentRecurrenceRepair.apply(resolver, "7", listOf("21096"), rows,
            "21096", "FREQ=DAILY;COUNT=7", "create-action:1", false, "recurrence-creation"))
        assertEquals(1, ShadowLog.getLogsForTag("KeepCalSyncRepair").size)
    }

    @Test fun recurrenceCreationRepairRefusesConcurrentStructuralChange() {
        provider.db.execSQL("DELETE FROM Events WHERE _id!=21096")
        provider.db.execSQL("UPDATE Events SET rrule=NULL,duration=NULL,dtend=dtstart+3600000 WHERE _id=21096")
        val rows = RecentRecurrenceRepair.read(resolver, "7", listOf("21096"))
        provider.beforeBatch = { provider.db.execSQL("UPDATE Events SET dtstart=dtstart+3600000 WHERE _id=21096") }
        assertFalse(RecentRecurrenceRepair.apply(resolver, "7", listOf("21096"), rows,
            "21096", "FREQ=DAILY;COUNT=7", "create-action:2", false, "recurrence-creation"))
        assertNull(provider.row(21096)!!.getAsString(Events.RRULE))
        assertTrue(ShadowLog.getLogsForTag("KeepCalSyncRepair").isEmpty())
    }

    @Test fun recurrenceCreationRepairAcceptsRruleOnlyReversalWithDurationIntact() {
        provider.db.execSQL("DELETE FROM Events WHERE _id!=21096")
        provider.db.execSQL("UPDATE Events SET rrule=NULL,dtend=NULL,duration='P3600S',eventEndTimezone=NULL WHERE _id=21096")
        val rows = RecentRecurrenceRepair.read(resolver, "7", listOf("21096"))
        assertTrue(RecentRecurrenceRepair.apply(resolver, "7", listOf("21096"), rows,
            "21096", "FREQ=DAILY;COUNT=7", "create-action:3", false, "recurrence-creation"))
        assertEquals("FREQ=DAILY;COUNT=7", provider.row(21096)!!.getAsString(Events.RRULE))
        assertEquals("P3600S", provider.row(21096)!!.getAsString(Events.DURATION))
    }

    @Test fun appliedRepairAlwaysHasOneSmallAuditWithoutEventContent() {
        assertTrue(repair(snapshot()))
        val audit = ShadowLog.getLogsForTag("KeepCalSyncRepair").single()
        assertEquals(Log.INFO, audit.type)
        assertTrue(audit.msg.startsWith("applied "))
        assertTrue(audit.msg.contains("\"calendar\":\"7\""))
        assertTrue(audit.msg.contains("\"root\":\"21096\""))
        assertTrue(audit.msg.contains("\"field\":\"rrule\""))
        assertTrue(audit.msg.contains("\"repairId\":\"test-action:2\""))
        assertTrue(audit.msg.length < 600)
        for (sensitive in listOf("title", "description", "attendees", "_sync_id", "FREQ=")) {
            assertFalse(audit.msg.contains(sensitive))
        }
    }
    @Test fun conflictsAndInvalidRequestsNeverSayApplied() {
        val rows = snapshot()
        provider.beforeBatch = { provider.db.execSQL("UPDATE Events SET deleted=1 WHERE _id=900") }
        assertFalse(repair(rows))
        assertThrows(IllegalArgumentException::class.java) { repair(emptyList()) }
        assertTrue(ShadowLog.getLogsForTag("KeepCalSyncRepair").isEmpty())
    }
    @Test fun duplicateOldSnapshotCannotProduceASecondAppliedAudit() {
        val rows = snapshot()
        assertTrue(repair(rows))
        assertFalse(repair(rows))
        assertEquals(1, ShadowLog.getLogsForTag("KeepCalSyncRepair").size)
    }
    @Test fun nativeRejectionReasonAndStackAreOptInAndCorrelated() {
        val rows = snapshot()
        provider.beforeBatch = { provider.db.execSQL("UPDATE Events SET deleted=1 WHERE _id=900") }
        assertFalse(repair(rows))
        assertTrue(ShadowLog.getLogsForTag("KeepCalSyncRepairDebug").isEmpty())
        assertFalse(RecentRecurrenceRepair.apply(resolver, "7", listOf("21096", "900"),
            rows, "21096", "FREQ=DAILY;COUNT=1", "debug-action:3", true))
        val detail = ShadowLog.getLogsForTag("KeepCalSyncRepairDebug").single()
        assertTrue(detail.msg.contains("debug-action:3"))
        assertTrue(detail.msg.startsWith("rejected "))
        assertNotNull(detail.throwable)
        assertTrue(ShadowLog.getLogsForTag("KeepCalSyncRepair").isEmpty())
    }
    @Test fun appliedAuditExistsBeforeReplyAndSurvivesReplyDeliveryFailure() {
        val lane = CalendarWriteLane(Executor { it.run() })
        var replied = false
        val gone = object : MethodChannel.Result {
            override fun success(result: Any?) { replied = true; error("engine disappeared") }
            override fun error(code: String, message: String?, details: Any?) { throw AssertionError(code) }
            override fun notImplemented() { throw AssertionError("not implemented") }
        }
        lane.submit(gone, {}) { reply -> reply.success(repair(snapshot())) }
        assertFalse(replied)
        assertEquals(1, ShadowLog.getLogsForTag("KeepCalSyncRepair").size)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(replied)
        assertEquals(1, ShadowLog.getLogsForTag("KeepCalSyncRepair").size)
        assertEquals("FREQ=DAILY;COUNT=1", provider.row(21096)!!.getAsString(Events.RRULE))
    }

    @Test fun narrowRepairPreservesExceptionsAndUnrelatedFields() {
        val before = provider.rows().associateBy { it.getAsLong(Events._ID) }
        val rows = snapshot()
        provider.db.execSQL("UPDATE Events SET title='external title' WHERE _id=21096")
        assertTrue(repair(rows))
        assertEquals("FREQ=DAILY;COUNT=1", provider.row(21096)!!.getAsString(Events.RRULE))
        assertEquals("external title", provider.row(21096)!!.getAsString(Events.TITLE))
        for (id in listOf(900L, 21098L, 21097L)) assertEquals(before[id], provider.row(id))
        assertEquals(before.size, provider.rows().size)
    }
    @Test fun competingRootEditIsNotOverwritten() {
        val rows = snapshot()
        provider.beforeBatch = { provider.db.execSQL("UPDATE Events SET rrule='FREQ=DAILY;COUNT=5' WHERE _id=21096") }
        assertFalse(repair(rows))
        assertEquals("FREQ=DAILY;COUNT=5", provider.row(21096)!!.getAsString(Events.RRULE))
    }
    @Test fun competingCompanionDeleteRollsBackRepair() {
        val rows = snapshot()
        provider.beforeBatch = { provider.db.execSQL("UPDATE Events SET deleted=1 WHERE _id=900") }
        assertFalse(repair(rows))
        assertEquals("FREQ=DAILY;COUNT=6;WKST=MO", provider.row(21096)!!.getAsString(Events.RRULE))
    }
    @Test fun exceptionChangeBetweenReadAndCommitRollsBackRepair() {
        val rows = snapshot()
        provider.beforeBatch = { provider.db.execSQL("UPDATE Events SET dtstart=dtstart+3600000 WHERE _id=21098") }
        assertFalse(repair(rows))
        assertEquals("FREQ=DAILY;COUNT=6;WKST=MO", provider.row(21096)!!.getAsString(Events.RRULE))
    }
    @Test fun insertedExceptionIsDetectedEvenThoughItWasNotInSnapshot() {
        val rows = snapshot()
        provider.beforeBatch = {
            provider.db.insertOrThrow("Events", null, ContentValues(provider.row(21098)!!).apply { put(Events._ID, 22000) })
        }
        assertFalse(repair(rows))
        assertEquals("FREQ=DAILY;COUNT=6;WKST=MO", provider.row(21096)!!.getAsString(Events.RRULE))
    }
    @Test fun malformedGuardOrMissingRootCannotAuthorizeAnUpdate() {
        assertThrows(IllegalArgumentException::class.java) { repair(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            repair(snapshot().map { it - Events.DTSTART })
        }
    }
    @Test fun secondIdenticalRequestDoesNotApplyAgainstNewValues() {
        val rows = snapshot()
        assertTrue(repair(rows))
        assertFalse(repair(rows))
    }
    @Test fun cannotExpandOrChangeRecurrencePattern() {
        val rows = snapshot()
        for (rule in listOf("FREQ=DAILY;COUNT=7", "FREQ=WEEKLY;COUNT=1", "FREQ=DAILY;INTERVAL=2;COUNT=1")) {
            assertThrows(IllegalArgumentException::class.java) {
                RecentRecurrenceRepair.apply(resolver, "7", listOf("21096", "900"), rows, "21096", rule)
            }
        }
        assertEquals("FREQ=DAILY;COUNT=6;WKST=MO", provider.row(21096)!!.getAsString(Events.RRULE))
    }
    @Test fun untilPrefixCanBeRepairedWithoutEnumeratingInfiniteSeries() {
        provider.db.execSQL("UPDATE Events SET rrule='FREQ=DAILY' WHERE _id=21096")
        assertTrue(RecentRecurrenceRepair.apply(resolver, "7", listOf("21096", "900"), snapshot(),
            "21096", "FREQ=DAILY;UNTIL=20260907T235959Z"))
    }
}
