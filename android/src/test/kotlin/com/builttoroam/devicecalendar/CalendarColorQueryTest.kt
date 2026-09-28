package com.builttoroam.devicecalendar

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Colors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CalendarColorQueryTest {
    @Test fun colorKeysAreTextAndAccountTypesDoNotLeakAcrossSharedNames() {
        val provider = ColorProvider()
        provider.onCreate()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val delegate = CalendarDelegate(null, RuntimeEnvironment.getApplication())

        // Samsung and Google can have the same account name, but the Colors
        // contract scopes a key to both account name and account type.
        assertEquals(emptyList<Pair<Int, String>>(),
            delegate.retrieveEventColors("shared", "com.osp.app.signin"))
        assertEquals(listOf(Pair(0xff123456.toInt(), "brand-blue"), Pair(0xff234567.toInt(), "0")),
            delegate.retrieveEventColors("shared", "com.google"))
        assertEquals(listOf(Pair(0xff345678.toInt(), "calendar-special")),
            delegate.retrieveCalendarColors("shared", "com.google"))
        assertTrue(provider.queries.all {
            it.first.contains("${Colors.ACCOUNT_TYPE} = ?") && it.second.size == 3
        })
    }

    private class ColorProvider : ContentProvider() {
        val queries = mutableListOf<Pair<String, List<String>>>()

        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                           selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = projection ?: arrayOf(Colors.COLOR, Colors.COLOR_KEY)
            val result = MatrixCursor(columns)
            val args = selectionArgs?.toList() ?: emptyList()
            queries.add(Pair(selection ?: "", args))
            if (args.size != 3 || args[1] != "shared" || args[2] != "com.google") return result
            val rows = if (args[0] == Colors.TYPE_EVENT.toString()) {
                listOf(Pair(0xff123456.toInt(), "brand-blue"), Pair(0xff234567.toInt(), "0"))
            } else {
                listOf(Pair(0xff345678.toInt(), "calendar-special"))
            }
            for ((color, key) in rows) {
                result.addRow(columns.map { if (it == Colors.COLOR) color as Any else key as Any }.toTypedArray())
            }
            return result
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?,
                            selectionArgs: Array<out String>?): Int = 0
    }
}
