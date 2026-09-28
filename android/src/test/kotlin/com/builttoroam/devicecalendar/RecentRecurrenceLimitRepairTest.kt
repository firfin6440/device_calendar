package com.builttoroam.devicecalendar

import android.content.ContentValues
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
class RecentRecurrenceLimitRepairTest {
    private lateinit var provider: TombstoneCalendarProvider
    private val resolver get() = RuntimeEnvironment.getApplication().contentResolver
    @Before fun setup() {
        provider = TombstoneCalendarProvider()
        provider.onCreate()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        seed()
    }
    @After fun close() { provider.db.close() }
    private fun seed() {
        provider.beforeBatch = null
        provider.db.execSQL("DELETE FROM Events")
        provider.seed(1788806700000, 5, true, true)
        ShadowLog.clear()
    }
    private fun snapshot() = RecentRecurrenceRepair.read(resolver, "7", listOf("21096"))
    private fun repair(rows: List<Map<String, String?>> = snapshot(),
                       rule: String = "FREQ=DAILY;COUNT=7") = RecentRecurrenceRepair.apply(
        resolver, "7", listOf("21096"), rows, "21096", rule,
        "limit-action:1", false, "recurrence-limit")

    @Test fun restoresFiveToSevenWithoutReplayingEventOrExceptionsAndAuditsOnce() {
        val before = provider.rows().associateBy { it.getAsLong(Events._ID) }
        val rows = snapshot()
        assertTrue(repair(rows))
        val expected = ContentValues(before[21096L]!!).apply { put(Events.RRULE, "FREQ=DAILY;COUNT=7") }
        assertEquals(expected, provider.row(21096))
        for ((id, values) in before) if (id != 21096L) assertEquals(values, provider.row(id))
        assertEquals(before.size, provider.rows().size)
        assertFalse(repair(rows))
        val log = ShadowLog.getLogsForTag("KeepCalSyncRepair").single()
        assertTrue(log.msg.contains("restore-recurrence-limit"))
    }

    @Test fun supportsCountAndUntilInBothDirectionsWithoutChangingPattern() {
        for (pattern in listOf("FREQ=DAILY", "FREQ=WEEKLY;BYDAY=MO,WE;WKST=SU",
                "FREQ=MONTHLY;BYMONTHDAY=1", "FREQ=YEARLY;BYMONTH=9;BYMONTHDAY=7")) {
            for ((oldLimit, newLimit) in listOf("COUNT=5" to "COUNT=7", "COUNT=7" to "COUNT=5",
                    "UNTIL=20261001T184500Z" to "UNTIL=20261101T184500Z",
                    "UNTIL=20261101T184500Z" to "UNTIL=20261001T184500Z")) {
                seed()
                provider.db.execSQL("UPDATE Events SET rrule=? WHERE _id=21096", arrayOf("$pattern;$oldLimit"))
                assertTrue(repair(rule = "$pattern;$newLimit"))
                assertEquals("$pattern;$newLimit", provider.row(21096)!!.getAsString(Events.RRULE))
            }
        }
    }

    @Test fun rootAndRelatedExceptionChangesAreAtomicallyRejected() {
        val changes = mapOf(Events.DTSTART to "123456", Events.DURATION to "P7200S",
            Events.ALL_DAY to "1", Events.EVENT_TIMEZONE to "UTC", Events.EVENT_END_TIMEZONE to "UTC",
            Events.RRULE to "FREQ=WEEKLY;COUNT=9", Events.RDATE to "20261201T180000Z",
            Events.EXDATE to "20260908T180000Z", Events.EXRULE to "FREQ=DAILY;COUNT=1",
            Events.ORIGINAL_ID to "30000", Events.ORIGINAL_SYNC_ID to "another-origin",
            Events.ORIGINAL_INSTANCE_TIME to "11111", Events.ORIGINAL_ALL_DAY to "1",
            Events.STATUS to "2", Events.DELETED to "1", Events._SYNC_ID to "new-sync")
        for (id in listOf(21096L, 21098L)) for ((column, value) in changes) {
            seed()
            val rows = snapshot()
            provider.beforeBatch = {
                provider.db.update("Events", ContentValues().apply { put(column, value) }, "_id=?", arrayOf("$id"))
            }
            assertFalse("CAS missed $id/$column", repair(rows))
            assertTrue(ShadowLog.getLogsForTag("KeepCalSyncRepair").isEmpty())
        }
    }

    @Test fun insertionDeletionAndReplacementInvalidateCompleteGuard() {
        for (change in listOf("insert", "delete", "replace")) {
            seed()
            val rows = snapshot()
            provider.beforeBatch = {
                when (change) {
                    "insert" -> provider.db.insertOrThrow("Events", null,
                        ContentValues(provider.row(21098)!!).apply { put(Events._ID, 30000) })
                    "delete" -> provider.db.delete("Events", "_id=21098", null)
                    else -> provider.db.execSQL("UPDATE Events SET _id=30000 WHERE _id=21096")
                }
            }
            assertFalse(change, repair(rows))
            assertTrue(ShadowLog.getLogsForTag("KeepCalSyncRepair").isEmpty())
        }
    }

    @Test fun refusesPatternChangesAndDoesNotRelaxSplitPrefixRestrictions() {
        for (rule in listOf("FREQ=WEEKLY;COUNT=7", "FREQ=DAILY;INTERVAL=2;COUNT=7")) {
            assertThrows(IllegalArgumentException::class.java) { repair(rule = rule) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecentRecurrenceRepair.apply(resolver, "7", listOf("21096", "900"),
                RecentRecurrenceRepair.read(resolver, "7", listOf("21096", "900")),
                "21096", "FREQ=DAILY;COUNT=7")
        }
        assertTrue(ShadowLog.getLogsForTag("KeepCalSyncRepair").isEmpty())
    }
}
