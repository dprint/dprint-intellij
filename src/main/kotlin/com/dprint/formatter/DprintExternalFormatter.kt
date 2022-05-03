package com.dprint.formatter

import com.dprint.config.ProjectConfiguration
import com.dprint.core.Bundle
import com.dprint.services.NOTIFICATION_GROUP_ID
import com.dprint.services.NotificationService
import com.dprint.services.editorservice.EditorServiceManager
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiFile

private val LOGGER = logger<DprintExternalFormatter>()
private const val NAME = "dprintfmt"

class DprintExternalFormatter : AsyncDocumentFormattingService() {
    override fun getFeatures(): MutableSet<FormattingService.Feature> {
        // To ensure that we don't allow IntelliJ range formatting on files that should be dprint formatted we need to
        // say we provide the FORMAT_FRAGMENTS and AD_HOC_FORMATTING features then handle them as a no op.
        return mutableSetOf(FormattingService.Feature.FORMAT_FRAGMENTS, FormattingService.Feature.AD_HOC_FORMATTING)
    }

    override fun canFormat(file: PsiFile): Boolean {
        val editorService = file.project.service<EditorServiceManager>().maybeGetEditorService()
        return editorService != null && file.virtualFile != null &&
            file.project.service<ProjectConfiguration>().state.enabled &&
            editorService.canFormat(file.virtualFile.path)
    }

    override fun createFormattingTask(formattingRequest: AsyncFormattingRequest): FormattingTask? {
        val project = formattingRequest.context.project
        // As per the getFeatures comment we handle FORMAT_FRAGMENTS and AD_HOC_FORMATTING features as a no op.
        if (!project.service<ProjectConfiguration>().state.enabled || isRangeFormat(formattingRequest)) {
            return null
        }

        val editorService = project.service<EditorServiceManager>().maybeGetEditorService()
        val notificationService = project.service<NotificationService>()
        val path = formattingRequest.ioFile?.path

        if (path == null) {
            val message = Bundle.message("formatting.cannot.determine.file.path")
            notificationService.notifyOfFormatFailure(message)
            LOGGER.info(message)
            LOGGER.info(formattingRequest.documentText)
            return null
        }

        if (editorService == null) {
            val message = Bundle.message("formatting.service.editor.service.uninitialized")
            notificationService.notifyOfFormatFailure(message)
            LOGGER.info(message)
            return null
        }

        return object : FormattingTask {
            override fun run() {
                val content = formattingRequest.documentText

                val result = editorService.fmt(path, content)

                result.error?.let {
                    formattingRequest.onError(Bundle.message("notification.format.failed.title"), it)
                    return
                }

                result.formattedContent?.let {
                    formattingRequest.onTextReady(it)
                    return
                }

                // In the event dprint is a no-op we just reset the original text rather than throwing an error
                formattingRequest.onTextReady(formattingRequest.documentText)
            }

            override fun cancel(): Boolean {
                // TODO (ryan) to be able to cancel dprint formatting something we need to interrupt the editor service
                // and reset it. This is non trivial without shutting it down so for now we just don't allow cancelling.
                return false
            }

            override fun isRunUnderProgress(): Boolean {
                return true
            }
        }
    }

    private fun isRangeFormat(formattingRequest: AsyncFormattingRequest): Boolean {
        return when {
            formattingRequest.isQuickFormat -> true
            formattingRequest.formattingRanges.size > 1 -> true
            formattingRequest.formattingRanges.size == 1 -> {
                val range = formattingRequest.formattingRanges[0]
                return range.startOffset != 0 || range.endOffset != formattingRequest.documentText.length
            }
            else -> false
        }
    }

    override fun getNotificationGroupId(): String {
        return NOTIFICATION_GROUP_ID
    }

    override fun getName(): String {
        return NAME
    }
}
