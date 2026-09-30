package com.builttoroam.devicecalendar

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.util.Log
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.dmfs.rfc5545.recur.RecurrenceRule
import org.json.JSONArray
import org.json.JSONObject

/** Destructive repair requires full event content, including child tables.
 * Never use the smaller recurrence-only proof to authorize deletion.
 * All assertions and the minimal deletion effect execute in one transaction.
 */
internal object RecentDeletionRepair {
    val columns = (RecentRecurrenceRepair.columns.toList() + listOf(
        Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION, Events.EVENT_COLOR,
        Events.EVENT_COLOR_KEY, Events.ACCESS_LEVEL, Events.AVAILABILITY,
        Events.ORGANIZER, Events.SELF_ATTENDEE_STATUS, Events.HAS_ATTENDEE_DATA,
        Events.GUESTS_CAN_MODIFY, Events.GUESTS_CAN_INVITE_OTHERS, Events.GUESTS_CAN_SEE_GUESTS,
        Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI, Events.UID_2445,
        Events.DIRTY, Events.MUTATORS)).distinct().toTypedArray()
    private val tables = linkedMapOf(
        "@attendees" to CalendarContract.Attendees.CONTENT_URI,
        "@reminders" to CalendarContract.Reminders.CONTENT_URI,
        "@properties" to CalendarContract.ExtendedProperties.CONTENT_URI)
    private val childColumns = mapOf(
        "@attendees" to arrayOf("_id", "event_id", "attendeeName", "attendeeEmail", "attendeeRelationship", "attendeeType", "attendeeStatus", "attendeeIdentity", "attendeeIdNamespace"),
        "@reminders" to arrayOf("_id", "event_id", "minutes", "method"),
        "@properties" to arrayOf("_id", "event_id", "name", "value"))

    private fun query(resolver: ContentResolver, uri: Uri, projection: Array<String>,
                      where: String, args: Array<String>): List<Map<String, String?>> {
        val result = mutableListOf<Map<String, String?>>()
        var characters = 0
        checkNotNull(resolver.query(uri, projection, where, args, "_id ASC")).use { c ->
            while (c.moveToNext()) {
                check(result.size < 256) { "Deletion proof exceeds bounded capacity" }
                result.add(projection.mapIndexed { i, key ->
                    val value = if (c.isNull(i)) null else c.getString(i)
                    check(value == null || value.length <= 65536) { "Deletion proof value too large" }
                    characters += value?.length ?: 0
                    check(characters <= 262144) { "Deletion query proof exceeds bounded capacity" }
                    key to value
                }.toMap())
            }
        }
        return result
    }

    private fun family(calendar: String, root: String, sync: String?, knownSync: String? = null): Pair<String, Array<String>> {
        val syncIds = listOfNotNull(sync, knownSync).distinct()
        val syncClause = syncIds.joinToString("") { " OR original_sync_id=? OR original_id=?" }
        return Pair("calendar_id=? AND deleted=0 AND (_id=? OR original_id=?$syncClause)",
            (listOf(calendar, root, root) + syncIds.flatMap { listOf(it, it) }).toTypedArray())
    }

    fun read(resolver: ContentResolver, calendar: String, root: String, knownSync: String? = null): List<Map<String, String?>> {
        require(calendar.isNotBlank() && root.toLongOrNull() != null)
        val master = query(resolver, Events.CONTENT_URI, columns, "calendar_id=? AND _id=?", arrayOf(calendar, root)).singleOrNull()
        val sync = master?.get(Events._SYNC_ID) ?: knownSync
        // A replaced sync identity is novel, not permission to follow another event.
        val (where, args) = family(calendar, root, sync, knownSync)
        var characters = 0
        val rows = query(resolver, Events.CONTENT_URI, columns, where, args).map { row ->
            val complete = row + tables.mapValues { (key, uri) ->
                encode(query(resolver, uri, childColumns.getValue(key), "event_id=?", arrayOf(row.getValue("_id")!!)))
            }
            characters += complete.values.sumOf { it?.length ?: 0 }
            check(characters <= 262144) { "Deletion family proof exceeds bounded capacity" }
            complete
        }
        // Multiple queries must describe one coherent version, not mixed facts
        // from successive external writes. This batch contains assertions ONLY.
        resolver.applyBatch(CalendarContract.AUTHORITY, assertions(calendar, root, sync, rows, knownSync))
        return rows
    }

    private fun encode(rows: List<Map<String, String?>>): String = JSONArray(rows.map { row ->
        JSONObject().apply { row.toSortedMap().forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } }
    }).toString()
    private fun decode(value: String): List<Map<String, String?>> {
        val array = JSONArray(value)
        require(array.length() <= 256)
        return (0 until array.length()).map { i ->
            val row = array.getJSONObject(i)
            row.keys().asSequence().associateWith { if (row.isNull(it)) null else row.getString(it) }
        }
    }

    fun plan(rows: List<Map<String, String?>>, root: String, scope: String, slot: Long?): Map<String, Any?>? {
        require(scope in setOf("all", "occurrence", "following"))
        val master = rows.singleOrNull { it["_id"] == root } ?: return null
        if (master[Events.DELETED] != "0" || master[Events.STATUS] == "2") return null
        if (scope == "all") return mapOf("deleteIds" to rows.map { it.getValue("_id")!! }, "cancelIds" to emptyList<String>())
        val raw = master[Events.RRULE] ?: return null
        val start = master[Events.DTSTART]?.toLongOrNull() ?: return null
        if (slot == null || master[Events.ORIGINAL_ID] != null || master[Events.ORIGINAL_SYNC_ID] != null) return null
        val children = rows.filter { it["_id"] != root }
        val matching = children.filter { it[Events.ORIGINAL_INSTANCE_TIME] == slot.toString() }
        val zone = master[Events.EVENT_TIMEZONE] ?: "UTC"
        if (scope == "occurrence") {
            if (matching.isNotEmpty()) return mapOf("deleteIds" to emptyList<String>(), "cancelIds" to matching.map { it.getValue("_id")!! })
            val sync = master[Events._SYNC_ID]
            if (sync.isNullOrBlank() || sync.startsWith("SYNC_ERROR:") ||
                !recurrenceRuleContainsOccurrenceStart(RecurrenceRule(raw), start, zone, slot)) return null
            return mapOf("deleteIds" to emptyList<String>(), "cancelIds" to emptyList<String>(), "insertSlot" to slot)
        }
        if (start == slot) return mapOf("deleteIds" to rows.map { it.getValue("_id")!! }, "cancelIds" to emptyList<String>())
        val prefix = recurrenceDeletionPrefix(raw, start, zone, slot) ?: return null
        // Missing original slots prevent proving that all affected children are known.
        if (children.any { it[Events.ORIGINAL_INSTANCE_TIME]?.toLongOrNull() == null }) return null
        return mapOf("deleteIds" to children.filter { it[Events.ORIGINAL_INSTANCE_TIME]!!.toLong() >= slot }.map { it.getValue("_id")!! },
            "cancelIds" to emptyList<String>(), "prefixRule" to prefix)
    }

    private fun values(row: Map<String, String?>) = ContentValues().apply {
        row.forEach { (key, value) -> if (value == null) putNull(key) else put(key, value) }
    }
    private fun assertions(calendar: String, root: String, sync: String?, rows: List<Map<String, String?>>, knownSync: String? = null): ArrayList<ContentProviderOperation> {
        require(rows.size <= 256 && rows.map { it["_id"] }.distinct().size == rows.size)
        require(rows.all { it.keys == columns.toSet() + tables.keys && it[Events.CALENDAR_ID] == calendar && it[Events.DELETED] == "0" })
        val (where, args) = family(calendar, root, sync, knownSync)
        val ops = arrayListOf(ContentProviderOperation.newAssertQuery(Events.CONTENT_URI)
            .withSelection(where, args).withExpectedCount(rows.size).build())
        rows.forEach { row ->
            ops.add(ContentProviderOperation.newAssertQuery(Events.CONTENT_URI)
                .withSelection("_id=? AND calendar_id=?", arrayOf(row.getValue("_id")!!, calendar))
                .withValues(values(row.filterKeys { it in columns })).withExpectedCount(1).build())
            tables.forEach { (key, uri) ->
                val children = decode(row.getValue(key)!!)
                require(children.all { it.keys == childColumns.getValue(key).toSet() && it["event_id"] == row["_id"] })
                ops.add(ContentProviderOperation.newAssertQuery(uri).withSelection("event_id=?", arrayOf(row.getValue("_id")!!))
                    .withExpectedCount(children.size).build())
                children.forEach { child ->
                    ops.add(ContentProviderOperation.newAssertQuery(uri)
                        .withSelection("_id=? AND event_id=?", arrayOf(child.getValue("_id")!!, row.getValue("_id")!!))
                        .withValues(values(child)).withExpectedCount(1).build())
                }
            }
        }
        return ops
    }

    private fun canonical(row: Map<String, String?>): Map<String, String?> = row.toMutableMap().apply {
        remove(Events.DIRTY); remove(Events.MUTATORS)
        tables.keys.forEach { key ->
            this[key]?.let { raw ->
                // Related table row IDs may be reallocated by synchronization.
                // Match their complete content; CAS still guards CURRENT IDs.
                this[key] = JSONArray(decode(raw).map { child ->
                    val content = child - "_id"
                    encode(listOf(content))
                }.sorted()).toString()
            }
        }
        this[Events.RRULE] = this[Events.RRULE]?.uppercase()?.removePrefix("RRULE:")?.split(';')
            ?.filterNot { it == "WKST=MO" || it == "INTERVAL=1" }?.toMutableList()?.let { parts ->
                if ("FREQ=DAILY" in parts && parts.none { it.startsWith("BYWEEKNO=") }) parts.removeAll { it.startsWith("WKST=") }
                parts.sorted().joinToString(";")
            } ?: ""
    }

    fun apply(resolver: ContentResolver, calendar: String, root: String, scope: String, slot: Long?,
              before: List<Map<String, String?>>, observed: List<Map<String, String?>>, repairId: String): Boolean {
        // Repeat historical-content authorization natively; a stale/malformed
        // caller cannot turn a structural RRULE proof into destructive authority.
        require(before.isNotEmpty() && before.size == observed.size)
        require(before.associate { it["_id"] to canonical(it) } == observed.associate { it["_id"] to canonical(it) })
        val effect = requireNotNull(plan(before, root, scope, slot))
        val master = observed.single { it["_id"] == root }
        val ops = assertions(calendar, root, master[Events._SYNC_ID], observed)
        @Suppress("UNCHECKED_CAST") val deleteIds = effect["deleteIds"] as List<String>
        @Suppress("UNCHECKED_CAST") val cancelIds = effect["cancelIds"] as List<String>
        // Children before root, never rely on provider-dependent cascading.
        deleteIds.sortedBy { it == root }.forEach { id ->
            ops.add(ContentProviderOperation.newDelete(Events.CONTENT_URI)
                .withSelection("_id=? AND calendar_id=? AND deleted=0", arrayOf(id, calendar)).withExpectedCount(1).build())
        }
        cancelIds.forEach { id ->
            ops.add(ContentProviderOperation.newUpdate(Events.CONTENT_URI)
                .withSelection("_id=? AND calendar_id=? AND deleted=0", arrayOf(id, calendar))
                .withValue(Events.STATUS, Events.STATUS_CANCELED).withExpectedCount(1).build())
        }
        effect["prefixRule"]?.let { prefix ->
            ops.add(ContentProviderOperation.newUpdate(Events.CONTENT_URI)
                .withSelection("_id=? AND calendar_id=? AND deleted=0", arrayOf(root, calendar))
                .withValue(Events.RRULE, prefix as String)
                .withValue(Events.DTSTART, master.getValue(Events.DTSTART)!!.toLong()).withExpectedCount(1).build())
        }
        effect["insertSlot"]?.let { boundary ->
            ops.add(ContentProviderOperation.newInsert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, root.toLong()))
                .withValue(Events.ORIGINAL_INSTANCE_TIME, boundary as Long).withValue(Events.STATUS, Events.STATUS_CANCELED).build())
        }
        return try {
            resolver.applyBatch(CalendarContract.AUTHORITY, ops)
            runCatching { Log.i("KeepCalSyncRepair", "applied " + JSONObject(mapOf(
                "repairId" to repairId.take(120), "calendar" to calendar, "root" to root,
                "operation" to "restore-deletion", "scope" to scope)).toString()) }
            true
        } catch (_: android.content.OperationApplicationException) { false }
    }

    fun handle(context: Context, call: MethodCall, result: MethodChannel.Result) {
        @Suppress("UNCHECKED_CAST")
        val input = snapshotCalendarArguments(call.arguments as? Map<String, Any?> ?: emptyMap())
        CalendarWriteExecutor.submit(result, {}) { reply ->
            val calendar = input["calendarId"] as String
            val root = input["root"] as String
            val scope = input["scope"] as String
            val slot = (input["slot"] as? Number)?.toLong()
            if (call.method == "readDeletionRepairSnapshot") {
                val rows = read(context.contentResolver, calendar, root, input["syncId"] as? String)
                reply.success(mapOf("rows" to rows, "plan" to plan(rows, root, scope, slot), "instant" to RecentRecurrenceRepair.clock(context)))
            } else {
                val now = RecentRecurrenceRepair.clock(context)
                if (now["epoch"] == "" || now["epoch"] != input["epoch"] ||
                    (now["us"] as Long) >= (input["deadlineUs"] as Number).toLong()) {
                    reply.success("expired")
                } else {
                    @Suppress("UNCHECKED_CAST") val before = input["beforeRows"] as List<Map<String, String?>>
                    @Suppress("UNCHECKED_CAST") val rows = input["rows"] as List<Map<String, String?>>
                    reply.success(if (apply(context.contentResolver, calendar, root, scope, slot, before, rows,
                            input["repairId"] as String)) "updated" else "conflict")
                }
            }
        }
    }
}
