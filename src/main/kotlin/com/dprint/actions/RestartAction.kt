package com.dprint.actions

import com.dprint.config.ProjectConfiguration
import com.dprint.i18n.DprintBundle
import com.dprint.services.DprintService
import com.dprint.utils.infoLogWithConsole
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
        event.project?.let {
            val enabled = it.service<ProjectConfiguration>().state.enabled
            if (!enabled) return@let
            infoLogWithConsole(DprintBundle.message("restart.action.run"), it, LOGGER)
            it.service<DprintService>().restartEditorService()
        }
    }
}
