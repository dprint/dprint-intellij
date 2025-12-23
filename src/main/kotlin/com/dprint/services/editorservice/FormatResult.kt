package com.dprint.services.editorservice

/**
 * The resulting state of running the Dprint formatter.
 *
 * If both parameters are null, it represents a no-op from the format operation.
 */
data class FormatResult(
    val formattedContent: String? = null,
    val error: String? = null,
)
