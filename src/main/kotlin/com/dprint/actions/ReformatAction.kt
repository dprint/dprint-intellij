package com.dprint.actions

import com.dprint.config.ProjectConfiguration
import com.dprint.core.Bundle
import com.dprint.services.FormatterService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiDocumentManager

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

            val formatterService = project.service<FormatterService>()
            val editor = event.getData(PlatformDataKeys.EDITOR)

            if (editor != null) {
                PsiDocumentManager.getInstance(project).getPsiFile(editor.document)?.virtualFile?.let { virtualFile ->
                    LOGGER.info(Bundle.message("reformat.action.run", virtualFile.path))
                    formatterService.format(virtualFile)
                }
            } else {
                event.getData(PlatformDataKeys.VIRTUAL_FILE)?.let { virtualFile ->
                    LOGGER.info(Bundle.message("reformat.action.run", virtualFile.path))
                    formatterService.format(virtualFile)
                }
            }
        }
    }
}
