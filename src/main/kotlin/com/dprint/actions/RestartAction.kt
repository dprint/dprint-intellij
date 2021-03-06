package com.dprint.actions

import com.dprint.config.ProjectConfiguration
import com.dprint.core.Bundle
import com.dprint.core.LogUtils
import com.dprint.services.editorservice.EditorServiceManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger

private val LOGGER = logger<RestartAction>()

/**
 * This action will restart the editor service when invoked
 */
class RestartAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.let { project ->
            if (!project.service<ProjectConfiguration>().state.enabled) return@let
            LogUtils.info(Bundle.message("restart.action.run"), project, LOGGER)
            project.service<EditorServiceManager>().restartEditorService()
        }
    }
}
