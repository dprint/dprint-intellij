package com.dprint.services

import com.dprint.core.Bundle
import com.dprint.services.editorservice.EditorServiceManager
import com.dprint.services.editorservice.FormatResult
import com.intellij.ide.scratch.ScratchUtil
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project

/**
 * A project service that handles reading virtual files, formatting their contents and writing the formatted result.
 */
@Service
class FormatterService(private val project: Project) {
    private var editorServiceManager = project.service<EditorServiceManager>()

    /**
     * Attempts to format and save a Document using Dprint.
     */
    fun format(filePath: String, document: Document) {
        val content = document.text
        val virtualFile = FileDocumentManager.getInstance().getFile(document)
        if (content.isBlank() || ScratchUtil.isScratch(virtualFile)) return

        if (editorServiceManager.canFormatCached(filePath) == true) {
            val formatHandler: (FormatResult) -> Unit = {
                it.formattedContent?.let {
                    WriteCommandAction.runWriteCommandAction(project) {
                        document.setText(it)
                    }
                }
            }

            editorServiceManager.format(filePath, content, formatHandler)
        } else {
            Bundle.message("formatting.cannot.format", filePath)
        }
    }
}
