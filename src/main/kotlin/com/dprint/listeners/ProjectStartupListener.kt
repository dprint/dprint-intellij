package com.dprint.listeners

import com.dprint.config.ProjectConfiguration
import com.dprint.services.DprintService
import com.dprint.toolwindow.Console
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class ProjectStartupListener : ProjectActivity {
    override suspend fun execute(project: Project) {
        val projectConfig = project.service<ProjectConfiguration>()
        if (!projectConfig.state.enabled) {
            return
        }

        val dprintService = project.service<DprintService>()
        project.service<Console>()
        dprintService.initializeEditorService()
    }
}
