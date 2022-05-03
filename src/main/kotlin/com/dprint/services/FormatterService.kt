package com.dprint.services

import com.dprint.core.Bundle
import com.dprint.services.editorservice.EditorServiceManager
import com.dprint.services.editorservice.FormatResult
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.BackgroundTaskQueue
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

private val LOGGER = logger<FormatterService>()
private const val FORMATTING_TIMEOUT_SECONDS = 10L

/**
 * A project service that handles reading virtual files, formatting their contents and writing the formatted result.
 */
@Service
class FormatterService(private val project: Project) {
    private var editorServiceManager = project.service<EditorServiceManager>()
    private val notificationService = project.service<NotificationService>()
    private val formatTaskQueue = BackgroundTaskQueue(project, Bundle.message("progress.formatting"))

    /**
     * Attempts to format and save a virtual file using Dprint.
     */
    fun format(virtualFile: VirtualFile) {
        val document = getDocument(project, virtualFile)

        executeUnderProgress(
            project,
            Bundle.message("formatting.file", virtualFile.name),
            fun(indicator) {
                val contentRef = Ref.create<String?>()
                val filePathRef = Ref.create<String>()

                ReadAction.run<RuntimeException> {
                    contentRef.set(document?.text)
                    filePathRef.set(virtualFile.path)
                }

                val content = contentRef.get()

                if (content.isNullOrBlank()) return

                try {
                    val editorServiceInstance = editorServiceManager.maybeGetEditorService()
                    if (editorServiceInstance == null) {
                        LOGGER.info(Bundle.message("formatting.service.editor.service.uninitialized"))
                        return
                    }

                    indicator.text = Bundle.message("dprint.formatting.on", filePathRef.get())

                    if (editorServiceInstance.canFormat(filePathRef.get())) {
                        val filePath = filePathRef.get()
                        val resultFuture = CompletableFuture<FormatResult>()
                        val formatHandler: (FormatResult) -> Unit = {
                            resultFuture.complete(it)
                        }

                        editorServiceInstance.fmt(filePath, content, formatHandler)

                        val result = resultFuture.get()

                        result.error?.let {
                            LOGGER.info(Bundle.message("logging.format.failed", filePath, it))
                            notificationService.notifyOfFormatFailure(it)
                        }

                        result.formattedContent?.let {
                            WriteCommandAction.runWriteCommandAction(project) {
                                getDocument(project, virtualFile)?.setText(it)
                            }
                        }
                    } else {
                        Bundle.message("formatting.cannot.format", filePathRef.get())
                    }
                } catch (e: ExecutionException) {
                    // In the event that the editor service times out we kill it and restart
                    LOGGER.error(Bundle.message("error.dprint.failed"), e)
                    notificationService.notify(
                        Bundle.message("error.dprint.failed"),
                        Bundle.message("error.dprint.failed.timeout", FORMATTING_TIMEOUT_SECONDS),
                        NotificationType.ERROR
                    )
                    editorServiceManager.restartEditorService()
                }
            }
        )
    }

    private fun isFileWriteable(project: Project, virtualFile: VirtualFile): Boolean {
        val readonlyStatusHandler = ReadonlyStatusHandler.getInstance(project)
        return !virtualFile.isDirectory &&
            virtualFile.isValid &&
            virtualFile.isInLocalFileSystem &&
            !readonlyStatusHandler.ensureFilesWritable(Collections.singleton(virtualFile)).hasReadonlyFiles()
    }

    private fun getDocument(project: Project, virtualFile: VirtualFile): Document? {
        if (isFileWriteable(project, virtualFile)) {
            PsiManager.getInstance(project).findFile(virtualFile)?.let {
                return PsiDocumentManager.getInstance(project).getDocument(it)
            }
        }

        return null
    }

    private fun executeUnderProgress(project: Project, title: String, handler: (indicator: ProgressIndicator) -> Unit) {
        val task = object : Task.Backgroundable(project, title) {
            override fun run(indicator: ProgressIndicator) {
                handler(indicator)
            }
        }
        this.formatTaskQueue.run(task)
    }
}
