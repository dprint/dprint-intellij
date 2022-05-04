package com.dprint.services.editorservice

/**
 * The resulting state of running the Dprint formatter.
 */
class FormatResult {
    /**
     * The results of the formatting if successful.
     */
    var formattedContent: String? = null

    /**
     * The error message if formatting was not successful. This can come from custom messages, stderr or stdin.
     */
    var error: String? = null
}
