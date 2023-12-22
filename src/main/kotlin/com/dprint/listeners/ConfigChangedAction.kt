package com.dprint.listeners

import com.dprint.config.ProjectConfiguration
import com.dprint.i18n.DprintBundle
import com.dprint.services.editorservice.EditorServiceManager
import com.dprint.utils.infoLogWithConsole
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

    override fun processDocuments(
        project: Project,
        documents: Array<Document?>,
    ) {
        val editorServiceManager = project.service<EditorServiceManager>()
        val manager = FileDocumentManager.getInstance()
        for (document in documents) {
            if (document == null) {
                continue
            }
            manager.getFile(document)?.let { vfile ->
                if (vfile.path == editorServiceManager.getConfigPath()) {
                    infoLogWithConsole(DprintBundle.message("config.changed.run"), project, LOGGER)
                    editorServiceManager.restartEditorService()
                }
            }
        }
    }
}
