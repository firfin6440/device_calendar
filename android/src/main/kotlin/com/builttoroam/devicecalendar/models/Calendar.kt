package com.builttoroam.devicecalendar.models

class Calendar(
    val id: String,
    val name: String,
    val color: Int,
    val accountName: String,
    val accountType: String,
    val ownerAccount: String?
) {
    var isReadOnly: Boolean = false
    var isDefault: Boolean = false
    var accessLevel: Int? = null
    var timeZone: String? = null
    var maxReminders: Int? = null
    var allowedReminderMethods: List<Int>? = null
    var allowedAvailabilities: List<String>? = null
    var allowedAttendeeTypes: List<Int>? = null
    var canModifyTimeZone: Boolean? = null
    var canOrganizerRespond: Boolean? = null
    var isVisible: Boolean? = null
    var isSyncEnabled: Boolean? = null
    var location: String? = null
    var colorKey: String? = null
}
