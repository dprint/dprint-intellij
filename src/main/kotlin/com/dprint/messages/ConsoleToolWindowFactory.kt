package com.dprint.messages

import com.dprint.config.ProjectConfiguration
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.content.ContentFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class Console(val project: Project) {
    val consoleView = ConsoleViewImpl(project, GlobalSearchScope.allScope(project), false, false)

    init {
        with(project.messageBus.connect()) {
            subscribe(
                DprintMessage.DPRINT_MESSAGE_TOPIC,
                DprintMessage.DprintMessageListener {
                    consoleView.print(decorateText(it), ConsoleViewContentType.LOG_INFO_OUTPUT)
                }
            )
        }
    }

    fun decorateText(text: String): String {
        return "${DateTimeFormatter.ISO_DATE_TIME.format(LocalDateTime.now())}:  ${text}\n"
    }
}

class ConsoleToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val console = Console(project)
        val contentFactory = ContentFactory.SERVICE.getInstance()
        val panel = SimpleToolWindowPanel(true, false)
        panel.setContent(console.consoleView.component)
        val content = contentFactory.createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun isApplicable(project: Project): Boolean {
        return project.service<ProjectConfiguration>().state.enabled
    }
}
