package com.builttoroam.devicecalendar

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.CursorWrapper
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.CalendarContract
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

/** Actual SQLite predicates/projections and production child acquisition. Faults
 * model provider/cursor behavior, never a canned successful acquisition. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CalendarChildReadTest {
    private lateinit var provider: ChildrenProvider
    @Before fun setup() {
        provider = ChildrenProvider()
        provider.onCreate()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
    }
    @After fun close() {
        assertEquals(provider.opened, provider.closed)
        provider.db.close()
    }
    private fun read(ids: List<String>) = readCalendarChildren(
        RuntimeEnvironment.getApplication().contentResolver,
        CalendarContract.Reminders.CONTENT_URI,
        arrayOf("event_id", "minutes", "method"), ids
    ) { cursor ->
        val id = cursor.getString(0)
        if (id == provider.badParseId) throw IllegalArgumentException("Malformed child")
        cursor.getInt(1) to cursor.getInt(2)
    }
    @Test fun realSqlGroupsDistinctIdsAndRetainsDuplicateChildrenAndEmptyOwners() {
        for (id in 1..201) provider.insertChild(id, id * 2, 1)
        provider.insertChild(1, 2, 1) // Duplicate rows must not silently disappear.
        provider.insertChild(999, 7, 2)
        val result = read((1..202).map(Int::toString) + listOf("1"))
        assertEquals(listOf(100, 100, 2), provider.queries.map { it.size })
        assertEquals(202, result.rowsById.size)
        assertEquals(listOf(2 to 1, 2 to 1), result.rowsById["1"])
        assertEquals(listOf(402 to 1), result.rowsById["201"])
        assertEquals(emptyList<Pair<Int, Int>>(), result.rowsById["202"])
        assertFalse(result.rowsById.containsKey("999"))
        assertTrue(result.unreadableIds.isEmpty())
        assertEquals(3, result.queryCount)
        assertEquals(0, result.fallbackGroups)
    }
    @Test fun emptyIdsDoNotQuery() {
        assertTrue(read(emptyList()).rowsById.isEmpty())
        assertTrue(provider.queries.isEmpty())
    }
    @Test fun providerRejectingInFallsBackToCompleteSingleReads() {
        provider.rejectGroups = true
        provider.insertChild(1, 10, 1); provider.insertChild(2, 20, 2)
        val result = read(listOf("1", "2", "3"))
        assertEquals(listOf(10 to 1), result.rowsById["1"])
        assertEquals(listOf(20 to 2), result.rowsById["2"])
        assertEquals(emptyList<Pair<Int, Int>>(), result.rowsById["3"])
        assertTrue(result.unreadableIds.isEmpty())
        assertEquals(4, result.queryCount)
        assertEquals(1, result.fallbackGroups)
    }
    @Test fun incompleteGroupTailIsDiscardedBeforeIndividualRetry() = brokenGroup("move")
    @Test fun groupCloseFailureDoesNotCertifyMissingChildren() = brokenGroup("close")
    @Test fun groupNullCursorDoesNotCertifyMissingChildren() = brokenGroup("null")
    private fun brokenGroup(point: String) {
        provider.groupFailure = point
        provider.insertChild(1, 10, 1); provider.insertChild(2, 20, 2)
        provider.afterBrokenGroup = {
            provider.db.execSQL("UPDATE Reminders SET minutes=99 WHERE event_id=1")
        }
        val result = read(listOf("1", "2", "3"))
        assertEquals("Partially staged old rows must be discarded", listOf(99 to 1), result.rowsById["1"])
        assertEquals(listOf(20 to 2), result.rowsById["2"])
        assertEquals(emptyList<Pair<Int, Int>>(), result.rowsById["3"])
        assertTrue(result.unreadableIds.isEmpty())
    }
    @Test fun unreadableSingleOwnerNeverBecomesEmptyAndDoesNotPoisonOthers() {
        provider.insertChild(1, 10, 1); provider.insertChild(2, 20, 1)
        provider.failId = "2"
        val result = read(listOf("1", "2", "3"))
        assertEquals(setOf("2"), result.unreadableIds)
        assertFalse(result.rowsById.containsKey("2"))
        assertEquals(listOf(10 to 1), result.rowsById["1"])
        assertEquals(emptyList<Pair<Int, Int>>(), result.rowsById["3"])
        assertNotNull(result.firstFailure)
    }
    @Test fun malformedChildQuarantinesItsWholeOwnerAfterFallback() {
        provider.insertChild(1, 10, 1); provider.insertChild(2, 20, 1)
        provider.badParseId = "2"
        val result = read(listOf("1", "2", "3"))
        assertEquals(setOf("2"), result.unreadableIds)
        assertFalse(result.rowsById.containsKey("2"))
        assertEquals(listOf(10 to 1), result.rowsById["1"])
        assertEquals(emptyList<Pair<Int, Int>>(), result.rowsById["3"])
    }
    @Test fun permissionRevocationInGroupIsFatalWithoutFallback() {
        provider.security = true
        assertThrows(SecurityException::class.java) { read(listOf("1", "2")) }
        assertEquals(1, provider.queries.size)
    }
    @Test fun permissionRevocationInFallbackIsFatalWithoutFurtherQueries() {
        provider.rejectGroups = true; provider.securityId = "2"
        assertThrows(SecurityException::class.java) { read(listOf("1", "2", "3")) }
        assertEquals(listOf(listOf("1", "2", "3"), listOf("1"), listOf("2")), provider.queries)
    }
    @Test fun permissionFailureOnCloseIsNotHiddenByAnotherCursorFailure() {
        provider.insertChild(1, 10, 1)
        provider.groupFailure = "move"; provider.securityOnClose = true
        assertThrows(SecurityException::class.java) { read(listOf("1", "2")) }
        assertEquals(1, provider.queries.size)
    }
    @Test fun noChildResultsAreReusedAfterExternalEditOrFailedRead() {
        provider.insertChild(1, 10, 1); provider.insertChild(2, 20, 1)
        val first = read(listOf("1", "2"))
        provider.db.execSQL("DELETE FROM Reminders WHERE event_id=1")
        provider.db.execSQL("UPDATE Reminders SET minutes=30 WHERE event_id=2")
        provider.failId = "2"
        assertEquals(setOf("2"), read(listOf("1", "2")).unreadableIds)
        provider.failId = null
        val second = read(listOf("1", "2"))
        assertEquals(listOf(10 to 1), first.rowsById["1"])
        assertEquals(emptyList<Pair<Int, Int>>(), second.rowsById["1"])
        assertEquals(listOf(30 to 1), second.rowsById["2"])
    }
    @Test fun unexpectedOwnershipIsRejectedRatherThanAttachedToAnotherEvent() {
        provider.insertChild(999, 30, 1); provider.ignoreSelection = true
        val result = read(listOf("1", "2"))
        assertEquals(setOf("1", "2"), result.unreadableIds)
        assertTrue(result.rowsById.isEmpty())
    }
    @Test fun parameterBindingDoesNotInterpretAnIdAsSql() {
        provider.insertChild(1, 10, 1)
        val result = read(listOf("1 OR 1=1", "2"))
        assertEquals(2, result.rowsById.size)
        assertTrue(result.rowsById.values.all { it.isEmpty() })
    }
    private class ChildrenProvider : ContentProvider() {
        lateinit var db: SQLiteDatabase
        val queries = mutableListOf<List<String>>()
        var opened = 0; var closed = 0
        var rejectGroups = false
        var groupFailure: String? = null
        var failId: String? = null
        var badParseId: String? = null
        var security = false
        var securityId: String? = null
        var securityOnClose = false
        var ignoreSelection = false
        var afterBrokenGroup: (() -> Unit)? = null
        override fun onCreate(): Boolean {
            db = SQLiteDatabase.create(null)
            db.execSQL("CREATE TABLE Reminders (event_id INTEGER, minutes INTEGER, method INTEGER)")
            return true
        }
        fun insertChild(id: Int, minutes: Int, method: Int) {
            db.execSQL("INSERT INTO Reminders VALUES (?, ?, ?)", arrayOf(id, minutes, method))
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            val ids = selectionArgs!!.toList()
            queries.add(ids)
            val group = ids.size > 1
            if (security || !group && securityId in ids) throw SecurityException("permission revoked")
            if (rejectGroups && group || failId in ids) throw IllegalStateException("query failed")
            if (group && groupFailure == "null") { afterBrokenGroup?.invoke(); return null }
            val cursor = db.query("Reminders", projection,
                if (ignoreSelection) null else selection,
                if (ignoreSelection) null else selectionArgs, null, null, sortOrder)
            opened++
            return object : CursorWrapper(cursor) {
                override fun moveToNext(): Boolean {
                    if (group && groupFailure == "move" && position == 0) {
                        throw IllegalStateException("cursor lost its tail")
                    }
                    return super.moveToNext()
                }
                override fun close() {
                    super.close(); closed++
                    if (group && groupFailure != null) afterBrokenGroup?.invoke()
                    if (securityOnClose) throw SecurityException("permission revoked on close")
                    if (group && groupFailure == "close") throw IllegalStateException("close failed")
                }
            }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri = error("Unexpected write")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = error("Unexpected write")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = error("Unexpected write")
    }
}
