package com.builttoroam.devicecalendar

import android.content.ContentResolver
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.OperationApplicationException
import android.os.SystemClock
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.dmfs.rfc5545.recur.RecurrenceRule

/** Narrow compare-and-set operation, never a split/reset/save implementation.
 * Full related-row coverage is read by native identity, not by loaded UI dates.
 * Every assertion and the recurrence update runs in one provider batch.
 * An existing prefix repeats its guarded DTSTART to invalidate Instances.
 */
internal object RecentRecurrenceRepair {
    val columns = arrayOf(Events._ID, Events.CALENDAR_ID, Events.DTSTART,
        Events.DTEND, Events.DURATION, Events.ALL_DAY, Events.EVENT_TIMEZONE,
        Events.EVENT_END_TIMEZONE, Events.RRULE, Events.RDATE, Events.EXRULE,
        Events.EXDATE, Events.ORIGINAL_ID, Events.ORIGINAL_SYNC_ID,
        Events.ORIGINAL_INSTANCE_TIME, Events.ORIGINAL_ALL_DAY, Events.STATUS,
        Events.DELETED, Events._SYNC_ID)

    private fun selection(ids: List<String>, syncIds: List<String>): Pair<String, Array<String>> {
        require(ids.isNotEmpty() && ids.size <= 64 && ids.distinct().size == ids.size)
        val marks = ids.joinToString(",") { "?" }
        val sync = if (syncIds.isEmpty()) "" else
            " OR ${Events.ORIGINAL_SYNC_ID} IN (${syncIds.joinToString(",") { "?" }})"
        return Pair("${Events.CALENDAR_ID} = ? AND (${Events._ID} IN ($marks) OR " +
            "${Events.ORIGINAL_ID} IN ($marks)$sync)", (ids + ids + syncIds).toTypedArray())
    }

    fun read(resolver: ContentResolver, calendar: String, ids: List<String>): List<Map<String, String?>> {
        require(calendar.isNotBlank())
        require(ids.isNotEmpty() && ids.size <= 64 && ids.distinct().size == ids.size)
        val roots = query(resolver, "${Events.CALENDAR_ID} = ? AND ${Events._ID} IN " +
            "(${ids.joinToString(",") { "?" }})", (listOf(calendar) + ids).toTypedArray())
        val syncIds = roots.mapNotNull { it[Events._SYNC_ID] }.distinct()
        val (where, args) = selection(ids, syncIds)
        return query(resolver, where, arrayOf(calendar, *args))
    }

    private fun query(resolver: ContentResolver, where: String, args: Array<String>): List<Map<String, String?>> {
        val result = mutableListOf<Map<String, String?>>()
        checkNotNull(resolver.query(Events.CONTENT_URI, columns, where, args, "${Events._ID} ASC")) {
            "CalendarProvider did not return complete repair evidence"
        }.use { cursor ->
            while (cursor.moveToNext()) {
                check(result.size < 256) { "Repair evidence exceeds bounded capacity" }
                result.add(columns.mapIndexed { i, column ->
                    column to if (cursor.isNull(i)) null else cursor.getString(i)
                }.toMap())
            }
        }
        return result
    }

    fun apply(resolver: ContentResolver, calendar: String, roots: List<String>,
              rows: List<Map<String, String?>>, target: String, rule: String,
              repairId: String = "unavailable", debugLoggingEnabled: Boolean = false,
              kind: String = "split-prefix"): Boolean {
        require(kind == "split-prefix" || kind == "recurrence-creation")
        require(roots.size >= (if (kind == "split-prefix") 2 else 1) &&
            target in roots && rows.isNotEmpty() && rows.size <= 256)
        require(rows.all { it.keys == columns.toSet() && it[Events.CALENDAR_ID] == calendar })
        require(rows.map { it[Events._ID] }.distinct().size == rows.size)
        require(roots.all { id -> rows.any { it[Events._ID] == id && it[Events.DELETED] == "0" } })
        val root = rows.single { it[Events._ID] == target }
        require(root[Events.ORIGINAL_ID] == null && root[Events.ORIGINAL_SYNC_ID] == null)
        require(rule.isNotBlank() && rule.length <= 8192 && rule != root[Events.RRULE])
        val newRule = RecurrenceRule(rule)
        val createRange = if (kind == "recurrence-creation") {
            // A later local split can leave the original created root with a
            // surviving successor. Guard that whole known structure while
            // restoring only the original root's recurrence storage.
            require(rows.size == roots.size && root[Events.RRULE] == null)
            require(rows.filter { it[Events._ID] != target }.all {
                it[Events.ORIGINAL_ID] == null &&
                    it[Events.ORIGINAL_SYNC_ID] == null &&
                    it[Events.RRULE] != null &&
                    it[Events.DELETED] == "0" &&
                    it[Events.STATUS] != "2"
            })
            require(root[Events.RDATE] == null && root[Events.EXRULE] == null && root[Events.EXDATE] == null)
            val start = root[Events.DTSTART]?.toLongOrNull()
            val storedEnd = root[Events.DTEND]?.toLongOrNull()
            val storedDuration = parseDurationMillis(root[Events.DURATION])
            require((storedEnd != null && root[Events.DURATION] == null) ||
                (storedEnd == null && storedDuration != null &&
                    root[Events.EVENT_END_TIMEZONE] == null))
            val end = when {
                start == null -> null
                storedEnd != null -> storedEnd
                storedDuration != null -> Math.addExact(start, storedDuration)
                else -> null
            }
            require(start != null && end != null && end > start &&
                (end - start) % 1000L == 0L)
            Pair(start, end)
        } else {
            require(root[Events.RRULE] != null)
            val oldRule = RecurrenceRule(root[Events.RRULE]!!)
            fun pattern(raw: String) = raw.uppercase().split(';').filterNot {
                it.startsWith("COUNT=") || it.startsWith("UNTIL=") || it == "WKST=MO" || it == "INTERVAL=1"
            }
            require(pattern(oldRule.toString()).sorted() == pattern(newRule.toString()).sorted()) {
                "A repair cannot change the recurrence pattern"
            }
            val oldCount = oldRule.count ?: 0
            val newCount = newRule.count ?: 0
            if (oldCount > 0) {
                require(newCount in 1 until oldCount) { "Repair must strictly shorten the prefix" }
            } else {
                val until = newRule.until
                require(newCount <= 0 && until != null &&
                    (oldRule.until == null || until.timestamp < oldRule.until.timestamp)) {
                    "Repair must strictly shorten the prefix"
                }
            }
            null
        }
        val syncIds = rows.filter { it[Events._ID] in roots }.mapNotNull { it[Events._SYNC_ID] }.distinct()
        val (where, args) = selection(roots, syncIds)
        val operations = arrayListOf<ContentProviderOperation>()
        // Count plus exact row assertions detects insertions, removals and
        // identity changes, including an exception outside the visible week.
        operations.add(ContentProviderOperation.newAssertQuery(Events.CONTENT_URI)
            .withSelection(where, arrayOf(calendar, *args)).withExpectedCount(rows.size).build())
        rows.forEach { row ->
            val values = ContentValues().apply {
                row.forEach { (column, value) -> if (value == null) putNull(column) else put(column, value) }
            }
            operations.add(ContentProviderOperation.newAssertQuery(Events.CONTENT_URI)
                .withSelection("${Events._ID} = ? AND ${Events.CALENDAR_ID} = ?",
                    arrayOf(row[Events._ID]!!, calendar))
                .withValues(values).withExpectedCount(1).build())
        }
        val values = if (createRange != null) ContentValues().apply {
            val (start, end) = createRange
            // These are the minimal CalendarProvider fields for recurrence
            // creation. DTSTART is repeated to invalidate generated Instances.
            recurrenceStorageChanges(rule, start, root[Events.EVENT_TIMEZONE], end,
                root[Events.EVENT_END_TIMEZONE], root[Events.ALL_DAY] == "1",
                "P${(end - start) / 1000L}S").forEach { (column, value) ->
                when (value) {
                    null -> putNull(column)
                    is String -> put(column, value)
                    is Long -> put(column, value)
                    is Int -> put(column, value)
                    else -> error("Unsupported recurrence value for $column")
                }
            }
        } else recurrencePrefixStorageChanges(rule, requireNotNull(root[Events.DTSTART]?.toLongOrNull()))
        operations.add(ContentProviderOperation.newUpdate(Events.CONTENT_URI)
            .withSelection("${Events._ID} = ? AND ${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 0",
                arrayOf(target, calendar))
            .withValues(values).withExpectedCount(1).build())
        return try {
            resolver.applyBatch(CalendarContract.AUTHORITY, operations)
            // Intentionally NOT gated by debugLoggingEnabled/BuildConfig:
            // this is a risky automatic write, not an ordinary save. Log the
            // actual native commit before a Dart/engine reply can be lost.
            // Never let a failed diagnostic turn a commit into a failed save.
            runCatching {
                fun token(value: String) = value.take(120).replace(Regex("[^A-Za-z0-9_.:-]"), "?")
                Log.i("KeepCalSyncRepair", "applied " + JSONObject(mapOf(
                    "repairId" to token(repairId), "calendar" to token(calendar),
                    "root" to token(target), "field" to "rrule",
                    "operation" to if (kind == "recurrence-creation")
                        "restore-recurrence-creation" else "restore-split-prefix"
                )).toString())
            }
            true
        } catch (failure: OperationApplicationException) {
            debugFailure(debugLoggingEnabled, "rejected", calendar, target, repairId, failure)
            false
        } catch (failure: Throwable) {
            debugFailure(debugLoggingEnabled, "uncertain", calendar, target, repairId, failure)
            throw failure
        }
    }

    private fun debugFailure(enabled: Boolean, outcome: String, calendar: String,
                             target: String, repairId: String, failure: Throwable) {
        if (!enabled) return
        runCatching {
            Log.w("KeepCalSyncRepairDebug", "$outcome " + JSONObject(mapOf(
                "repairId" to repairId, "calendar" to calendar, "root" to target
            )).toString(), failure)
        }
    }

    private fun clock(context: Context): Map<String, Any> {
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        val device = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return mapOf("epoch" to if (boot < 0 || device.isNullOrBlank()) "" else "$device:$boot",
            "us" to SystemClock.elapsedRealtimeNanos() / 1000)
    }

    /** Uses the same native lane and completion lifetime as ordinary writes.
     * No Activity, UI-thread query, permission dialog or cloud readiness gate.
     */
    fun handle(context: Context, call: MethodCall, result: MethodChannel.Result,
               debugLoggingEnabled: Boolean = false) {
        @Suppress("UNCHECKED_CAST")
        val input = snapshotCalendarArguments(call.arguments as? Map<String, Any?> ?: emptyMap())
        CalendarWriteExecutor.submit(result, {}) { reply ->
            if (call.method == "recentRecurrenceClock") {
                reply.success(clock(context))
            } else {
                val calendar = input["calendarId"] as String
                @Suppress("UNCHECKED_CAST")
                val roots = input["roots"] as List<String>
                if (call.method == "readRecurrenceRepairSnapshot") {
                    val rows = read(context.contentResolver, calendar, roots)
                    reply.success(mapOf("rows" to rows, "instant" to clock(context)))
                } else {
                    val now = clock(context)
                    val deadline = (input["deadlineUs"] as Number).toLong()
                    if (now["epoch"] == "" || now["epoch"] != input["epoch"] ||
                        (now["us"] as Long) >= deadline) {
                        reply.success("expired")
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        val rows = input["rows"] as List<Map<String, String?>>
                        reply.success(if (apply(context.contentResolver, calendar, roots, rows,
                            input["target"] as String, input["rule"] as String,
                            input["repairId"] as? String ?: "unavailable",
                            debugLoggingEnabled || input["debugLoggingEnabled"] == true,
                            input["kind"] as? String ?: "split-prefix")) "updated" else "conflict")
                    }
                }
            }
        }
    }
}
