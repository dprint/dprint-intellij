package com.dprint.formatter

import com.dprint.config.ProjectConfiguration
import com.dprint.core.Bundle
import com.dprint.core.LogUtils
import com.dprint.services.editorservice.EditorServiceManager
import com.dprint.services.editorservice.FormatResult
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiFile
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val LOGGER = logger<DprintExternalFormatter>()
private const val NAME = "dprintfmt"
private const val CAN_FORMAT_TIMEOUT = 4L

class DprintExternalFormatter : AsyncDocumentFormattingService() {
    override fun getFeatures(): MutableSet<FormattingService.Feature> {
        // To ensure that we don't allow IntelliJ range formatting on files that should be dprint formatted we need to
        // say we provide the FORMAT_FRAGMENTS features then handle them as a no op.
        return mutableSetOf(FormattingService.Feature.FORMAT_FRAGMENTS)
    }

    override fun canFormat(file: PsiFile): Boolean {
        try {
            val future = CompletableFuture.supplyAsync {
                val editorService = file.project.service<EditorServiceManager>().maybeGetEditorService()
                editorService != null &&
                    file.virtualFile != null &&
                    file.project.service<ProjectConfiguration>().state.enabled &&
                    editorService.canFormat(file.virtualFile.path)
            }.orTimeout(CAN_FORMAT_TIMEOUT, TimeUnit.SECONDS)
            return future.get()
        } catch (e: TimeoutException) {
            LogUtils.error(
                Bundle.message(
                    "editor.service.timed.out.checking.if.can.format",
                    file.virtualFile?.path ?: "Unknown file path"
                ),
                e,
                file.project,
                LOGGER
            )
        } catch (e: ExecutionException) {
            LogUtils.error(
                Bundle.message(
                    "editor.service.timed.out.checking.if.can.format",
                    file.virtualFile?.path ?: "Unknown file path"
                ),
                e,
                file.project,
                LOGGER
            )
        }

        return false
    }

    override fun createFormattingTask(formattingRequest: AsyncFormattingRequest): FormattingTask? {
        val project = formattingRequest.context.project
        // As per the getFeatures comment we handle FORMAT_FRAGMENTS and AD_HOC_FORMATTING features as a no op.
        if (!project.service<ProjectConfiguration>().state.enabled) {
            return null
        }

        val editorService = project.service<EditorServiceManager>().maybeGetEditorService()
        val path = formattingRequest.ioFile?.path

        if (path == null) {
            LogUtils.info(Bundle.message("formatting.cannot.determine.file.path"), project, LOGGER)
            LOGGER.info(formattingRequest.documentText)
            return null
        }

        if (editorService == null) {
            LogUtils.info(Bundle.message("formatting.service.editor.service.uninitialized"), project, LOGGER)
            return null
        }

        if (!editorService.canRangeFormat() && isRangeFormat(formattingRequest)) {
            return null
        }

        return object : FormattingTask {
            private var formattingId: Int? = null
            private var isCancelled = false

            override fun run() {
                val content = formattingRequest.documentText
                val ranges = formattingRequest.formattingRanges
                val future = CompletableFuture<FormatResult>()

                for (range in ranges.subList(1, ranges.size)) {
                    future.thenApply {
                        if (isCancelled) {
                            it
                        } else {
                            val resultContent = it.formattedContent
                            val nextFuture = CompletableFuture<FormatResult>()
                            val nextHandler: (FormatResult) -> Unit = { nextResult ->
                                nextFuture.complete(nextResult)
                            }
                            if (resultContent != null) {
                                formattingId = editorService.fmt(
                                    path,
                                    resultContent,
                                    range.startOffset,
                                    range.endOffset,
                                    nextHandler
                                )
                            }
                            nextFuture.get()
                        }
                    }
                }

                val initialRange = if (ranges.size > 0) ranges.first() else null
                val initialHandler: (FormatResult) -> Unit = {
                    future.complete(it)
                }
                formattingId = editorService.fmt(
                    path,
                    content,
                    initialRange?.startOffset ?: 0,
                    initialRange?.endOffset ?: content.length,
                    initialHandler
                )
                val result = future.get()

                if (isCancelled) {
                    return
                }

                val error = result.error
                if (error != null) {
                    formattingRequest.onError(Bundle.message("formatting.error"), error)
                } else {
                    // If the result is a no op it will be null, in which case we pass the original content back in
                    formattingRequest.onTextReady(result.formattedContent ?: content)
                }
            }

            override fun cancel(): Boolean {
                if (editorService.canCancelFormat()) {
                    formattingId?.let { editorService.cancelFormat(it) }
                    isCancelled = true
                    return true
                }

                return false
            }

            override fun isRunUnderProgress(): Boolean {
                return true
            }
        }
    }

    private fun isRangeFormat(formattingRequest: AsyncFormattingRequest): Boolean {
        return when {
            formattingRequest.formattingRanges.size > 1 -> true
            formattingRequest.formattingRanges.size == 1 -> {
                val range = formattingRequest.formattingRanges[0]
                return range.startOffset != 0 || range.endOffset != formattingRequest.documentText.length
            }
            else -> false
        }
    }

    override fun getNotificationGroupId(): String {
        return "Dprint"
    }

    override fun getName(): String {
        return NAME
    }
}
