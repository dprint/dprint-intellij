package com.dprint.listeners

import com.dprint.services.editorservice.EditorServiceManager
import com.dprint.toolwindow.Console
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class ProjectStartupListener : ProjectActivity {
    override suspend fun execute(project: Project) {
        val editorServiceManager = project.service<EditorServiceManager>()
        project.service<Console>()
        editorServiceManager.restartEditorService()
    }
}
