package com.dprint.services.editorservice

import com.dprint.core.Bundle
import com.dprint.core.FileUtils
import com.dprint.core.LogUtils
import com.dprint.messages.DprintMessage
import com.dprint.services.editorservice.v4.EditorServiceV4
import com.dprint.services.editorservice.v5.EditorServiceV5
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.BackgroundTaskQueue
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.impl.BackgroundableProcessIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private val LOGGER = logger<EditorServiceManager>()
private const val SCHEMA_V4 = 4
private const val SCHEMA_V5 = 5
private const val PRIME_CACHE_TIMEOUT = 2L

@Service
class EditorServiceManager(private val project: Project) {
    private var editorService: EditorService? = null
    private val taskQueue = BackgroundTaskQueue(project, "Dprint manager task queue")

    init {
        maybeInitialiseEditorService()
    }

    // The less generic error is kotlinx.serialization.json.internal.JsonDecodingException and is not accessible
    // unfortunately
    @Suppress("TooGenericExceptionCaught")
    private fun getSchemaVersion(): Int? {
        val executablePath = FileUtils.getValidExecutablePath(project)

        val commandLine = GeneralCommandLine(
            executablePath,
            "editor-info",
        )
        val result = ExecUtil.execAndGetOutput(commandLine)

        return try {
            val jsonText = result.stdout
            LogUtils.info(Bundle.message("config.dprint.editor.info", jsonText), project, LOGGER)
            Json.parseToJsonElement(jsonText).jsonObject["schemaVersion"]?.jsonPrimitive?.int
        } catch (e: RuntimeException) {
            LogUtils.error(
                Bundle.message("error.failed.to.parse.json.schema", result.stdout, result.stderr),
                e,
                project,
                LOGGER
            )
            null
        }
    }

    private fun maybeInitialiseEditorService() {
        val init = object : Task.Backgroundable(project, "Initializing dprint service", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Getting schema version"
                val schemaVersion = getSchemaVersion()
                indicator.text = "Attempting to initialize editor service"
                LogUtils.info("Received schema version $schemaVersion", project, LOGGER)
                when {
                    schemaVersion == null -> project.messageBus.syncPublisher(DprintMessage.DPRINT_MESSAGE_TOPIC)
                        .info(
                            Bundle.message("config.dprint.schemaVersion.not.found")
                        )
                    schemaVersion < SCHEMA_V4 -> project.messageBus.syncPublisher(DprintMessage.DPRINT_MESSAGE_TOPIC)
                        .info(
                            Bundle.message("config.dprint.schemaVersion.older")
                        )
                    schemaVersion == SCHEMA_V4 -> editorService = project.service<EditorServiceV4>()
                    schemaVersion == SCHEMA_V5 -> editorService = project.service<EditorServiceV5>()
                    schemaVersion > SCHEMA_V5 -> LogUtils.info(
                        Bundle.message("config.dprint.schemaVersion.newer"),
                        project,
                        LOGGER
                    )
                }
                editorService?.initialiseEditorService()
            }
        }

        // Clear can format requests if we are restarting the editor service
        taskQueue.clear()
        taskQueue.run(init, ModalityState.defaultModalityState(), BackgroundableProcessIndicator(init))
    }

    fun primeCanFormatCacheForFile(virtualFile: VirtualFile) {
        val primeCache =
            object : Task.Backgroundable(project, "Priming can format cache for ${virtualFile.path}", false) {
                override fun run(indicator: ProgressIndicator) {
                    CompletableFuture<Boolean>().completeAsync {
                        indicator.text = "Priming can format cache for ${virtualFile.path}"
                        LogUtils.info(indicator.text, project, LOGGER)
                        editorService?.canFormat(virtualFile.path)
                    }.orTimeout(PRIME_CACHE_TIMEOUT, TimeUnit.SECONDS)
                }
            }
        taskQueue.run(primeCache)
    }

    fun maybeGetEditorService(): EditorService? {
        return editorService
    }

    fun restartEditorService() {
        // TODO rather than restarting we should try and see if it is healthy, cancel the pending format, and drain
        //  pending messages if we are on schema version 5
        maybeInitialiseEditorService()
    }

    fun destroyEditorService() {
        editorService?.destroyEditorService()
    }
}
