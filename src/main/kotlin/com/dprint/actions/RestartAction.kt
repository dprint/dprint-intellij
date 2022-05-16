package com.dprint.actions

import com.dprint.config.ProjectConfiguration
import com.dprint.core.LogUtils
import com.dprint.services.editorservice.EditorServiceManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger

/**
 * This action will restart the editor service when invoked
 */

private val LOGGER = logger<RestartAction>()

class RestartAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.let { project ->
            if (!project.service<ProjectConfiguration>().state.enabled) return@let
            LogUtils.info("Performing restart action", project, LOGGER)
            project.service<EditorServiceManager>().restartEditorService()
        }
    }
}
