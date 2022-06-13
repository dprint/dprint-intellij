package com.dprint.services.editorservice

import com.dprint.config.ProjectConfiguration
import com.dprint.core.Bundle
import com.dprint.core.FileUtils
import com.dprint.core.LogUtils
import com.dprint.messages.DprintMessage
import com.dprint.services.editorservice.v4.EditorServiceV4
import com.dprint.services.editorservice.v5.EditorServiceV5
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.BackgroundTaskQueue
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.ktor.utils.io.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.collections4.map.LRUMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val LOGGER = logger<EditorServiceManager>()
private const val SCHEMA_V4 = 4
private const val SCHEMA_V5 = 5
private const val TIMEOUT = 10L

@Service
class EditorServiceManager(private val project: Project) {
    private var editorService: EditorService? = null
    private val taskQueue = BackgroundTaskQueue(project, "Dprint manager task queue")
    private var canFormatCache = LRUMap<String, Boolean>()

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
        if (!project.service<ProjectConfiguration>().state.enabled) {
            return
        }
        createTaskWithTimeout("Initialising editor service") {
            val schemaVersion = getSchemaVersion()
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

    /**
     * Gets a cached canFormat result. If a result doesn't exist this will return null and start a request to fill the
     * value in the cache.
     */
    fun canFormatCached(path: String): Boolean? {
        val result = canFormatCache[path]

        if (result == null) {
            LogUtils.warn("Did not find cached can format result for $path.", project, LOGGER)
            primeCanFormatCache(path)
        }

        return result
    }

    /**
     * Primes the canFormat result cache for the passed in virtual file.
     */
    fun primeCanFormatCacheForFile(virtualFile: VirtualFile) {
        primeCanFormatCache(virtualFile.path)
    }

    private fun primeCanFormatCache(path: String) {
        createTaskWithTimeout("Priming can format cache for $path") {
            editorService?.canFormat(path) {
                canFormatCache[path] = it
            }
        }
    }

    private fun createTaskWithTimeout(title: String, operation: () -> Unit) {
        createTaskWithTimeout(title, operation, null)
    }

    private fun createTaskWithTimeout(title: String, operation: () -> Unit, onFailure: (() -> Unit)?) {
        val future = CompletableFuture<Unit>()
        val task = object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = title
                LogUtils.info(indicator.text, project, LOGGER)
                try {
                    future.completeAsync(operation)
                    future.get(TIMEOUT, TimeUnit.SECONDS)
                } catch (e: TimeoutException) {
                    if (onFailure != null) onFailure()
                    LogUtils.error("Dprint timeout: $title", e, project, LOGGER)
                } catch (e: ExecutionException) {
                    if (onFailure != null) onFailure()
                    LogUtils.error("Dprint execution exception: $title", e, project, LOGGER)
                } catch (e: InterruptedException) {
                    if (onFailure != null) onFailure()
                    LogUtils.error("Dprint interruption: $title", e, project, LOGGER)
                } catch (e: CancellationException) {
                    if (onFailure != null) onFailure()
                    LogUtils.error("Dprint cancellation: $title", e, project, LOGGER)
                }
            }

            override fun onCancel() {
                super.onCancel()
                future.cancel(true)
            }
        }
        taskQueue.run(task)
    }

    /**
     * Formats the given file in a background thread and runs the onFinished callback once complete.
     * See [com.dprint.services.editorservice.EditorService.fmt] for more info on the parameters.
     */
    fun format(path: String, content: String, onFinished: (FormatResult) -> Unit) {
        format(editorService?.maybeGetFormatId(), path, content, null, null, onFinished)
    }

    /**
     * Formats the given file in a background thread and runs the onFinished callback once complete.
     * See [com.dprint.services.editorservice.EditorService.fmt] for more info on the parameters.
     */
    fun format(
        formatId: Int?,
        path: String,
        content: String,
        startIndex: Int?,
        endIndex: Int?,
        onFinished: (FormatResult) -> Unit
    ) {
        createTaskWithTimeout(
            "Creating formatting task for $path",
            { editorService?.fmt(formatId, path, content, startIndex, endIndex, onFinished) },
            { onFinished(FormatResult()) }
        )
    }

    fun restartEditorService() {
        // TODO rather than restarting we should try and see if it is healthy, cancel the pending format, and drain
        //  pending messages if we are on schema version 5
        maybeInitialiseEditorService()
    }

    fun destroyEditorService() {
        editorService?.destroyEditorService()
    }

    fun canRangeFormat(): Boolean {
        return editorService?.canRangeFormat() == true
    }

    fun maybeGetFormatId(): Int? {
        return editorService?.maybeGetFormatId()
    }

    fun cancelFormat(formatId: Int) {
        editorService?.cancelFormat(formatId)
    }

    fun canCancelFormat(): Boolean {
        return editorService?.canCancelFormat() == true
    }

    fun clearCanFormatCache() {
        canFormatCache.clear()
    }
}
