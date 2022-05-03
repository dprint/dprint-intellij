package com.dprint.actions

import com.dprint.config.ProjectConfiguration
import com.dprint.services.editorservice.EditorServiceManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service

/**
 * This action will restart the editor service when invoked
 */
class RestartAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.let { project ->
            if (!project.service<ProjectConfiguration>().state.enabled) return@let
            project.service<EditorServiceManager>().restartEditorService()
        }
    }
}
