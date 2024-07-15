package com.dprint.services.editorservice

import com.dprint.config.ProjectConfiguration
import com.dprint.i18n.DprintBundle
import com.dprint.messages.DprintMessage
import com.dprint.services.editorservice.v4.EditorServiceV4
import com.dprint.services.editorservice.v5.EditorServiceV5
import com.dprint.utils.errorLogWithConsole
import com.dprint.utils.getValidConfigPath
import com.dprint.utils.getValidExecutablePath
import com.dprint.utils.infoLogWithConsole
import com.dprint.utils.isFormattableFile
import com.dprint.utils.warnLogWithConsole
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.collections4.map.LRUMap
import java.io.File

private val LOGGER = logger<EditorServiceManager>()
private const val SCHEMA_V4 = 4
private const val SCHEMA_V5 = 5

@Service(Service.Level.PROJECT)
class EditorServiceManager(private val project: Project) {
    private var editorService: IEditorService? = null
    private var configPath: String? = null
    private val editorServiceTaskQueue = EditorServiceTaskQueue(project)
    private var canFormatCache = LRUMap<String, Boolean>()

    private var hasAttemptedInitialisation = false

    private fun getSchemaVersion(configPath: String?): Int? {
        val executablePath = getValidExecutablePath(project)
        val timeout = project.service<ProjectConfiguration>().state.initialisationTimeout

        val commandLine =
            GeneralCommandLine(
                executablePath,
                "editor-info",
            )
        configPath?.let {
            val workingDir = File(it).parent
            commandLine.withWorkDirectory(workingDir)
        }

        val result = ExecUtil.execAndGetOutput(commandLine, timeout.toInt())

        return try {
            val jsonText = result.stdout
            infoLogWithConsole(DprintBundle.message("config.dprint.editor.info", jsonText), project, LOGGER)
            Json.parseToJsonElement(jsonText).jsonObject["schemaVersion"]?.jsonPrimitive?.int
        } catch (e: RuntimeException) {
            val stdout = result.stdout.trim()
            val stderr = result.stderr.trim()
            val message =
                when {
                    stdout.isEmpty() && stderr.isNotEmpty() ->
                        DprintBundle.message(
                            "error.failed.to.parse.json.schema.error",
                            result.stderr.trim(),
                        )

                    stdout.isNotEmpty() && stderr.isEmpty() ->
                        DprintBundle.message(
                            "error.failed.to.parse.json.schema.received",
                            result.stdout.trim(),
                        )

                    stdout.isNotEmpty() && stderr.isNotEmpty() ->
                        DprintBundle.message(
                            "error.failed.to.parse.json.schema.received.error",
                            result.stdout.trim(),
                            result.stderr.trim(),
                        )

                    else -> DprintBundle.message("error.failed.to.parse.json.schema")
                }
            errorLogWithConsole(
                message,
                project,
                LOGGER,
            )
            throw e
        }
    }

    /**
     * Gets a cached canFormat result. If a result doesn't exist this will return null and start a request to fill the
     * value in the cache.
     */
    fun canFormatCached(path: String): Boolean? {
        val result = canFormatCache[path]

        if (result == null) {
            warnLogWithConsole(
                DprintBundle.message("editor.service.manager.no.cached.can.format", path),
                project,
                LOGGER,
            )
            primeCanFormatCache(path)
        }

        return result
    }

    /**
     * Primes the canFormat result cache for the passed in virtual file.
     */
    fun primeCanFormatCacheForFile(virtualFile: VirtualFile) {
        // Mainly used for project startup. The file opened listener runs before the ProjectStartUp listener
        if (!hasAttemptedInitialisation) {
            return
        }
        primeCanFormatCache(virtualFile.path)
    }

    private fun primeCanFormatCache(path: String) {
        val timeout = project.service<ProjectConfiguration>().state.commandTimeout
        editorServiceTaskQueue.createTaskWithTimeout(
            TaskInfo(TaskType.PrimeCanFormat, path, null),
            DprintBundle.message("editor.service.manager.priming.can.format.cache", path),
            {
                if (editorService == null) {
                    warnLogWithConsole(DprintBundle.message("editor.service.manager.not.initialised"), project, LOGGER)
                }

                editorService?.canFormat(path) { canFormat ->
                    if (canFormat == null) {
                        infoLogWithConsole("Unable to determine if $path can be formatted.", project, LOGGER)
                    } else {
                        canFormatCache[path] = canFormat
                        infoLogWithConsole("$path ${if (canFormat) "can" else "cannot"} be formatted", project, LOGGER)
                    }
                }
            },
            timeout,
        )
    }

    /**
     * Formats the given file in a background thread and runs the onFinished callback once complete.
     * See [com.dprint.services.editorservice.IEditorService.fmt] for more info on the parameters.
     */
    fun format(
        path: String,
        content: String,
        onFinished: (FormatResult) -> Unit,
    ) {
        format(editorService?.maybeGetFormatId(), path, content, null, null, onFinished)
    }

    /**
     * Formats the given file in a background thread and runs the onFinished callback once complete.
     * See [com.dprint.services.editorservice.IEditorService.fmt] for more info on the parameters.
     */
    fun format(
        formatId: Int?,
        path: String,
        content: String,
        startIndex: Int?,
        endIndex: Int?,
        onFinished: (FormatResult) -> Unit,
    ) {
        val timeout = project.service<ProjectConfiguration>().state.commandTimeout
        editorServiceTaskQueue.createTaskWithTimeout(
            TaskInfo(TaskType.Format, path, formatId),
            DprintBundle.message("editor.service.manager.creating.formatting.task", path),
            {
                if (editorService == null) {
                    warnLogWithConsole(DprintBundle.message("editor.service.manager.not.initialised"), project, LOGGER)
                }
                editorService?.fmt(formatId, path, content, startIndex, endIndex, onFinished)
            },
            timeout,
            {
                onFinished(FormatResult())
            },
        )
    }

    private fun initialiseFreshEditorService(): Boolean {
        hasAttemptedInitialisation = true
        configPath = getValidConfigPath(project)
        val schemaVersion = getSchemaVersion(configPath)
        infoLogWithConsole(
            DprintBundle.message(
                "editor.service.manager.received.schema.version",
                schemaVersion ?: "none",
            ),
            project,
            LOGGER,
        )
        when {
            schemaVersion == null ->
                project.messageBus.syncPublisher(DprintMessage.DPRINT_MESSAGE_TOPIC).info(
                    DprintBundle.message("config.dprint.schemaVersion.not.found"),
                )

            schemaVersion < SCHEMA_V4 ->
                project.messageBus.syncPublisher(DprintMessage.DPRINT_MESSAGE_TOPIC)
                    .info(
                        DprintBundle.message("config.dprint.schemaVersion.older"),
                    )

            schemaVersion == SCHEMA_V4 -> editorService = project.service<EditorServiceV4>()
            schemaVersion == SCHEMA_V5 -> editorService = project.service<EditorServiceV5>()
            schemaVersion > SCHEMA_V5 ->
                infoLogWithConsole(
                    DprintBundle.message("config.dprint.schemaVersion.newer"),
                    project,
                    LOGGER,
                )
        }

        if (editorService == null) {
            return false
        }

        editorService?.initialiseEditorService()
        return true
    }

    fun restartEditorService() {
        if (!project.service<ProjectConfiguration>().state.enabled) {
            return
        }

        // Slightly larger incase the json schema step times out
        val timeout = project.service<ProjectConfiguration>().state.initialisationTimeout
        editorServiceTaskQueue.createTaskWithTimeout(
            TaskInfo(TaskType.Initialisation, null, null),
            DprintBundle.message("editor.service.manager.initialising.editor.service"),
            {
                clearCanFormatCache()
                val initialised = initialiseFreshEditorService()
                if (initialised) {
                    for (virtualFile in FileEditorManager.getInstance(project).openFiles) {
                        if (isFormattableFile(project, virtualFile)) {
                            primeCanFormatCacheForFile(virtualFile)
                        }
                    }
                }
            },
            timeout,
            {
                this.notifyFailedToStart()
            },
        )
    }

    private fun notifyFailedToStart() {
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup("Dprint")
            .createNotification(
                DprintBundle.message(
                    "editor.service.manager.initialising.editor.service.failed.title",
                ),
                DprintBundle.message(
                    "editor.service.manager.initialising.editor.service.failed.content",
                ),
                NotificationType.ERROR,
            )
            .notify(project)
    }

    fun destroyEditorService() {
        editorService?.destroyEditorService()
    }

    fun getConfigPath(): String? {
        return configPath
    }

    fun canRangeFormat(): Boolean {
        return editorService?.canRangeFormat() == true
    }

    fun maybeGetFormatId(): Int? {
        return editorService?.maybeGetFormatId()
    }

    fun cancelFormat(formatId: Int) {
        val timeout = project.service<ProjectConfiguration>().state.commandTimeout
        editorServiceTaskQueue.createTaskWithTimeout(
            TaskInfo(TaskType.Cancel, null, formatId),
            "Cancelling format $formatId",
            { editorService?.cancelFormat(formatId) },
            timeout,
        )
    }

    fun canCancelFormat(): Boolean {
        return editorService?.canCancelFormat() == true
    }

    fun clearCanFormatCache() {
        canFormatCache.clear()
    }
}
