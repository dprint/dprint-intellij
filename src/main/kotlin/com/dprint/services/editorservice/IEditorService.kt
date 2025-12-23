package com.dprint.services.editorservice

import com.dprint.i18n.DprintBundle
import com.dprint.services.editorservice.exceptions.HandlerNotImplementedException
import com.intellij.openapi.Disposable

interface IEditorService : Disposable {
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
    suspend fun canFormat(filePath: String): Boolean?

    /**
     * This runs dprint using the editor service with the supplied file path and content as stdin.
     * @param formatId The id of the message that is passed to the underlying editor service. This is exposed at this
     * level, so we can cancel requests if need be.
     * @param filePath The path of the file being formatted. This is needed so the correct dprint configuration file
     * located.
     * @param content The content of the file as a string. This is formatted via Dprint and returned via the result.
     * @return A result object containing the formatted content is successful or an error.
     */
    suspend fun fmt(
        filePath: String,
        content: String,
        formatId: Int?,
    ): FormatResult

    /**
     * This runs dprint using the editor service with the supplied file path and content as stdin.
     * @param formatId The id of the message that is passed to the underlying editor service. This is exposed at this
     * level, so we can cancel requests if need be.
     * @param filePath The path of the file being formatted. This is needed so the correct dprint configuration file
     * located.
     * @param content The content of the file as a string. This is formatted via Dprint and returned via the result.
     * @param startIndex The char index indicating where to start range formatting from
     * @param endIndex The char indicating where to range format up to (not inclusive)
     * @return A result object containing the formatted content is successful or an error.
     */
    suspend fun fmt(
        filePath: String,
        content: String,
        formatId: Int?,
        startIndex: Int?,
        endIndex: Int?,
    ): FormatResult

    /**
     * Whether the editor service implementation supports range formatting.
     */
    fun canRangeFormat(): Boolean

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
    fun cancelFormat(formatId: Int): Unit =
        throw HandlerNotImplementedException(DprintBundle.message("error.cancel.format.not.implemented"))
}
