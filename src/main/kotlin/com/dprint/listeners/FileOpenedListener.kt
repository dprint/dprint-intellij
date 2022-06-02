package com.dprint.listeners

import com.dprint.services.editorservice.EditorServiceManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.vfs.VirtualFile

class FileOpenedListener : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        super.fileOpened(source, file)
        val manager = source.project.service<EditorServiceManager>()
        manager.primeCanFormatCacheForFile(file)
    }
}
