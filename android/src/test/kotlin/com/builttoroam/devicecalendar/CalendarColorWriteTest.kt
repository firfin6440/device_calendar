package com.builttoroam.devicecalendar

import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentValues
import android.content.OperationApplicationException
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Colors
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

/** Production writer with real assertion operations and SQL transactions.
 * Provider emulates CalendarProvider's palette lookup/colour recalculation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CalendarColorWriteTest {
    private lateinit var provider: PaletteProvider
    private lateinit var writer: CalendarColorWriter
    private val oldColor = 0xffabcdef.toInt()
    private val newColor = 0xff123456.toInt()

    @Before fun setup() {
        provider = PaletteProvider()
        provider.onCreate()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        writer = CalendarColorWriter(RuntimeEnvironment.getApplication().contentResolver)
    }
    @After fun close() { provider.db.close() }
    private fun save(key: String = "0", owner: String = "owner", type: String = "com.google",
                     color: Int? = oldColor, oldKey: String? = null) =
        writer.update(7, key, owner, type, color, oldKey)
    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("Expected rejected write") } catch (_: Exception) { }
    }

    @Test fun noPaletteAcceptsRawColorWithExactReadbackAndClearedKey() {
        provider.db.delete("colors", "${Colors.COLOR_TYPE}=?", arrayOf(Colors.TYPE_CALENDAR.toString()))
        val result = writer.update(7, null, "owner", "com.google", oldColor, null, newColor)
        assertEquals(mapOf("applied" to true, "color" to newColor, "colorKey" to null), result)
        assertEquals(setOf(Calendars.CALENDAR_COLOR_KEY, Calendars.CALENDAR_COLOR), provider.writtenColumns)
    }
    @Test fun rawColorCannotBypassCalendarPaletteOrNewlyAddedPalette() {
        expectFailure { writer.update(7, null, "owner", "com.google", oldColor, null, newColor) }
        assertEquals(0, provider.updates)
        provider.db.delete("colors", null, null)
        provider.beforeBatch = { provider.db.execSQL("INSERT INTO colors VALUES ('owner','com.google',?,?,?)",
            arrayOf<Any>(Colors.TYPE_CALENDAR, "new", newColor)) }
        expectFailure { writer.update(7, null, "owner", "com.google", oldColor, null, newColor) }
        assertEquals(oldColor, provider.color())
        assertEquals(0, provider.updates)
    }

    @Test fun textAndZeroKeysSaveAndReadActualProviderColor() {
        val saved = save()
        assertEquals(mapOf("applied" to true, "color" to newColor, "colorKey" to "0"), saved)
        assertEquals(newColor, provider.color())
        assertEquals("0", provider.key())
        assertEquals(1, provider.updates)
        assertEquals(setOf(Calendars.CALENDAR_COLOR_KEY), provider.writtenColumns)
        assertEquals(mapOf("applied" to true, "color" to newColor, "colorKey" to "brand-blue"),
            save("brand-blue", color = newColor, oldKey = "0"))
    }
    @Test fun wrongAccountTypeCannotBorrowPaletteForSameAccountName() {
        expectFailure { save(type = "samsung") }
        assertEquals(0, provider.updates)
        assertEquals(oldColor, provider.color())
    }
    @Test fun eventPaletteKeyDoesNotAuthorizeCalendarColor() {
        expectFailure { save("event-only") }
        assertEquals(0, provider.updates)
    }
    @Test fun recycledIdIsRejectedAtomicallyEvenWithValidAccountPalette() {
        provider.beforeBatch = { provider.db.execSQL("UPDATE calendars SET ${Calendars.ACCOUNT_NAME}='other'") }
        expectFailure { save() }
        assertEquals(0, provider.updates)
        assertEquals(oldColor, provider.color())
    }
    @Test fun externalColorEditBetweenPaletteReadAndBatchIsPreserved() {
        provider.beforeBatch = { provider.db.execSQL("UPDATE calendars SET ${Calendars.CALENDAR_COLOR}=123") }
        expectFailure { save() }
        assertEquals(0, provider.updates)
        assertEquals(123, provider.color())
    }
    @Test fun paletteRemovedBeforeWriteDoesNotClearCalendarColor() {
        provider.beforeBatch = { provider.db.delete("colors", null, null) }
        expectFailure { save() }
        assertEquals(oldColor, provider.color())
        assertNull(provider.key())
    }
    @Test fun nullPaletteCursorIsFailureRatherThanNoColors() {
        provider.nullPalette = true
        expectFailure { save() }
        assertEquals(0, provider.updates)
        val delegate = CalendarDelegate(null, RuntimeEnvironment.getApplication())
        expectFailure { delegate.retrieveCalendarColors("owner", "com.google") }
    }
    @Test fun rejectedReadbackIsNotSuccessful() {
        provider.reject = true
        val saved = save()
        assertEquals(false, saved["applied"])
        assertEquals(oldColor, saved["color"])
        assertNull(saved["colorKey"])
    }
    @Test fun readbackFailureAfterCommitDoesNotPretendRollback() {
        provider.nullReadback = true
        expectFailure { save() }
        assertEquals(newColor, provider.color())
        assertEquals("0", provider.key())
        assertEquals(1, provider.updates)
    }
    @Test fun missingCalendarAndZeroAffectedRowsDoNotSucceed() {
        provider.db.delete("calendars", null, null)
        expectFailure { save() }
        assertEquals(0, provider.updates)
    }

    private class PaletteProvider : ContentProvider() {
        lateinit var db: SQLiteDatabase
        var updates = 0
        var nullPalette = false
        var nullReadback = false
        var reject = false
        var beforeBatch: (() -> Unit)? = null
        var writtenColumns = emptySet<String>()

        override fun onCreate(): Boolean {
            db = SQLiteDatabase.create(null)
            db.execSQL("CREATE TABLE calendars (_id INTEGER PRIMARY KEY, ${Calendars.ACCOUNT_NAME} TEXT, ${Calendars.ACCOUNT_TYPE} TEXT, ${Calendars.CALENDAR_COLOR} INTEGER, ${Calendars.CALENDAR_COLOR_KEY} TEXT)")
            db.execSQL("CREATE TABLE colors (${Colors.ACCOUNT_NAME} TEXT, ${Colors.ACCOUNT_TYPE} TEXT, ${Colors.COLOR_TYPE} INTEGER, ${Colors.COLOR_KEY} TEXT, ${Colors.COLOR} INTEGER)")
            db.execSQL("INSERT INTO calendars VALUES (7, 'owner', 'com.google', ?, NULL)", arrayOf(0xffabcdef.toInt()))
            for (key in listOf("0", "brand-blue")) {
                db.execSQL("INSERT INTO colors VALUES ('owner','com.google',?,?,?)",
                    arrayOf<Any>(Colors.TYPE_CALENDAR, key, 0xff123456.toInt()))
            }
            db.execSQL("INSERT INTO colors VALUES ('owner','com.google',?,?,?)",
                arrayOf<Any>(Colors.TYPE_EVENT, "event-only", 0xff111111.toInt()))
            return true
        }
        fun color(): Int = db.rawQuery("SELECT ${Calendars.CALENDAR_COLOR} FROM calendars", null).use {
            check(it.moveToFirst()); it.getInt(0)
        }
        fun key(): String? = db.rawQuery("SELECT ${Calendars.CALENDAR_COLOR_KEY} FROM calendars", null).use {
            check(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0)
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                           selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            if (uri.pathSegments.first() == "colors") {
                if (nullPalette) return null
                return db.query("colors", projection, selection, selectionArgs, null, null, sortOrder)
            }
            if (nullReadback && updates > 0 && projection?.contains(Calendars.ACCOUNT_NAME) == true &&
                projection.contains(Calendars.CALENDAR_COLOR_KEY) && projection.size == 4) return null
            return db.query("calendars", projection, "_id = ?", arrayOf(uri.lastPathSegment), null, null, null)
        }
        override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
            beforeBatch?.invoke(); beforeBatch = null
            db.beginTransaction()
            try {
                val result = super.applyBatch(operations)
                db.setTransactionSuccessful()
                return result
            } finally { db.endTransaction() }
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?,
                            selectionArgs: Array<out String>?): Int {
            check(uri.pathSegments.first() == "calendars") { "Unexpected event write" }
            writtenColumns = values!!.keySet().toSet()
            val key = values.getAsString(Calendars.CALENDAR_COLOR_KEY)
            val rgb = if (key == null) values.getAsInteger(Calendars.CALENDAR_COLOR) else db.rawQuery("SELECT ${Colors.COLOR} FROM colors WHERE ${Colors.COLOR_TYPE}=? AND ${Colors.ACCOUNT_NAME}='owner' AND ${Colors.ACCOUNT_TYPE}='com.google' AND ${Colors.COLOR_KEY}=?",
                arrayOf(Colors.TYPE_CALENDAR.toString(), key)).use {
                if (!it.moveToFirst()) throw OperationApplicationException("Palette disappeared")
                it.getInt(0)
            }
            updates++
            if (reject) return 1
            val actual = ContentValues(values).apply { put(Calendars.CALENDAR_COLOR, rgb) }
            return db.update("calendars", actual, "_id = ?", arrayOf(uri.lastPathSegment))
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }
}
