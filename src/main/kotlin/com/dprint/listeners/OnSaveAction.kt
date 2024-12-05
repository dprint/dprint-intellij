package com.dprint.listeners

import com.dprint.config.ProjectConfiguration
import com.dprint.config.UserConfiguration
import com.dprint.i18n.DprintBundle
import com.dprint.services.FormatterService
import com.dprint.utils.infoLogWithConsole
import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project

private val LOGGER = logger<OnSaveAction>()

/**
 * This listener sets up format on save functionality.
 */
class OnSaveAction : ActionsOnSaveFileDocumentManagerListener.ActionOnSave() {
    override fun isEnabledForProject(project: Project): Boolean {
        val projectConfig = project.service<ProjectConfiguration>().state
        val userConfig = project.service<UserConfiguration>().state
        return projectConfig.enabled && userConfig.runOnSave
    }

    override fun processDocuments(
        project: Project,
        documents: Array<Document>,
    ) {
        val currentCommandName = CommandProcessor.getInstance().currentCommandName
        if (currentCommandName == ReformatCodeProcessor.getCommandName()) {
            return
        }
        val formatterService = project.service<FormatterService>()
        val manager = FileDocumentManager.getInstance()
        for (document in documents) {
            manager.getFile(document)?.let { vfile ->
                infoLogWithConsole(DprintBundle.message("save.action.run", vfile.path), project, LOGGER)
                formatterService.format(vfile, document)
            }
        }
    }
}
