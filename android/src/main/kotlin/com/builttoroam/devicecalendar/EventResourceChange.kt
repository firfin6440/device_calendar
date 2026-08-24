package com.builttoroam.devicecalendar

data class EventResourceValue(
    val name: String?,
    val email: String?
) {
    fun toMap(): Map<String, Any?> = mapOf("name" to name, "email" to email)

    fun identity(): String {
        val normalizedEmail = email?.trim()?.lowercase().orEmpty()
        if (normalizedEmail.isNotEmpty()) return "email:$normalizedEmail"
        return "name:${name?.trim()?.lowercase()?.replace(Regex("\\s+"), " ").orEmpty()}"
    }
}

fun parseEventResourceValues(value: Any?): List<EventResourceValue>? {
    val values = value as? List<*> ?: return null
    val parsed = values.map { raw ->
        val map = raw as? Map<*, *> ?: return null
        val name = (map["name"] as? String)?.trim()?.ifEmpty { null }
        val email = (map["email"] as? String)?.trim()?.ifEmpty { null }
        if (name == null && email == null) return null
        EventResourceValue(name, email)
    }
    return parsed.distinctBy(EventResourceValue::identity).sortedBy(EventResourceValue::identity)
}
