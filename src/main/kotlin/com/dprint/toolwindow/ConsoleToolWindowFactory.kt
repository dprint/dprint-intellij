package com.dprint.toolwindow

import com.dprint.config.ProjectConfiguration
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class ConsoleToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val console = Console(project)
        val contentFactory = ContentFactory.getInstance()
        val panel = SimpleToolWindowPanel(true, false)
        panel.setContent(console.consoleView.component)
        val content = contentFactory.createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun isApplicable(project: Project): Boolean {
        return project.service<ProjectConfiguration>().state.enabled
    }
}
