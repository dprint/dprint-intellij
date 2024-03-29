package com.dprint.listeners

import com.dprint.config.ProjectConfiguration
import com.dprint.services.editorservice.EditorServiceManager
import com.dprint.utils.isFormattableFile
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.vfs.VirtualFile

/**
 * This listener will fire a request to get the canFormat status of a file. The result will then be cached in the
 * EditorServiceManager so we don't need to wait for background threads in the main EDT thread.
 */
class FileOpenedListener : FileEditorManagerListener {
    override fun fileOpened(
        source: FileEditorManager,
        file: VirtualFile,
    ) {
        super.fileOpened(source, file)
        val projectConfig = source.project.service<ProjectConfiguration>().state
        if (!projectConfig.enabled ||
            !source.project.isOpen ||
            !source.project.isInitialized ||
            source.project.isDisposed
        ) {
            return
        }

        // We ignore scratch files as they are never part of config
        if (!isFormattableFile(source.project, file)) return

        val manager = source.project.service<EditorServiceManager>()
        manager.primeCanFormatCacheForFile(file)
    }
}
