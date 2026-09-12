package com.builttoroam.devicecalendar.common

class ErrorCodes {
    companion object {
        const val INVALID_ARGUMENT: String = "400"
        const val NOT_FOUND: String = "404"
        const val NOT_ALLOWED: String = "405"
        const val NOT_AUTHORIZED: String = "401"
        // Explicit no-commit receipt, not a generic/possibly post-commit error.
        const val ATOMIC_WRITE_REJECTED: String = "422"
        const val GENERIC_ERROR: String = "500"
    }
}
