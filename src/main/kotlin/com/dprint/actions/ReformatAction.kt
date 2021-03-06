package com.dprint.actions

import com.dprint.config.ProjectConfiguration
import com.dprint.core.Bundle
import com.dprint.core.LogUtils
import com.dprint.services.FormatterService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import java.util.Collections

private val LOGGER = logger<ReformatAction>()

/**
 * This action is intended to be hooked up to a menu option or a key command. It handles events for two separate data
 * types, editor and virtual files.
 *
 * For editor data, it will pull the virtual file from the editor and for both it will send the virtual file to
 * DprintFormatterService to be formatted and saved.
 */
class ReformatAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.let { project ->
            if (!project.service<ProjectConfiguration>().state.enabled) return@let

            val editor = event.getData(PlatformDataKeys.EDITOR)

            if (editor != null) {
                formatDocument(project, editor.document)
            } else {
                event.getData(PlatformDataKeys.VIRTUAL_FILE)?.let {
                    formatVirtualFile(project, it)
                }
            }
        }
    }

    private fun formatDocument(project: Project, document: Document) {
        val formatterService = project.service<FormatterService>()
        PsiDocumentManager.getInstance(project).getPsiFile(document)?.virtualFile?.let { virtualFile ->
            LogUtils.info(Bundle.message("reformat.action.run", virtualFile.path), project, LOGGER)
            formatterService.format(virtualFile.path, document)
        }
    }

    private fun formatVirtualFile(project: Project, virtualFile: VirtualFile) {
        val formatterService = project.service<FormatterService>()
        LogUtils.info(Bundle.message("reformat.action.run", virtualFile.path), project, LOGGER)
        getDocument(project, virtualFile)?.let {
            formatterService.format(virtualFile.path, it)
        }
    }

    private fun isFileWriteable(project: Project, virtualFile: VirtualFile): Boolean {
        val readonlyStatusHandler = ReadonlyStatusHandler.getInstance(project)
        return !virtualFile.isDirectory &&
            virtualFile.isValid &&
            virtualFile.isInLocalFileSystem &&
            !readonlyStatusHandler.ensureFilesWritable(Collections.singleton(virtualFile)).hasReadonlyFiles()
    }

    private fun getDocument(project: Project, virtualFile: VirtualFile): Document? {
        if (isFileWriteable(project, virtualFile)) {
            PsiManager.getInstance(project).findFile(virtualFile)?.let {
                return PsiDocumentManager.getInstance(project).getDocument(it)
            }
        }

        return null
    }
}
