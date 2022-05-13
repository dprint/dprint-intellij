package com.dprint.services.editorservice

import com.dprint.services.editorservice.exceptions.HandlerNotImplementedException
import com.intellij.openapi.Disposable

interface EditorService : Disposable {
    /**
     * If enabled, initialises the dprint editor-service so it is ready to format
     */
    fun initialiseEditorService()

    /**
     * Shuts down the editor service and destroys the process.
     */
    fun destroyEditorService()

    /**
     * Returns whether dprint can format the given file path based on the config used in the editor service.
     */
    fun canFormat(filePath: String): Boolean

    fun canRangeFormat(): Boolean

    /**
     * This runs dprint using the editor service with the supplied file path and content as stdin.
     * @param filePath The path of the file being formatted. This is needed so the correct dprint configuration file
     * located.
     * @param content The content of the file as a string. This is formatted via Dprint and returned via the result.
     * @return A result object containing the formatted content is successful or an error.
     */
    fun fmt(filePath: String, content: String, onFinished: (FormatResult) -> Unit): Int? {
        return fmt(filePath, content, null, null, onFinished)
    }

    fun fmt(
        filePath: String,
        content: String,
        startIndex: Int?,
        endIndex: Int?,
        onFinished: (FormatResult) -> Unit
    ): Int?

    fun canCancelFormat(): Boolean

    fun cancelFormat(formatId: Int) {
        throw HandlerNotImplementedException("Cancel format has not been implemented")
    }
}
