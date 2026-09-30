package com.builttoroam.devicecalendar

import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
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
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RecentDeletionRepairTest {
    private lateinit var provider: DeletionProofProvider
    private val resolver get() = RuntimeEnvironment.getApplication().contentResolver
    private val start = 1788806700000L
    private val day = 86400000L
    @Before fun setup() {
        provider = DeletionProofProvider()
        provider.onCreate()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        provider.seed(start, 7, true, true)
        provider.db.execSQL("INSERT INTO ExtendedProperties VALUES(1,21096,'keepcal','private-value')")
        ShadowLog.clear()
    }
    @After fun close() { provider.db.close() }
    private fun snapshot() = RecentDeletionRepair.read(resolver, "7", "21096")
    private fun apply(rows: List<Map<String, String?>>, scope: String = "all", slot: Long? = null) =
        RecentDeletionRepair.apply(resolver, "7", "21096", scope, slot, rows, rows, "delete-action:1")
    private fun live(id: Long) = provider.row(id)?.getAsInteger(Events.DELETED) == 0

    @Test fun allDeletionRemovesExactFamilyAndLeavesUnrelatedSeries() {
        val unrelated = ContentValues(provider.row(900)!!)
        val rows = snapshot()
        assertTrue(rows.any { it["_id"] == "21098" })
        assertTrue(apply(rows))
        for (row in rows) assertFalse(live(row.getValue("_id")!!.toLong()))
        assertEquals(unrelated, provider.row(900))
        assertEquals(1, ShadowLog.getLogsForTag("KeepCalSyncRepair").size)
        assertFalse(ShadowLog.getLogsForTag("KeepCalSyncRepair").single().msg.contains("private-value"))
    }
    @Test fun standaloneDeletionDoesNotRequireRrule() {
        provider.db.execSQL("DELETE FROM Events WHERE original_id=21096")
        provider.db.execSQL("UPDATE Events SET rrule=NULL,dtend=dtstart+3600000,duration=NULL WHERE _id=21096")
        assertTrue(apply(snapshot()))
        assertFalse(live(21096))
        assertTrue(live(900))
    }
    @Test fun nativeAlsoRefusesHistoricalContentMismatch() {
        val before = snapshot()
        provider.db.execSQL("UPDATE Events SET title='valid external edit' WHERE _id=21096")
        val current = snapshot()
        assertThrows(IllegalArgumentException::class.java) {
            RecentDeletionRepair.apply(resolver, "7", "21096", "all", null, before, current, "a")
        }
        assertTrue(live(21096))
    }
    @Test fun dirtyOnlySyncTransitionIsAllowedButGuardedAtCommit() {
        val before = snapshot()
        provider.db.execSQL("UPDATE Events SET dirty=1-dirty")
        assertTrue(RecentDeletionRepair.apply(resolver, "7", "21096", "all", null, before, snapshot(), "a"))
    }
    @Test fun relatedRowIdsCanChangeWithIdenticalPayloadButCurrentIdsAreGuarded() {
        provider.db.execSQL("INSERT INTO Attendees(_id,event_id,attendeeEmail,attendeeStatus) VALUES(9000,21096,'guest@example.com',1)")
        val before = snapshot()
        provider.db.execSQL("UPDATE Attendees SET _id=9001 WHERE _id=9000")
        assertTrue(RecentDeletionRepair.apply(resolver, "7", "21096", "all", null, before, snapshot(), "a"))
    }
    @Test fun titleChangedAfterDetectionAbortsWholeDeletion() {
        val rows = snapshot()
        provider.beforeBatch = { provider.db.execSQL("UPDATE Events SET title='new title' WHERE _id=21096") }
        assertFalse(apply(rows))
        assertTrue(live(21096)); assertTrue(live(21098))
        assertTrue(ShadowLog.getLogsForTag("KeepCalSyncRepair").isEmpty())
    }
    @Test fun attendeeRsvpChangedAfterDetectionAbortsWholeDeletion() {
        provider.db.execSQL("INSERT INTO Attendees(_id,event_id,attendeeEmail,attendeeStatus) VALUES(9000,21096,'guest@example.com',1)")
        val rows = snapshot()
        provider.beforeBatch = { provider.db.execSQL("UPDATE Attendees SET attendeeStatus=2 WHERE _id=9000") }
        assertFalse(apply(rows)); assertTrue(live(21096))
    }
    @Test fun newAttendeeAfterDetectionAbortsDeletion() {
        val rows = snapshot()
        provider.beforeBatch = { provider.db.execSQL("INSERT INTO Attendees(_id,event_id,attendeeEmail) VALUES(9000,21096,'new@example.com')") }
        assertFalse(apply(rows)); assertTrue(live(21096))
    }
    @Test fun changedReminderAbortsDeletion() {
        provider.db.execSQL("INSERT INTO Reminders VALUES(9000,21096,30,1)")
        val rows = snapshot()
        provider.beforeBatch = { provider.db.execSQL("UPDATE Reminders SET minutes=60 WHERE _id=9000") }
        assertFalse(apply(rows)); assertTrue(live(21096))
    }
    @Test fun changedPropertyAbortsDeletion() {
        val rows = snapshot()
        provider.beforeBatch = { provider.db.execSQL("UPDATE ExtendedProperties SET value='external' WHERE _id=1") }
        assertFalse(apply(rows)); assertTrue(live(21096))
    }
    @Test fun newOutOfRangeExceptionAbortsDeletion() {
        val rows = snapshot()
        provider.beforeBatch = { provider.db.insertOrThrow("Events", null, ContentValues(provider.row(21098)!!).apply {
            put(Events._ID, 33333); put(Events.DTSTART, start + 999 * day)
        }) }
        assertFalse(apply(rows)); assertTrue(live(21096)); assertTrue(live(33333))
    }
    @Test fun changedMovedExceptionAbortsDeletion() {
        val rows = snapshot()
        provider.beforeBatch = { provider.db.execSQL("UPDATE Events SET dtstart=dtstart+86400000 WHERE _id=21098") }
        assertFalse(apply(rows)); assertTrue(live(21096))
    }
    @Test fun identityReassignedAfterDetectionAbortsDeletion() {
        val rows = snapshot()
        provider.beforeBatch = { provider.db.execSQL("UPDATE Events SET _sync_id='other-remote' WHERE _id=21096") }
        assertFalse(apply(rows)); assertTrue(live(21096))
    }
    @Test fun rootGoneButSyncedOrphanStillPresentIsNotCompleteAbsence() {
        val sync = provider.row(21096)!!.getAsString(Events._SYNC_ID)
        provider.db.execSQL("DELETE FROM Events WHERE _id=21096")
        provider.db.execSQL("UPDATE Events SET original_id=NULL WHERE _id=21098")
        val rows = RecentDeletionRepair.read(resolver, "7", "21096", sync)
        assertTrue(rows.any { it["_id"] == "21098" })
    }
    @Test fun changedTombstoneSyncIdCannotHideHistoricalOrphans() {
        provider.db.execSQL("UPDATE Events SET deleted=1,_sync_id='changed' WHERE _id=21096")
        provider.db.execSQL("UPDATE Events SET original_id=NULL WHERE _id=21098")
        val rows = RecentDeletionRepair.read(resolver, "7", "21096", "remote-master")
        assertTrue(rows.any { it["_id"] == "21098" })
    }
    @Test fun softDeletedRowsAreAbsenceButLiveCancellationsRemainInProof() {
        provider.db.execSQL("UPDATE Events SET deleted=1 WHERE _id=21098")
        provider.db.execSQL("UPDATE Events SET deleted=0,eventStatus=2 WHERE _id=21097")
        val rows = snapshot()
        assertFalse(rows.any { it["_id"] == "21098" })
        assertTrue(rows.any { it[Events.STATUS] == "2" })
    }
    @Test fun followingUsesOriginalSlotsAndKeepsEarlierException() {
        // Independently known boundary: Wednesday, exactly two generated slots before it.
        provider.db.execSQL("UPDATE Events SET originalInstanceTime=${start + day},dtstart=${start + 100 * day} WHERE _id=21097")
        provider.db.execSQL("UPDATE Events SET originalInstanceTime=${start + 3 * day},dtstart=$start WHERE _id=21098")
        val rows = snapshot()
        assertTrue(apply(rows, "following", start + 2 * day))
        assertEquals("FREQ=DAILY;WKST=MO;COUNT=2", provider.row(21096)!!.getAsString(Events.RRULE))
        assertEquals(start, provider.row(21096)!!.getAsLong(Events.DTSTART))
        assertTrue(live(21097)); assertFalse(live(21098)); assertTrue(live(900))
    }
    @Test fun firstFollowingDeletesWholeSeries() {
        assertTrue(apply(snapshot(), "following", start))
        assertFalse(live(21096)); assertFalse(live(21098)); assertTrue(live(900))
    }
    @Test fun movedOccurrenceIsCancelledAtOriginalSlotWithoutDeletingMaster() {
        provider.db.execSQL("UPDATE Events SET eventStatus=1,originalInstanceTime=${start + day},dtstart=${start + 80 * day} WHERE _id=21098")
        assertTrue(apply(snapshot(), "occurrence", start + day))
        assertEquals(2, provider.row(21098)!!.getAsInteger(Events.STATUS))
        assertEquals(start + 80 * day, provider.row(21098)!!.getAsLong(Events.DTSTART))
        assertTrue(live(21096)); assertTrue(live(900))
    }
    @Test fun missingCancellationIsRecreatedThroughExceptionUri() {
        provider.db.execSQL("DELETE FROM Events WHERE original_id=21096")
        assertTrue(apply(snapshot(), "occurrence", start + 2 * day))
        val cancelled = provider.rows().single { it.getAsInteger(Events.STATUS) == 2 }
        assertEquals(start + 2 * day, cancelled.getAsLong(Events.ORIGINAL_INSTANCE_TIME))
        assertEquals("remote-master", cancelled.getAsString(Events.ORIGINAL_SYNC_ID))
        assertEquals(21096L, cancelled.getAsLong(Events.ORIGINAL_ID))
        assertTrue(live(21096))
    }
    @Test fun missingSyncIdDoesNotAuthorizeCancellationInsertion() {
        provider.db.execSQL("DELETE FROM Events WHERE original_id=21096")
        provider.db.execSQL("UPDATE Events SET _sync_id=NULL WHERE _id=21096")
        assertNull(RecentDeletionRepair.plan(snapshot(), "21096", "occurrence", start + day))
    }
    @Test fun malformedProofCannotBeUsedToDelete() {
        val rows = snapshot()
        assertThrows(IllegalArgumentException::class.java) { apply(rows.map { it - Events.TITLE }) }
        assertThrows(IllegalArgumentException::class.java) { apply(emptyList()) }
        assertTrue(live(21096))
    }
    @Test fun mixedCaptureReadIsRejectedBeforeItBecomesHistory() {
        provider.beforeBatch = { provider.db.execSQL("UPDATE Events SET title='changed while reading' WHERE _id=21096") }
        assertThrows(android.content.OperationApplicationException::class.java) { snapshot() }
        assertTrue(live(21096))
    }
    @Test fun failureAfterChildDeletesRollsBackEntireRepair() {
        val rows = snapshot()
        provider.failRootDelete = true
        assertFalse(apply(rows))
        assertTrue(live(21096)); assertTrue(live(21098)); assertTrue(live(21097))
        assertTrue(ShadowLog.getLogsForTag("KeepCalSyncRepair").isEmpty())
    }
    @Test fun oversizedScalarRefusesCaptureRatherThanWeakeningProof() {
        provider.db.update("Events", ContentValues().apply { put(Events.DESCRIPTION, "x".repeat(65537)) }, "_id=21096", null)
        assertThrows(IllegalStateException::class.java) { snapshot() }
        assertTrue(live(21096))
    }
    @Test fun oversizedRelatedPayloadRefusesCaptureBeforeUnboundedAccumulation() {
        repeat(27) { index ->
            provider.db.insertOrThrow("ExtendedProperties", null, ContentValues().apply {
                put("event_id", 21096); put("name", "property-$index"); put("value", "x".repeat(10000))
            })
        }
        assertThrows(IllegalStateException::class.java) { snapshot() }
        assertTrue(live(21096))
    }
    @Test fun duplicateNativeRequestCannotDeleteAgain() {
        val rows = snapshot()
        assertTrue(apply(rows)); assertFalse(apply(rows))
        assertEquals(1, ShadowLog.getLogsForTag("KeepCalSyncRepair").size)
    }
}

private class DeletionProofProvider : TombstoneCalendarProvider() {
    var failRootDelete = false
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
        if (failRootDelete && args?.firstOrNull() == "21096") throw android.content.OperationApplicationException("root delete failed")
        return super.delete(uri, selection, args)
    }
    override fun onCreate(): Boolean {
        super.onCreate()
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
}
