package com.builttoroam.devicecalendar

/** Optional host-owned admission fence on a native result receiver. It must
 * return immediately or throw before I/O. Normal unhosted plugin calls are
 * unchanged. Retirement rejects queued work; it does not cancel an already
 * invoked provider operation or certify its rollback. */
interface CalendarInvocationFence {
    fun checkCalendarInvocation()
}
