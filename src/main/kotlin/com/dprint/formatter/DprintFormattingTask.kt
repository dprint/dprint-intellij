package com.dprint.formatter

import com.dprint.i18n.DprintBundle
import com.dprint.otel.AttributeKeys
import com.dprint.otel.DprintScope
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
import com.intellij.platform.diagnostic.telemetry.TelemetryManager
import com.intellij.platform.diagnostic.telemetry.helpers.use
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
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
    private val path: String,
) {
    private var formattingIds = mutableListOf<Int>()
    private var isCancelled = false
    private val tracer: Tracer = TelemetryManager.getInstance().getTracer(DprintScope.FormatterScope)

    /**
     * Used when we want to cancel a format, so that we can cancel every future in the chain.
     */
    private val allFormatFutures = mutableListOf<CompletableFuture<FormatResult>>()

    fun run() {
        val rootSpan =
            tracer.spanBuilder("dprint.format")
                .setAttribute(AttributeKeys.FILE_PATH, path)
                .startSpan()

        try {
            rootSpan.makeCurrent().use { scope ->
                val content = formattingRequest.documentText

                rootSpan.setAttribute(AttributeKeys.CONTENT_LENGTH, content.length.toLong())

                val ranges =
                    tracer.spanBuilder("dprint.determine_ranges")
                        .startSpan().use { rangesSpan ->
                            if (editorServiceManager.canRangeFormat()) {
                                rangesSpan.setAttribute("range_format_supported", true)
                                rangesSpan.setAttribute(
                                    "ranges_count",
                                    formattingRequest.formattingRanges.size.toLong(),
                                )
                                formattingRequest.formattingRanges
                            } else {
                                rangesSpan.setAttribute("range_format_supported", false)
                                rangesSpan.setAttribute("ranges_count", 1L)
                                mutableListOf(
                                    TextRange(0, content.length),
                                )
                            }
                        }

                infoLogWithConsole(
                    DprintBundle.message("external.formatter.running.task", path),
                    project,
                    LOGGER,
                )

                val initialResult = FormatResult(formattedContent = content)
                val baseFormatFuture = CompletableFuture.completedFuture(initialResult)
                allFormatFutures.add(baseFormatFuture)

                val formatRangesSpan =
                    tracer.spanBuilder("dprint.format_ranges")
                        .setAttribute("ranges_count", ranges.size.toLong())
                        .startSpan()

                var nextFuture = baseFormatFuture
                for (range in ranges.subList(0, ranges.size)) {
                    formatRangesSpan.addEvent(
                        "processing_range",
                        Attributes.of(
                            AttributeKeys.RANGE_START,
                            range.startOffset.toLong(),
                            AttributeKeys.RANGE_END,
                            range.endOffset.toLong(),
                        ),
                    )

                    nextFuture.thenCompose { formatResult ->
                        nextFuture =
                            if (isCancelled) {
                                formatRangesSpan.addEvent(
                                    "format_cancelled",
                                    Attributes.of(
                                        AttributeKeys.RANGE_START,
                                        range.startOffset.toLong(),
                                        AttributeKeys.RANGE_END,
                                        range.endOffset.toLong(),
                                    ),
                                )
                                // Revert to the initial contents
                                CompletableFuture.completedFuture(initialResult)
                            } else {
                                applyNextRangeFormat(
                                    path,
                                    formatResult,
                                    getStartOfRange(formatResult.formattedContent, content, range),
                                    getEndOfRange(formatResult.formattedContent, content, range),
                                    formatRangesSpan,
                                )
                            }
                        nextFuture
                    }
                }

                // Timeouts are handled at the EditorServiceManager level and an empty result will be
                // returned if something goes wrong
                val result = getFuture(nextFuture)
                formatRangesSpan.end()

                // If cancelled there is no need to utilise the formattingRequest finalising methods
                if (isCancelled) {
                    rootSpan.addEvent("formatting_cancelled")
                    return
                }

                val resultSpan =
                    tracer.spanBuilder("dprint.process_result")
                        .startSpan()

                resultSpan.use {
                    // If the result is null we don't want to change the document text, so we just set it to be the original.
                    // This should only happen if getting the future throws.
                    if (result == null) {
                        resultSpan.setAttribute("result", "null")
                        formattingRequest.onTextReady(content)
                        return
                    }

                    val error = result.error
                    if (error != null) {
                        resultSpan.setStatus(StatusCode.ERROR, error)
                        formattingRequest.onError(DprintBundle.message("formatting.error"), error)
                    } else {
                        // Record if there was any content change
                        resultSpan.setAttribute("content_changed", (result.formattedContent != content))

                        // If the result is a no op it will be null, in which case we pass the original content back in
                        val finalContent = result.formattedContent ?: content
                        resultSpan.setAttribute("final_content_length", finalContent.length.toLong())
                        formattingRequest.onTextReady(finalContent)
                    }
                }
            }
        } catch (e: Exception) {
            rootSpan.recordException(e)
            rootSpan.setStatus(StatusCode.ERROR)
            throw e
        } finally {
            rootSpan.end()
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
        parentSpan: Span,
    ): CompletableFuture<FormatResult>? {
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

        val span =
            tracer.spanBuilder("dprint.formatter.apply_range_format")
                .setParent(Context.current().with(parentSpan))
                .setAttribute(AttributeKeys.FILE_PATH, path)
                .setAttribute(AttributeKeys.RANGE_START, startIndex.toLong())
                .setAttribute(AttributeKeys.RANGE_END, endIndex.toLong())
                .setAttribute(AttributeKeys.CONTENT_LENGTH, contentToFormat.length.toLong())
                .startSpan()

        // This span is ended in the callback, don't end it early.
        return span.makeCurrent().use { scope ->
            // Need to update the formatting id so the correct job would be cancelled
            val formattingId = editorServiceManager.maybeGetFormatId()
            formattingId?.let {
                formattingIds.add(it)
                span.setAttribute(AttributeKeys.FORMATTING_ID, it.toLong())
            }

            val nextFuture = CompletableFuture<FormatResult>()
            allFormatFutures.add(nextFuture)
            val nextHandler: (FormatResult) -> Unit = { nextResult ->
                // Add result information to the span
                if (nextResult.error != null) {
                    span.setStatus(StatusCode.ERROR, nextResult.error)
                } else {
                    span.setStatus(StatusCode.OK)
                    if (nextResult.formattedContent != null) {
                        val contentLengthDiff = nextResult.formattedContent.length - contentToFormat.length
                        span.setAttribute("content_length_diff", contentLengthDiff.toLong())
                    }
                }
                span.end()

                nextFuture.complete(nextResult)
            }
            editorServiceManager.format(
                formattingId,
                path,
                contentToFormat,
                startIndex,
                endIndex,
                nextHandler,
            )

            nextFuture
        }
    }

    fun cancel(): Boolean {
        val span =
            tracer.spanBuilder("dprint.formatter.cancel_format")
                .setAttribute(AttributeKeys.FILE_PATH, path)
                .startSpan()

        span.use { scope ->
            if (!editorServiceManager.canCancelFormat()) {
                span.setAttribute("can_cancel", false)
                span.addEvent("cancel_not_supported")
                return false
            }

            span.setAttribute("can_cancel", true)
            span.setAttribute("formatting_ids_count", formattingIds.size.toLong())

            isCancelled = true
            for (id in formattingIds) {
                span.addEvent("cancelling_task", Attributes.of(AttributeKeys.FORMATTING_ID, id.toLong()))
                infoLogWithConsole(
                    DprintBundle.message("external.formatter.cancelling.task", id),
                    project,
                    LOGGER,
                )
                editorServiceManager.cancelFormat(id)
            }

            // Clean up state so process can complete
            span.addEvent(
                "cancelling_futures",
                Attributes.of(AttributeKey.longKey("futures_count"), allFormatFutures.size.toLong()),
            )
            allFormatFutures.stream().forEach { f -> f.cancel(true) }
            return true
        }
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
