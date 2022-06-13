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

private val LOGGER = logger<DprintExternalFormatter>()
private const val NAME = "dprintfmt"

class DprintExternalFormatter : AsyncDocumentFormattingService() {
    override fun getFeatures(): MutableSet<FormattingService.Feature> {
        // To ensure that we don't allow IntelliJ range formatting on files that should be dprint formatted we need to
        // say we provide the FORMAT_FRAGMENTS features then handle them as a no op.
        return mutableSetOf(FormattingService.Feature.FORMAT_FRAGMENTS)
    }

    override fun canFormat(file: PsiFile): Boolean {
        if (!file.project.service<ProjectConfiguration>().state.enabled) {
            return false
        }
        // If we don't have a cached can format response then we return true and let the formatting task figure that
        // out. Worse case scenario is that a file that cannot be formatted by dprint won't trigger the default IntelliJ
        // formatter. This is a minor issue and should be resolved if they run it again.
        //
        // We need to take this approach as it appears that blocking the
        return file.project.service<EditorServiceManager>().canFormatCached(file.virtualFile.path) != false
    }

    override fun createFormattingTask(formattingRequest: AsyncFormattingRequest): FormattingTask? {
        val project = formattingRequest.context.project
        // As per the getFeatures comment we handle FORMAT_FRAGMENTS and AD_HOC_FORMATTING features as a no op.
        if (!project.service<ProjectConfiguration>().state.enabled) {
            return null
        }

        val editorServiceManager = project.service<EditorServiceManager>()
        val path = formattingRequest.ioFile?.path

        if (path == null) {
            LogUtils.info(Bundle.message("formatting.cannot.determine.file.path"), project, LOGGER)
            return null
        }

        if (project.service<EditorServiceManager>().canFormatCached(path) != true) {
            return null
        }

        if (!editorServiceManager.canRangeFormat() && isRangeFormat(formattingRequest)) {
            return null
        }

        return object : FormattingTask {
            private var formattingId: Int? = editorServiceManager.maybeGetFormatId()
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
                                // Need to update the formatting id so the correct job would be cancelled
                                formattingId = editorServiceManager.maybeGetFormatId()
                                editorServiceManager.format(
                                    formattingId,
                                    path,
                                    resultContent,
                                    range.startOffset,
                                    range.endOffset,
                                    nextHandler
                                )
                            }
                            // Timeouts are handled at the EditorServiceManager level and an empty result will be
                            // returned if something goes wrong
                            nextFuture.get()
                        }
                    }
                }

                val initialRange = if (ranges.size > 0) ranges.first() else null
                val initialHandler: (FormatResult) -> Unit = {
                    future.complete(it)
                }
                editorServiceManager.format(
                    formattingId,
                    path,
                    content,
                    initialRange?.startOffset ?: 0,
                    initialRange?.endOffset ?: content.length,
                    initialHandler
                )
                // Timeouts are handled at the EditorServiceManager level and an empty result will be
                // returned if something goes wrong
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
                if (!editorServiceManager.canCancelFormat()) {
                    return false
                }

                val formatId = formattingId
                isCancelled = true
                if (formatId != null) editorServiceManager.cancelFormat(formatId)
                return true
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
                return range.startOffset > 0 || range.endOffset < formattingRequest.documentText.length
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
