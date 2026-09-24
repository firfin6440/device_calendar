package com.builttoroam.devicecalendar

import android.provider.CalendarContract.Events
import org.dmfs.rfc5545.recur.RecurrenceRule
import java.util.Locale

/**
 * Equality for provider RRULE serialization, not equality of a loaded set of
 * occurrences. RFC 5545 §3.3.10 permits any rule-part order and defaults WKST to
 * MO and INTERVAL to 1. Other values, limits and BY* parts remain significant.
 *
 * Keep the same predicate in preflight and inside the provider transaction.
 * A Dart-only normalization cannot protect against sync rewriting the string
 * between a native read and applyBatch. This is NOT permission to retry a
 * rejected split or to drop the other row/ownership/child assertions.
 */
internal object RecurrenceRuleStorageGuard {
    private val defaults = linkedMapOf("WKST" to "MO", "INTERVAL" to "1")
    private val keys = setOf("FREQ", "UNTIL", "COUNT", "INTERVAL", "BYSECOND",
        "BYMINUTE", "BYHOUR", "BYDAY", "BYMONTHDAY", "BYYEARDAY", "BYWEEKNO",
        "BYMONTH", "BYSETPOS", "WKST")
    private val token = Regex("([A-Z]+)=([A-Z0-9,+-]+)")

    private fun parts(raw: String?): Map<String, String>? {
        if (raw == null || raw.isEmpty() || raw.length > 8192 || raw.any { it.code > 127 }) return null
        val result = linkedMapOf<String, String>()
        for (part in raw.uppercase(Locale.ROOT).split(';')) {
            val match = token.matchEntire(part) ?: return null
            val key = match.groupValues[1]
            if (key !in keys || result.put(key, match.groupValues[2]) != null) return null
        }
        if ("FREQ" !in result || ("COUNT" in result && "UNTIL" in result)) return null
        for (key in listOf("COUNT", "INTERVAL")) {
            if (key in result && (result[key]?.toLongOrNull() ?: 0L) <= 0L) return null
        }
        try { RecurrenceRule(raw.uppercase(Locale.ROOT)) } catch (_: Exception) { return null }
        defaults.forEach { (key, value) -> if (result[key] == value) result.remove(key) }
        return result
    }

    fun equivalent(left: String?, right: String?): Boolean {
        if (left == right) return true
        val a = parts(left) ?: return false
        val b = parts(right) ?: return false
        return a == b
    }

    data class Selection(val sql: String, val args: List<String>)

    fun selection(expected: String?): Selection {
        val column = Events.RRULE
        if (expected == null) return Selection("$column IS NULL", emptyList())
        val required = parts(expected) ?: return Selection("$column = ?", listOf(expected))
        val wrapped = "(';' || upper($column) || ';')"
        val predicates = mutableListOf("instr($column, char(0)) = 0")
        val args = mutableListOf<String>()
        required.forEach { (key, value) ->
            predicates.add("instr($wrapped, ?) > 0")
            args.add(";$key=$value;")
        }
        // Presence alone is unsafe: a changed/unknown/duplicate extra part
        // must reject the transaction. Count exactly the required distinct
        // parts plus at most one occurrence of each absent default part.
        val count = StringBuilder(required.size.toString())
        defaults.forEach { (key, value) ->
            if (key !in required) {
                count.append(" + (instr($wrapped, ';$key=$value;') > 0)")
            }
        }
        predicates.add("length($column) - length(replace($column, ';', '')) + 1 = ($count)")
        return Selection("(${predicates.joinToString(" AND ")})", args)
    }

    fun append(parts: MutableList<String>, args: MutableList<String>, expected: String?) {
        val guard = selection(expected)
        parts.add(guard.sql)
        args.addAll(guard.args)
    }
}
