package com.dprint.listeners

import com.dprint.services.DprintService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener

/**
 * A listener that initialises dprint when the project opens
 */
class StartupListener(project: Project) : ProjectManagerListener {
    private val dprintService = project.service<DprintService>()

    override fun projectOpened(project: Project) {
        super.projectOpened(project)
        dprintService.initialiseEditorService()
    }
}
