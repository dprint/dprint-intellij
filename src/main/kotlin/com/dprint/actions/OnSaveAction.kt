package com.dprint.actions

import com.dprint.config.ProjectConfiguration
import com.dprint.core.Bundle
import com.dprint.core.LogUtils
import com.dprint.services.FormatterService
import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project

private val LOGGER = logger<OnSaveAction>()

class OnSaveAction : ActionsOnSaveFileDocumentManagerListener.ActionOnSave() {

    override fun isEnabledForProject(project: Project): Boolean {
        return project.service<ProjectConfiguration>().state.enabled
    }

    override fun processDocuments(project: Project, documents: Array<out Document>) {
        val formatterService = project.service<FormatterService>()
        val manager = FileDocumentManager.getInstance()
        for (document in documents) {
            manager.getFile(document)?.let {
                LogUtils.info(Bundle.message("save.action.run", it.path), project, LOGGER)
                formatterService.format(it)
            }
        }
    }
}