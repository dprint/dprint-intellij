package com.dprint.listeners

import com.dprint.config.ProjectConfiguration
import com.dprint.config.UserConfiguration
import com.dprint.core.Bundle
import com.dprint.core.LogUtils
import com.dprint.services.FormatterService
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

private val LOGGER = logger<OnSaveListener>()

/**
 * A listener that looks for events coming from saves. It attempts to format the content of the saved file with Dprint.
 */
class OnSaveListener(private val project: Project) : BulkFileListener {
    private val formatterService = project.service<FormatterService>()

    override fun before(events: MutableList<out VFileEvent>) {
        val enabled = project.service<ProjectConfiguration>().state.enabled
        val runOnSave = project.service<UserConfiguration>().state.runOnSave

        if (!enabled || !runOnSave) return

        for (event in events.filter { it.isFromSave }) {

            event.file?.let {
                if (FileEditorManager.getInstance(project).selectedFiles.contains(it)) {
                    LogUtils.info(Bundle.message("save.action.run", it.path), project, LOGGER)
                    formatterService.format(it)
                }
            }
        }
    }
}
