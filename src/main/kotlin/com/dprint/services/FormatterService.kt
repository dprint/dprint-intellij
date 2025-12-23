package com.dprint.services

import com.dprint.i18n.DprintBundle
import com.dprint.services.editorservice.FormatResult
import com.dprint.utils.isFormattableFile
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * A project service that handles reading virtual files, formatting their contents and writing the formatted result.
 */
@Service(Service.Level.PROJECT)
class FormatterService(
    private val project: Project,
) {
    fun format(
        virtualFile: VirtualFile,
        document: Document,
    ) {
        val content = document.text
        val filePath = virtualFile.path
        if (content.isBlank() || !isFormattableFile(project, virtualFile)) return

        val dprintService = project.service<DprintService>()
        if (dprintService.canFormatCached(filePath) == true) {
            val formatHandler: (FormatResult) -> Unit = {
                it.formattedContent?.let {
                    WriteCommandAction.runWriteCommandAction(project) {
                        document.setText(it)
                    }
                }
            }

            dprintService.format(filePath, content, formatHandler)
        } else {
            DprintBundle.message("formatting.cannot.format", filePath)
        }
    }
}
