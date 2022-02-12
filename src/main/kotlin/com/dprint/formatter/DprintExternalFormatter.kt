package com.dprint.formatter

import com.dprint.core.Bundle
import com.dprint.services.DprintService
import com.dprint.services.NOTIFICATION_GROUP_ID
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.components.service
import com.intellij.psi.PsiFile
import java.util.Collections

class DprintExternalFormatter : AsyncDocumentFormattingService() {
    override fun getFeatures(): MutableSet<FormattingService.Feature> {
        return Collections.emptySet()
    }

    override fun canFormat(file: PsiFile): Boolean {
        val dprintService = file.project.service<DprintService>()
        return dprintService.canFormat(file.virtualFile.path)
    }

    override fun createFormattingTask(formattingRequest: AsyncFormattingRequest): FormattingTask? {
        val dprintService = formattingRequest.context.project.service<DprintService>()
        return object : FormattingTask {
            override fun run() {
                val path = formattingRequest.ioFile?.path
                val content = formattingRequest.documentText

                path?.let {
                    val result = dprintService.fmt(path, content)

                    result?.error?.let {
                        formattingRequest.onError(Bundle.message("notification.format.failed.title"), it)
                        return
                    }

                    result?.formattedContent?.let {
                        formattingRequest.onTextReady(it)
                        return
                    }

                    // In the event dprint is a no-op we just reset the original text rather than throwing an error
                    formattingRequest.onTextReady(formattingRequest.documentText)
                }
            }

            override fun cancel(): Boolean {
                // TODO (ryan) to be able to cancel dprint formatting something we need to interrupt the editor service
                // and reset it. This is non trivial without shutting it down so for now we just don't allow cancelling.
                return false
            }
        }
    }

    override fun getNotificationGroupId(): String {
        return NOTIFICATION_GROUP_ID
    }

    override fun getName(): String {
        return "dprintfmt"
    }
}
