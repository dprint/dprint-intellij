package com.dprint.services.editorservice

/**
 * The resulting state of running the Dprint formatter.
 */
data class FormatResult(val formattedContent: String? = null, val error: String? = null)
