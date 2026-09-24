package com.builttoroam.devicecalendar

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteQueryBuilder
import android.provider.CalendarContract.Events
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RecurrenceRuleStorageGuardTest {
    /** Execute the actual selection, including Android's strict grammar;
     * expected outcomes come from the declared rules, not the helper's output. */
    private fun compare(expected: String?, observed: String?, matches: Boolean) {
        assertEquals("preflight: $expected / $observed", matches,
            RecurrenceRuleStorageGuard.equivalent(expected, observed))
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE Events (_id INTEGER PRIMARY KEY, rrule TEXT)")
            db.insertOrThrow("Events", null, ContentValues().apply { put("rrule", observed) })
            val guard = RecurrenceRuleStorageGuard.selection(expected)
            val query = SQLiteQueryBuilder().apply {
                tables = "Events"
                setProjectionMap(mapOf("_id" to "_id", "rrule" to "rrule"))
                setStrict(true); setStrictColumns(true); setStrictGrammar(true)
            }
            query.query(db, arrayOf("_id"), guard.sql, guard.args.toTypedArray(),
                null, null, null).use {
                assertEquals("SQL: $expected / $observed", if (matches) 1 else 0, it.count)
            }
        }
    }

    @Test fun defaultsPartOrderAndCaseAreEquivalentInBothDirections() {
        val rules = listOf(
            "FREQ=DAILY;COUNT=7",
            "FREQ=DAILY;COUNT=7;WKST=MO",
            "INTERVAL=1;COUNT=7;FREQ=DAILY",
            "WKST=MO;COUNT=7;INTERVAL=1;FREQ=DAILY",
            "freq=daily;count=7;wkst=mo;interval=1"
        )
        rules.forEach { expected -> rules.forEach { observed -> compare(expected, observed, true) } }
    }
    @Test fun everyNonDefaultPartStillGuardsTheDefinition() {
        val baseline = "FREQ=DAILY;COUNT=7"
        listOf(
            "FREQ=DAILY;COUNT=8;WKST=MO",
            "FREQ=WEEKLY;COUNT=7;WKST=MO",
            "FREQ=DAILY;COUNT=7;WKST=SU",
            "FREQ=DAILY;COUNT=7;INTERVAL=2",
            "FREQ=DAILY;COUNT=7;BYDAY=MO",
            "FREQ=DAILY;UNTIL=20260914T184500Z",
            "FREQ=DAILY", null, ""
        ).forEach { other ->
            compare(baseline, other, false); compare(other, baseline, false)
        }
        compare("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,SU;WKST=SU",
            "WKST=SU;BYDAY=MO,SU;INTERVAL=2;FREQ=WEEKLY", true)
        compare("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,SU;WKST=SU",
            "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,SU;WKST=MO", false)
    }
    @Test fun extraMalformedOrDuplicatePartsNeverSlipThrough() {
        val baseline = "FREQ=DAILY;COUNT=7"
        listOf(
            "$baseline;COUNT=7", "$baseline;COUNT=8", "$baseline;FREQ=WEEKLY",
            "$baseline;WKST=MO;WKST=MO", "$baseline;WKST=MO;WKST=SU",
            "$baseline;INTERVAL=1;INTERVAL=1", "$baseline;INTERVAL=1;INTERVAL=2",
            "$baseline;X-FOO=1", "$baseline;BYHOUR=7", "$baseline;",
            ";$baseline", "$baseline;;WKST=MO", "X=$baseline", "$baseline ;WKST=MO",
            "$baseline;WKST=MO\u0000;COUNT=9"
        ).forEach { other -> compare(baseline, other, false); compare(other, baseline, false) }
        compare("FREQ=DAILY;WKST=SU", "FREQ=DAILY;WKST=SU;WKST=MO", false)
        compare("FREQ=DAILY;INTERVAL=2", "FREQ=DAILY;INTERVAL=2;INTERVAL=1", false)
    }
    @Test fun opaqueInvalidAndAbsentRulesRemainExactOnly() {
        compare(null, null, true); compare(null, "", false); compare("", null, false)
        compare("", "", true)
        compare("FREQ=DAILY;X-FOO=1", "FREQ=DAILY;X-FOO=1", true)
        compare("FREQ=DAILY;X-FOO=1", "FREQ=DAILY;X-FOO=1;WKST=MO", false)
        compare("COUNT=7", "COUNT=7;WKST=MO", false)
        compare("FREQ=DAILY;COUNT=0", "FREQ=DAILY;COUNT=0;WKST=MO", false)
    }
    @Test fun copiedRowGuardAllowsOnlyRecurrenceRepresentationNotOtherFields() {
        val provider = TombstoneCalendarProvider().also { it.onCreate() }
        try {
            provider.seed(1788806700000, 7, false, false)
            provider.db.execSQL("UPDATE Events SET rrule='FREQ=DAILY;COUNT=7;WKST=MO' WHERE _id=21096")
            val snapshot = ContentValues().apply {
                put(Events.RRULE, "FREQ=DAILY;COUNT=7"); put(Events.TITLE, "test-rec")
            }
            val guard = calendarProviderRowGuard(Events.CONTENT_URI, "21096",
                "calendar_id=? AND deleted!=1", arrayOf("7"), snapshot)
            provider.applyBatch(arrayListOf(guard))
            assertEquals("Guard must not mutate the caller snapshot",
                "FREQ=DAILY;COUNT=7", snapshot.getAsString(Events.RRULE))
            provider.db.execSQL("UPDATE Events SET title='remote edit' WHERE _id=21096")
            assertThrows(android.content.OperationApplicationException::class.java) {
                provider.applyBatch(arrayListOf(guard))
            }
        } finally { provider.db.close() }
    }
}
