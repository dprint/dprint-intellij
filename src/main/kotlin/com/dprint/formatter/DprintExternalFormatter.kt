package com.dprint.formatter

import com.dprint.config.ProjectConfiguration
import com.dprint.core.Bundle
import com.dprint.services.DprintService
import com.dprint.services.NOTIFICATION_GROUP_ID
import com.dprint.services.NotificationService
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiFile
import java.util.Collections

private val LOGGER = logger<DprintExternalFormatter>()
private val NAME = "dprintfmt"

class DprintExternalFormatter : AsyncDocumentFormattingService() {
    override fun getFeatures(): MutableSet<FormattingService.Feature> {
        return Collections.emptySet()
    }

    override fun canFormat(file: PsiFile): Boolean {
        val dprintService = file.project.service<DprintService>()
        return file.manager?.project?.service<ProjectConfiguration>()?.state?.enabled == true
            && dprintService.canFormat(file.virtualFile.path)
    }

    override fun createFormattingTask(formattingRequest: AsyncFormattingRequest): FormattingTask? {
        val project = formattingRequest.context.project
        if (!project.service<ProjectConfiguration>().state.enabled) {
            return null
        }

        val dprintService = project.service<DprintService>()
        val notificationService = project.service<NotificationService>()
        val path = formattingRequest.ioFile?.path

        if(path == null) {
            val message = Bundle.message("formatting.cannot.determine.file.path")
            notificationService.notifyOfFormatFailure(message)
            LOGGER.info(message)
            LOGGER.info(formattingRequest.documentText)
            return null
        }

        return object : FormattingTask {
            override fun run() {
                val content = formattingRequest.documentText

                val result = dprintService.fmt(path, content)

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

    override fun getNotificationGroupId(): String {
        return NOTIFICATION_GROUP_ID
    }

    override fun getName(): String {
        return NAME
    }
}
