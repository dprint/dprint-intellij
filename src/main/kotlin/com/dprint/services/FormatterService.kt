package com.dprint.services

import com.dprint.i18n.DprintBundle
import com.dprint.messages.DprintAction
import com.dprint.services.editorservice.EditorServiceManager
import com.dprint.services.editorservice.FormatResult
import com.dprint.utils.isFormattableFile
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlin.time.measureTimedValue

interface IFormatterService {
    /**
     * Attempts to format and save a Document using Dprint.
     */
    fun format(
        virtualFile: VirtualFile,
        document: Document,
    )
}

/**
 * A project service that handles reading virtual files, formatting their contents and writing the formatted result.
 */
@Service(Service.Level.PROJECT)
class FormatterService(project: Project) : IFormatterService {
    private val impl =
        FormatterServiceImpl(
            project,
            project.service<EditorServiceManager>(),
        )

    override fun format(
        virtualFile: VirtualFile,
        document: Document,
    ) {
        this.impl.format(virtualFile, document)
    }
}

class FormatterServiceImpl(
    private val project: Project,
    private val editorServiceManager: EditorServiceManager,
) :
    IFormatterService {
    override fun format(
        virtualFile: VirtualFile,
        document: Document,
    ) {
        val content = document.text
        val filePath = virtualFile.path

        if (content.isBlank() || !isFormattableFile(project, virtualFile)) {
            DprintAction.publishFormattingSkipped(project, filePath)
            return
        }

        if (editorServiceManager.canFormatCached(filePath) != true) {
            DprintAction.publishFormattingSkipped(project, filePath)
            DprintBundle.message("formatting.cannot.format", filePath)
            return
        }

        try {
            DprintAction.publishFormattingStarted(project, filePath)
            val formattingDuration =
                measureTimedValue {
                    runFormat(filePath, document, content)
                }
            DprintAction.publishFormattingSucceeded(project, filePath, formattingDuration.duration.inWholeMilliseconds)
        } catch (e: Throwable) {
            DprintAction.publishFormattingFailed(project, filePath, 0, e.message)
            throw e
        }
    }

    private fun runFormat(
        filePath: String,
        document: Document,
        content: String,
    ) {
        val formatHandler: (FormatResult) -> Unit = {
            it.formattedContent?.let {
                WriteCommandAction.runWriteCommandAction(project) {
                    document.setText(it)
                }
            }
        }
        editorServiceManager.format(filePath, content, formatHandler)
    }
}
