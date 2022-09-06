package com.dprint.listeners

import com.dprint.config.ProjectConfiguration
import com.dprint.core.Bundle
import com.dprint.core.LogUtils
import com.dprint.services.editorservice.EditorServiceManager
import com.dprint.services.editorservice.FormatResult
import com.intellij.lang.ImportOptimizer
import com.intellij.lang.ImportOptimizer.CollectingInfoRunnable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private val LOGGER = logger<OptimizeJavaImports>()
private const val TIMEOUT = 10L

/**
 * This listener formats Java files when optimize imports called.
 */
class OptimizeJavaImports : ImportOptimizer {

    override fun supports(file: PsiFile): Boolean {
        if (file !is PsiJavaFile) return false

        val project = file.project
        if (!project.service<ProjectConfiguration>().state.enabled) return false

        val documentManager = PsiDocumentManager.getInstance(project)
        val document = documentManager.getDocument(file) ?: return false

        val editorServiceManager = project.service<EditorServiceManager>()
        val fileManager = FileDocumentManager.getInstance()
        return fileManager.getFile(document)?.path?.let { editorServiceManager.canFormatCached(it) } ?: false
    }

    override fun processFile(file: PsiFile): Runnable {
        val project = file.project
        val manager = FileDocumentManager.getInstance()

        val documentManager = PsiDocumentManager.getInstance(project)
        val document = documentManager.getDocument(file) ?: return Runnable { }
        val editorService = project.service<EditorServiceManager>()

        var error: String? = null
        var formattedContent: String? = null
        lateinit var future: CompletableFuture<Unit>
        manager.getFile(document)?.let { virtualFile ->
            LogUtils.info(Bundle.message("optimize.imports.run", virtualFile.path), project, LOGGER)
            val formatHandler: (FormatResult) -> Unit = { formatResult ->
                formatResult.error?.let {
                    error = it
                    LogUtils.warn(Bundle.message("optimize.imports.error", it), project, LOGGER)
                } ?: run {
                    formattedContent = formatResult.formattedContent
                }
            }

            future = editorService.format(virtualFile.path, document.text, formatHandler)
        }
        future.get(TIMEOUT, TimeUnit.SECONDS)

        return object : CollectingInfoRunnable {
            override fun run() {
                formattedContent?.let {
                    WriteCommandAction.runWriteCommandAction(project) {
                        document.setText(it)
                    }
                }
            }

            override fun getUserNotificationInfo(): String {
                return error?.let { Bundle.message("optimize.imports.error", it) }
                    ?: Bundle.message("optimize.imports.successful")
            }
        }
    }
}
