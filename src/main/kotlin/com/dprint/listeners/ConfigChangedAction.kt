package com.dprint.listeners

import com.dprint.config.ProjectConfiguration
import com.dprint.core.Bundle
import com.dprint.core.LogUtils
import com.dprint.services.editorservice.EditorServiceManager
import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project

private val LOGGER = logger<ConfigChangedAction>()

/**
 * This listener restarts the editor service if the config file is updated.
 */
class ConfigChangedAction : ActionsOnSaveFileDocumentManagerListener.ActionOnSave() {

    override fun isEnabledForProject(project: Project): Boolean {
        return project.service<ProjectConfiguration>().state.enabled
    }

    override fun processDocuments(project: Project, documents: Array<out Document>) {
        val editorServiceManager = project.service<EditorServiceManager>()
        val manager = FileDocumentManager.getInstance()
        for (document in documents) {
            manager.getFile(document)?.let {
                if (it.path == editorServiceManager.getConfigPath()) {
                    LogUtils.info(Bundle.message("config.changed.run"), project, LOGGER)
                    editorServiceManager.restartEditorService()
                }
            }
        }
    }
}
