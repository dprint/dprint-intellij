package com.dprint.toolwindow

import com.dprint.messages.DprintMessage
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Service(Service.Level.PROJECT)
class Console(
    val project: Project,
) {
    val consoleView = ConsoleViewImpl(project, GlobalSearchScope.allScope(project), false, false)

    init {
        with(project.messageBus.connect()) {
            subscribe(
                DprintMessage.DPRINT_MESSAGE_TOPIC,
                object : DprintMessage.Listener {
                    override fun info(message: String) {
                        consoleView.print(decorateText(message), ConsoleViewContentType.LOG_INFO_OUTPUT)
                    }

                    override fun warn(message: String) {
                        consoleView.print(decorateText(message), ConsoleViewContentType.LOG_WARNING_OUTPUT)
                    }

                    override fun error(message: String) {
                        consoleView.print(decorateText(message), ConsoleViewContentType.LOG_ERROR_OUTPUT)
                    }
                },
            )
        }
    }

    private fun decorateText(text: String): String =
        "${DateTimeFormatter.ofPattern("yyyy MM dd HH:mm:ss").format(LocalDateTime.now())}:  ${text}\n"
}
