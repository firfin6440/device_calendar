package com.builttoroam.devicecalendar

import android.database.sqlite.SQLiteException
import com.builttoroam.devicecalendar.common.ErrorCodes

internal class CalendarAtomicWriteRejected(cause: SQLiteException) : RuntimeException(cause.message, cause) {
    val code: String = ErrorCodes.ATOMIC_WRITE_REJECTED
}

/**
 * Only preparation and a single atomic CalendarProvider batch belong here.
 * A SQLite rejection of that transaction cannot commit a partial write.
 * Do NOT include decoding/returning the result: an error after commit is not
 * rollback evidence. Remote/transport failures remain outcome-unknown too.
 */
internal fun <T> runAtomicCalendarWrite(write: () -> T): T = try {
    write()
} catch (error: SQLiteException) {
    throw CalendarAtomicWriteRejected(error)
}
