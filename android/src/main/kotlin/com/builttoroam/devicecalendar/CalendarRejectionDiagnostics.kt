package com.builttoroam.devicecalendar

/** Debug-only evidence, evaluated lazily and never allowed to affect a save. */
internal fun calendarRejectionDiagnostics(
    enabled: Boolean,
    titles: List<String?>,
    stage: String,
    batchFailure: Exception? = null,
    details: () -> Map<String, Any?>
): Map<String, Any?>? {
    if (!enabled || titles.none { it?.contains("test-rec", ignoreCase = true) == true }) {
        return null
    }
    return try {
        details() + mapOf(
            "stage" to stage,
            "batchExceptionType" to batchFailure?.javaClass?.name,
            "batchExceptionMessage" to batchFailure?.message
        )
    } catch (error: Exception) {
        mapOf("stage" to stage, "diagnosticErrorType" to error.javaClass.name)
    }
}
