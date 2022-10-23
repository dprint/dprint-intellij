package com.dprint.actions

import com.dprint.config.ProjectConfiguration
import com.dprint.i18n.DprintBundle
import com.dprint.services.editorservice.EditorServiceManager
import com.dprint.utils.infoLogWithConsole
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger

private val LOGGER = logger<ClearCacheAction>()

/**
 * This action clears the cache of canFormat results. Useful if config changes have been made.
 */
class ClearCacheAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.let {
            val projectConfig = it.service<ProjectConfiguration>().state
            if (!projectConfig.enabled) return@let
            infoLogWithConsole(DprintBundle.message("clear.cache.action.run"), it, LOGGER)
            it.service<EditorServiceManager>().clearCanFormatCache()
        }
    }
}
