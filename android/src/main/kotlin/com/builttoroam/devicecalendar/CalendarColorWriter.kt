package com.builttoroam.devicecalendar

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Colors

/** A calendar palette key is scoped to account name, account type and colour type.
 * Assert the opening metadata in the same transaction as the write: a reused ID
 * or an external edit must not change an unrelated calendar. No event rows change.
 */
internal class CalendarColorWriter(private val resolver: ContentResolver) {
    fun update(id: Long, key: String?, accountName: String, accountType: String,
               oldColor: Int?, oldKey: String?, rawColor: Int? = null): Map<String, Any?> {
        require(id > 0 && (key == null) != (rawColor == null) && key?.isEmpty() != true)
        val uri = ContentUris.withAppendedId(Calendars.CONTENT_URI, id)
        val selection = "${Colors.COLOR_TYPE} = ? AND ${Colors.ACCOUNT_NAME} = ? AND ${Colors.ACCOUNT_TYPE} = ?" +
            if (key == null) "" else " AND ${Colors.COLOR_KEY} = ?"
        val args = arrayOf(Colors.TYPE_CALENDAR.toString(), accountName, accountType) +
            if (key == null) emptyArray<String>() else arrayOf(key)
        val expectedColors = if (key == null) 0 else 1
        val palette = resolver.query(Colors.CONTENT_URI, arrayOf(Colors.COLOR_KEY), selection, args, null)
        checkNotNull(palette) { "Calendar palette unavailable" }.use {
            check(it.count == expectedColors) { "Calendar colour selection is no longer available" }
        }
        val paletteAssert = ContentProviderOperation.newAssertQuery(Colors.CONTENT_URI)
            .withSelection(selection, args).withExpectedCount(expectedColors).build()
        val assert = ContentProviderOperation.newAssertQuery(uri)
            .withValue(Calendars.ACCOUNT_NAME, accountName)
            .withValue(Calendars.ACCOUNT_TYPE, accountType)
            .withValue(Calendars.CALENDAR_COLOR, oldColor)
            .withValue(Calendars.CALENDAR_COLOR_KEY, oldKey)
            .withExpectedCount(1).build()
        val updateBuilder = ContentProviderOperation.newUpdate(uri)
            .withValue(Calendars.CALENDAR_COLOR_KEY, key)
            .withExpectedCount(1)
        if (rawColor != null) updateBuilder.withValue(Calendars.CALENDAR_COLOR, rawColor)
        val update = updateBuilder.build()
        resolver.applyBatch(CalendarContract.AUTHORITY, arrayListOf(paletteAssert, assert, update))
        // An exception here may follow a committed write. Never infer rollback
        // or retry the write simply because the readback/reply was lost.
        val cursor = resolver.query(uri, arrayOf(Calendars.ACCOUNT_NAME,
            Calendars.ACCOUNT_TYPE, Calendars.CALENDAR_COLOR, Calendars.CALENDAR_COLOR_KEY),
            null, null, null)
        return checkNotNull(cursor) { "Calendar colour readback unavailable" }.use {
            check(it.count == 1 && it.moveToFirst()) { "Calendar disappeared after update" }
            check(it.getString(0) == accountName && it.getString(1) == accountType) {
                "Calendar identity changed after update"
            }
            val color = if (it.isNull(2)) null else it.getInt(2)
            val savedKey = if (it.isNull(3)) null else it.getString(3)
            val applied = if (key != null) savedKey == key && color != null
                else savedKey.isNullOrEmpty() && color == rawColor
            mapOf("applied" to applied,
                "color" to color, "colorKey" to savedKey)
        }
    }
}
