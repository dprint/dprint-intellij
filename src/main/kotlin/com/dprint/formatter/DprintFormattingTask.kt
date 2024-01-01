package com.dprint.formatter

import com.dprint.i18n.DprintBundle
import com.dprint.services.editorservice.EditorServiceManager
import com.dprint.services.editorservice.FormatResult
import com.dprint.services.editorservice.exceptions.ProcessUnavailableException
import com.dprint.utils.errorLogWithConsole
import com.dprint.utils.infoLogWithConsole
import com.dprint.utils.warnLogWithConsole
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val LOGGER = logger<DprintFormattingTask>()
private const val FORMATTING_TIMEOUT = 10L

class DprintFormattingTask(
    private val project: Project,
    private val editorServiceManager: EditorServiceManager,
    private val formattingRequest: AsyncFormattingRequest,
) {
    private var formattingId: Int? = editorServiceManager.maybeGetFormatId()
    private var isCancelled = false
    private val baseFormatFuture = CompletableFuture<FormatResult>()
    private var activeFormatFuture: CompletableFuture<FormatResult>? = null

    fun run(path: String) {
        val content = formattingRequest.documentText
        val ranges = formattingRequest.formattingRanges

        infoLogWithConsole(
            DprintBundle.message("external.formatter.running.task", formattingId ?: path),
            project,
            LOGGER,
        )

        // If the version can't range format we always return null, in this case the underlying process will
        // extract the start byte position (0) and end byte position (content.encodeToByteArray().size) for the
        // whole file
        val initialRange =
            if (editorServiceManager.canRangeFormat() && ranges.size > 0) ranges.first() else null
        val initialHandler: (FormatResult) -> Unit = {
            baseFormatFuture.complete(it)
        }

        editorServiceManager.format(
            formattingId,
            path,
            content,
            initialRange?.startOffset,
            initialRange?.endOffset?.let {
                when {
                    it > content.length -> content.length
                    else -> it
                }
            },
            initialHandler,
        )

        for (range in ranges.subList(1, ranges.size)) {
            baseFormatFuture.thenApply { formatResult ->
                if (isCancelled) {
                    formatResult
                } else {
                    applyNextRangeFormat(
                        path,
                        formatResult,
                        getStartOfRange(formatResult.formattedContent, content, range),
                        getEndOfRange(formatResult.formattedContent, content, range),
                    )
                }
            }
        }

        // Timeouts are handled at the EditorServiceManager level and an empty result will be
        // returned if something goes wrong
        val result = getFuture(baseFormatFuture)

        if (isCancelled || result == null) return

        val error = result.error
        if (error != null) {
            formattingRequest.onError(DprintBundle.message("formatting.error"), error)
        } else {
            // If the result is a no op it will be null, in which case we pass the original content back in
            formattingRequest.onTextReady(result.formattedContent ?: content)
        }
    }

    private fun getFuture(future: CompletableFuture<FormatResult>): FormatResult? {
        return try {
            future.get(FORMATTING_TIMEOUT, TimeUnit.SECONDS)
        } catch (e: CancellationException) {
            errorLogWithConsole("External format process cancelled", e, project, LOGGER)
            null
        } catch (e: TimeoutException) {
            errorLogWithConsole("External format process timed out", e, project, LOGGER)
            formattingRequest.onError("Dprint external formatter", "Format process timed out")
            editorServiceManager.restartEditorService()
            null
        } catch (e: ExecutionException) {
            if (e.cause is ProcessUnavailableException) {
                warnLogWithConsole(
                    DprintBundle.message("editor.service.process.is.dead"),
                    e.cause,
                    project,
                    LOGGER,
                )
            }
            errorLogWithConsole("External format process failed", e, project, LOGGER)
            formattingRequest.onError("Dprint external formatter", "Format process failed")
            editorServiceManager.restartEditorService()
            null
        } catch (e: InterruptedException) {
            errorLogWithConsole("External format process interrupted", e, project, LOGGER)
            formattingRequest.onError("Dprint external formatter", "Format process interrupted")
            editorServiceManager.restartEditorService()
            null
        }
    }

    private fun applyNextRangeFormat(
        path: String,
        previousFormatResult: FormatResult,
        startIndex: Int?,
        endIndex: Int?,
    ): FormatResult? {
        val contentToFormat = previousFormatResult.formattedContent
        if (contentToFormat == null || startIndex == null || endIndex == null) {
            errorLogWithConsole(
                DprintBundle.message(
                    "external.formatter.illegal.state",
                    startIndex ?: "null",
                    endIndex ?: "null",
                    contentToFormat ?: "null",
                ),
                project,
                LOGGER,
            )
            return null
        }

        val nextFuture = CompletableFuture<FormatResult>()
        activeFormatFuture = nextFuture
        val nextHandler: (FormatResult) -> Unit = { nextResult ->
            nextFuture.complete(nextResult)
        }
        // Need to update the formatting id so the correct job would be cancelled
        formattingId = editorServiceManager.maybeGetFormatId()
        editorServiceManager.format(
            formattingId,
            path,
            contentToFormat,
            startIndex,
            endIndex,
            nextHandler,
        )

        return getFuture(nextFuture)
    }

    fun cancel(): Boolean {
        if (!editorServiceManager.canCancelFormat()) return false

        val formatId = formattingId
        isCancelled = true
        formatId?.let {
            infoLogWithConsole(
                DprintBundle.message("external.formatter.cancelling.task", it),
                project,
                LOGGER,
            )
            editorServiceManager.cancelFormat(it)
        }
        // Clean up state so process can complete
        baseFormatFuture.cancel(true)
        activeFormatFuture?.cancel(true)
        return true
    }

    fun isRunUnderProgress(): Boolean {
        return true
    }
}

private fun getStartOfRange(
    currentContent: String?,
    originalContent: String,
    range: TextRange,
): Int? {
    if (currentContent == null) {
        return null
    }
    // We need to account for dprint changing the length of the file as it formats. The assumption made is
    // that ranges do not overlap, so we can use the diff to the original content length to know where the
    // new range will shift after each range format.
    val rangeOffset = currentContent.length - originalContent.length
    val startOffset = range.startOffset + rangeOffset
    return when {
        startOffset > currentContent.length -> currentContent.length
        else -> startOffset
    }
}

private fun getEndOfRange(
    currentContent: String?,
    originalContent: String,
    range: TextRange,
): Int? {
    if (currentContent == null) {
        return null
    }
    // We need to account for dprint changing the length of the file as it formats. The assumption made is
    // that ranges do not overlap, so we can use the diff to the original content length to know where the
    // new range will shift after each range format.
    val rangeOffset = currentContent.length - originalContent.length
    val endOffset = range.endOffset + rangeOffset
    return when {
        endOffset < 0 -> 0
        else -> endOffset
    }
}
