package com.dprint.formatter

import com.dprint.i18n.DprintBundle
import com.dprint.services.DprintService
import com.dprint.services.editorservice.FormatResult
import com.dprint.utils.errorLogWithConsole
import com.dprint.utils.infoLogWithConsole
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

private val LOGGER = logger<DprintRangeFormattingTask>()
private val FORMATTING_TIMEOUT = 10.seconds

class DprintRangeFormattingTask(
    private val project: Project,
    private val dprintService: DprintService,
    private val formattingRequest: AsyncFormattingRequest,
    private val path: String,
) {
    private var formattingIds = mutableListOf<Int>()
    private var isCancelled = false

    fun run() {
        val content = formattingRequest.documentText
        val ranges = formattingRequest.formattingRanges

        infoLogWithConsole(
            DprintBundle.message("external.formatter.running.task", path),
            project,
            LOGGER,
        )

        val result = runRangeFormatting(content, ranges)

        // If cancelled there is no need to utilise the formattingRequest finalising methods
        if (isCancelled) return

        // If the result is null we don't want to change the document text, so we just set it to be the original.
        // This should only happen if formatting throws.
        if (result == null) {
            formattingRequest.onTextReady(content)
            return
        }

        val error = result.error
        if (error != null) {
            formattingRequest.onError(DprintBundle.message("formatting.error"), error)
        } else {
            // If the result is a no op it will be null, in which case we pass the original content back in
            formattingRequest.onTextReady(result.formattedContent ?: content)
        }
    }

    private fun runRangeFormatting(
        content: String,
        ranges: List<TextRange>,
    ): FormatResult? {
        return try {
            runBlocking {
                withTimeout(FORMATTING_TIMEOUT) {
                    if (isCancelled) return@withTimeout null

                    // For multiple ranges, we need to format them sequentially
                    // starting from the end to avoid offset issues
                    val sortedRanges = ranges.sortedByDescending { it.startOffset }
                    var currentContent = content

                    for (range in sortedRanges) {
                        if (isCancelled) return@withTimeout null

                        // Need to update the formatting id so the correct job would be cancelled
                        val formatId = dprintService.maybeGetFormatId()
                        formatId?.let {
                            formattingIds.add(it)
                        }

                        val result =
                            dprintService.formatSuspend(
                                path = path,
                                content = currentContent,
                                formatId = formatId,
                                startIndex = range.startOffset,
                                endIndex = range.endOffset,
                            )

                        if (result == null || result.error != null) {
                            return@withTimeout result
                        }

                        // Update content for next iteration if formatting was successful
                        currentContent = result.formattedContent ?: currentContent
                    }

                    FormatResult(formattedContent = currentContent)
                }
            }
        } catch (e: CancellationException) {
            // This is expected when the user cancels the operation
            infoLogWithConsole(DprintBundle.message("error.range.format.cancelled.by.user"), project, LOGGER)
            null
        } catch (e: TimeoutCancellationException) {
            errorLogWithConsole(
                DprintBundle.message("error.range.format.timeout", FORMATTING_TIMEOUT.inWholeSeconds),
                e,
                project,
                LOGGER,
            )
            formattingRequest.onError(
                DprintBundle.message("dialog.title.dprint.formatter"),
                DprintBundle.message("error.range.format.process.timeout", FORMATTING_TIMEOUT.inWholeSeconds),
            )
            dprintService.restartEditorService()
            null
        } catch (e: Exception) {
            errorLogWithConsole(
                DprintBundle.message("error.range.format.unexpected", e.javaClass.simpleName, e.message ?: "unknown"),
                e,
                project,
                LOGGER,
            )
            formattingRequest.onError(
                DprintBundle.message("dialog.title.dprint.formatter"),
                DprintBundle.message("error.format.unexpected.generic", e.message ?: e.javaClass.simpleName),
            )
            // Only restart service for unexpected errors that might indicate a corrupted state
            dprintService.restartEditorService()
            null
        }
    }

    fun cancel(): Boolean {
        if (!dprintService.canCancelFormat()) return false

        isCancelled = true
        for (id in formattingIds) {
            infoLogWithConsole(
                DprintBundle.message("external.formatter.cancelling.task", id),
                project,
                LOGGER,
            )
            dprintService.cancelFormat(id)
        }

        return true
    }

    fun isRunUnderProgress(): Boolean {
        return true
    }
}
