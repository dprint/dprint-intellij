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
    fun canFormat(
        filePath: String,
        onFinished: (Boolean) -> Unit,
    )

    fun canRangeFormat(): Boolean

    /**
     * This runs dprint using the editor service with the supplied file path and content as stdin.
     * @param filePath The path of the file being formatted. This is needed so the correct dprint configuration file
     * located.
     * @param content The content of the file as a string. This is formatted via Dprint and returned via the result.
     * @param onFinished A callback that is called when the formatting job has finished. The only param to this callback
     * will be the result of the formatting job. The class providing this should handle timeouts themselves.
     * @return A result object containing the formatted content is successful or an error.
     */
    fun fmt(
        filePath: String,
        content: String,
        onFinished: (FormatResult) -> Unit,
    ): Int? {
        return fmt(maybeGetFormatId(), filePath, content, null, null, onFinished)
    }

    /**
     * This runs dprint using the editor service with the supplied file path and content as stdin.
     * @param formatId The id of the message that is passed to the underlying editor service. This is exposed at this
     * level so we can cancel requests if need be.
     * @param filePath The path of the file being formatted. This is needed so the correct dprint configuration file
     * located.
     * @param content The content of the file as a string. This is formatted via Dprint and returned via the result.
     * @param startIndex The starting index of a range format, null if the format is not for a range in the file.
     * @param endIndex The ending index of a range format, null if the format is not for a range in the file.
     * @param onFinished A callback that is called when the formatting job has finished. The only param to this callback
     * will be the result of the formatting job. The class providing this should handle timeouts themselves.
     * @return A result object containing the formatted content is successful or an error.
     */
    fun fmt(
        formatId: Int?,
        filePath: String,
        content: String,
        startIndex: Int?,
        endIndex: Int?,
        onFinished: (FormatResult) -> Unit,
    ): Int?

    /**
     * Whether the editor service implementation supports cancellation of formats.
     */
    fun canCancelFormat(): Boolean

    /**
     * Gets a formatting message id if the editor service supports messages with id's, this starts at schema version 5.
     */
    fun maybeGetFormatId(): Int?

    /**
     * Cancels the format for a given id if the service supports it. Will throw HandlerNotImplementedException if the
     * service doesn't.
     */
    fun cancelFormat(formatId: Int) {
        throw HandlerNotImplementedException("Cancel format has not been implemented")
    }
}
