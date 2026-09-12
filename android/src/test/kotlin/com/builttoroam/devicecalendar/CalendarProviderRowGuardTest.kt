package com.builttoroam.devicecalendar

import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentValues
import android.content.OperationApplicationException
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteQueryBuilder
import android.net.Uri
import android.provider.CalendarContract
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CalendarProviderRowGuardTest {
    private lateinit var provider: JoinedCalendarProvider
    @Before fun setup() { provider = JoinedCalendarProvider(); provider.onCreate() }
    @After fun close() { provider.db.close() }

    private fun guard(table: String, id: String = "1", owner: String = "20938",
                      value: String = "old"): ContentProviderOperation = calendarProviderRowGuard(
        Uri.parse("content://com.android.calendar/${table.lowercase()}"), id,
        "event_id = ?", arrayOf(owner), ContentValues().apply { put("payload", value) })

    @Test fun attendeeGuardExecutesAgainstTheRecordedThreeTableJoin() {
        val result = provider.applyBatch(arrayListOf(guard("Attendees")))
        assertEquals(1, result.single().count)
    }

    @Test fun reminderGuardExecutesAgainstJoinedEventsAndCalendars() {
        assertEquals(1, provider.applyBatch(arrayListOf(guard("Reminders"))).single().count)
    }

    @Test fun duplicatePayloadDoesNotReplaceRowIdentityOrOwnership() {
        for (table in listOf("Attendees", "Reminders")) {
            assertThrows(OperationApplicationException::class.java) {
                provider.applyBatch(arrayListOf(guard(table, id = "2")))
            }
            assertThrows(OperationApplicationException::class.java) {
                provider.applyBatch(arrayListOf(guard(table, owner = "999")))
            }
        }
    }

    @Test fun staleGuardRollsBackEarlierWritesInTheSameTransaction() {
        val update = ContentProviderOperation.newUpdate(CalendarContract.Events.CONTENT_URI)
            .withSelection("_id = ?", arrayOf("20938"))
            .withValue("dtstart", "21:45").withExpectedCount(1).build()
        assertThrows(OperationApplicationException::class.java) {
            provider.applyBatch(arrayListOf(update, guard("Attendees", value = "stale")))
        }
        assertEquals("20:45", provider.start())
    }

    @Test fun validGuardsAllowThe2145BatchToCommit() {
        val update = ContentProviderOperation.newUpdate(CalendarContract.Events.CONTENT_URI)
            .withSelection("_id = ?", arrayOf("20938"))
            .withValue("dtstart", "21:45").withExpectedCount(1).build()
        provider.applyBatch(arrayListOf(guard("Attendees"), guard("Reminders"), update))
        assertEquals("21:45", provider.start())
    }

    @Test fun oldUnqualifiedPredicateReproducesTheExactSqlFailure() {
        val old = ContentProviderOperation.newAssertQuery(CalendarContract.Attendees.CONTENT_URI)
            .withSelection("_id = ? AND (event_id = ?)", arrayOf("1", "20938"))
            .withValue("payload", "old").withExpectedCount(1).build()
        val error = assertThrows(SQLiteException::class.java) { provider.applyBatch(arrayListOf(old)) }
        assertTrue(error.message!!.contains("ambiguous column name: _id"))
    }

    @Test fun sqlRejectionHasAnExplicitNoCommitReceiptAndRollsBackTheBatch() {
        val update = ContentProviderOperation.newUpdate(CalendarContract.Events.CONTENT_URI)
            .withSelection("_id = ?", arrayOf("20938"))
            .withValue("dtstart", "21:45").withExpectedCount(1).build()
        val invalid = ContentProviderOperation.newAssertQuery(CalendarContract.Attendees.CONTENT_URI)
            .withSelection("_id = ? AND (event_id = ?)", arrayOf("1", "20938"))
            .withValue("payload", "old").withExpectedCount(1).build()
        val rejection = assertThrows(CalendarAtomicWriteRejected::class.java) {
            runAtomicCalendarWrite { provider.applyBatch(arrayListOf(update, invalid)) }
        }
        assertEquals("422", rejection.code)
        assertTrue(rejection.cause is SQLiteException)
        assertEquals("20:45", provider.start())
    }

    @Test fun aLostReplyDoesNotBecomeAFalseRollbackReceipt() {
        val error = android.os.RemoteException("reply lost")
        assertSame(error, assertThrows(android.os.RemoteException::class.java) {
            runAtomicCalendarWrite { throw error }
        })
    }

    @Test fun resultProcessingAfterCommitIsOutsideTheRejectionBoundary() {
        val update = ContentProviderOperation.newUpdate(CalendarContract.Events.CONTENT_URI)
            .withSelection("_id = ?", arrayOf("20938"))
            .withValue("dtstart", "21:45").withExpectedCount(1).build()
        val error = SQLiteException("post-commit result processing failed")
        assertSame(error, assertThrows(SQLiteException::class.java) {
            runAtomicCalendarWrite { provider.applyBatch(arrayListOf(update)) }
            throw error
        })
        assertEquals("21:45", provider.start())
    }
}

/** Real SQLite, with the join from the recorded Samsung error, not a canned query reply. */
internal class JoinedCalendarProvider : ContentProvider() {
    lateinit var db: SQLiteDatabase
    override fun onCreate(): Boolean {
        db = SQLiteDatabase.create(null)
        db.execSQL("CREATE TABLE Calendars (_id INTEGER PRIMARY KEY)")
        db.execSQL("CREATE TABLE Events (_id INTEGER PRIMARY KEY, calendar_id INTEGER, dtstart TEXT)")
        db.execSQL("INSERT INTO Calendars VALUES (7)")
        db.execSQL("INSERT INTO Events VALUES (20938, 7, '20:45'), (999, 7, '18:45')")
        for (table in listOf("Attendees", "Reminders")) {
            db.execSQL("CREATE TABLE $table (_id INTEGER PRIMARY KEY, event_id INTEGER, payload TEXT)")
            db.execSQL("INSERT INTO $table VALUES (1, 20938, 'old'), (2, 999, 'old')")
        }
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val table = when (uri.pathSegments.first()) {
            "attendees" -> "Attendees"
            "reminders" -> "Reminders"
            else -> "Events"
        }
        val builder = SQLiteQueryBuilder()
        if (table == "Events") builder.tables = table else {
            builder.tables = "$table, Events, Calendars"
            builder.appendWhere("Events._id=$table.event_id AND Events.calendar_id=Calendars._id")
            // CalendarProvider maps the public ID projection, but does not
            // rewrite arbitrary caller WHERE expressions.
            builder.setProjectionMap(mapOf("_id" to "$table._id AS _id",
                "event_id" to "$table.event_id", "payload" to "$table.payload"))
        }
        return builder.query(db, projection, selection, selectionArgs, null, null, sortOrder)
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = db.update("Events", values, selection, selectionArgs)
    override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
        db.beginTransaction()
        try {
            val result = super.applyBatch(operations)
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }
    fun start(): String = db.rawQuery("SELECT dtstart FROM Events WHERE _id=20938", null).use {
        it.moveToFirst(); it.getString(0)
    }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri = error("Unexpected insert")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Unexpected delete")
}
