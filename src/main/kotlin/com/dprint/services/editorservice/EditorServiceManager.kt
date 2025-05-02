package com.dprint.services.editorservice

import com.dprint.config.ProjectConfiguration
import com.dprint.i18n.DprintBundle
import com.dprint.messages.DprintMessage
import com.dprint.otel.AttributeKeys
import com.dprint.otel.DprintScope
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
import com.intellij.platform.diagnostic.telemetry.TelemetryManager
import com.intellij.platform.diagnostic.telemetry.helpers.use
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
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
    private val tracer: Tracer = TelemetryManager.getInstance().getTracer(DprintScope.EditorServiceScope)

    private var hasAttemptedInitialisation = false

    private fun getSchemaVersion(configPath: String?): Int? {
        val span =
            tracer.spanBuilder("dprint.get_schema_version")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(AttributeKeys.CONFIG_PATH, configPath ?: "null")
                .startSpan()

        return span.use { scope ->
            val executablePath = getValidExecutablePath(project)
            val timeout = project.service<ProjectConfiguration>().state.initialisationTimeout

            span.setAttribute(AttributeKeys.TIMEOUT_MS, timeout)

            val commandLine =
                GeneralCommandLine(
                    executablePath,
                    "editor-info",
                )
            configPath?.let {
                val workingDir = File(it).parent
                span.setAttribute("working_dir", workingDir)
                commandLine.withWorkDirectory(workingDir)
            }

            span.addEvent(
                "executing_command",
                Attributes.of(
                    AttributeKey.stringKey("command"),
                    commandLine.commandLineString,
                ),
            )

            val cmdSpan =
                tracer.spanBuilder("dprint.exec_editor_info")
                    .setParent(Context.current().with(span))
                    .setSpanKind(SpanKind.CLIENT)
                    .startSpan()

            val result =
                try {
                    val res = ExecUtil.execAndGetOutput(commandLine, timeout.toInt())
                    cmdSpan.setAttribute("exit_code", res.exitCode.toLong())
                    cmdSpan.setAttribute("stdout_length", res.stdout.length.toLong())
                    cmdSpan.setAttribute("stderr_length", res.stderr.length.toLong())
                    res
                } catch (e: Exception) {
                    cmdSpan.recordException(e)
                    cmdSpan.setStatus(StatusCode.ERROR, "Command execution failed: ${e.message}")
                    throw e
                } finally {
                    cmdSpan.end()
                }

            val jsonText = result.stdout
            infoLogWithConsole(DprintBundle.message("config.dprint.editor.info", jsonText), project, LOGGER)
            try {
                val parseSpan =
                    tracer.spanBuilder("dprint.parse_schema_json")
                        .setParent(Context.current().with(span))
                        .startSpan()

                val schemaVersion =
                    try {
                        val version =
                            Json.parseToJsonElement(
                                jsonText,
                            ).jsonObject["schemaVersion"]?.jsonPrimitive?.int
                        parseSpan.setAttribute(AttributeKeys.SCHEMA_VERSION, version?.toLong() ?: -1)
                        version
                    } catch (e: Exception) {
                        parseSpan.recordException(e)
                        parseSpan.setStatus(StatusCode.ERROR, "JSON parsing failed: ${e.message}")
                        throw e
                    } finally {
                        parseSpan.end()
                    }

                span.setAttribute(AttributeKeys.SCHEMA_VERSION, schemaVersion?.toLong() ?: -1)
                span.setStatus(StatusCode.OK)
                schemaVersion
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

                span.recordException(e)
                span.setAttribute("stdout", stdout)
                span.setAttribute("stderr", stderr)
                span.setStatus(StatusCode.ERROR, message)

                errorLogWithConsole(
                    message,
                    project,
                    LOGGER,
                )
                throw e
            }
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
        val span =
            tracer.spanBuilder("dprint.prime_can_format_file")
                .setAttribute(AttributeKeys.FILE_PATH, virtualFile.path)
                .setAttribute("file_name", virtualFile.name)
                .startSpan()

        try {
            // Mainly used for project startup. The file opened listener runs before the ProjectStartUp listener
            if (!hasAttemptedInitialisation) {
                span.setAttribute("initialisation_attempted", false)
                span.addEvent("skipping_not_initialized")
                return
            }

            span.setAttribute("initialisation_attempted", true)
            primeCanFormatCache(virtualFile.path)
        } finally {
            span.end()
        }
    }

    private fun primeCanFormatCache(path: String) {
        val span =
            tracer.spanBuilder("dprint.prime_can_format_cache")
                .setAttribute(AttributeKeys.FILE_PATH, path)
                .startSpan()

        try {
            val timeout = project.service<ProjectConfiguration>().state.commandTimeout
            span.setAttribute(AttributeKeys.TIMEOUT_MS, timeout)

            editorServiceTaskQueue.createTaskWithTimeout(
                TaskInfo(TaskType.PrimeCanFormat, path, null),
                DprintBundle.message("editor.service.manager.priming.can.format.cache", path),
                {
                    val taskSpan =
                        tracer.spanBuilder("dprint.execute_can_format")
                            .setParent(Context.current().with(span))
                            .setSpanKind(SpanKind.CLIENT)
                            .setAttribute(AttributeKeys.FILE_PATH, path)
                            .startSpan()

                    try {
                        if (editorService == null) {
                            taskSpan.setAttribute("editor_service_available", false)
                            taskSpan.addEvent("editor_service_not_initialized")
                            warnLogWithConsole(
                                DprintBundle.message("editor.service.manager.not.initialised"),
                                project,
                                LOGGER,
                            )
                        } else {
                            taskSpan.setAttribute("editor_service_available", true)
                        }

                        editorService?.canFormat(path) { canFormat ->
                            if (canFormat == null) {
                                taskSpan.setAttribute("result_available", false)
                                taskSpan.addEvent("cannot_determine_format_capability")
                                infoLogWithConsole("Unable to determine if $path can be formatted.", project, LOGGER)
                            } else {
                                taskSpan.setAttribute("result_available", true)
                                taskSpan.setAttribute("can_format", canFormat)
                                canFormatCache[path] = canFormat
                                infoLogWithConsole(
                                    "$path ${if (canFormat) "can" else "cannot"} be formatted",
                                    project,
                                    LOGGER,
                                )
                            }
                            taskSpan.end()
                        }
                    } catch (e: Exception) {
                        taskSpan.recordException(e)
                        taskSpan.setStatus(StatusCode.ERROR, "Can format check failed: ${e.message}")
                        taskSpan.end()
                        throw e
                    }
                },
                timeout,
            )
        } finally {
            span.end()
        }
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
        val span =
            tracer.spanBuilder("dprint.format_file")
                .setAttribute(AttributeKeys.FILE_PATH, path)
                .setAttribute(AttributeKeys.CONTENT_LENGTH, content.length.toLong())
                .startSpan()

        try {
            val formatId = editorService?.maybeGetFormatId()
            span.setAttribute(AttributeKeys.FORMATTING_ID, formatId?.toLong() ?: -1)
            span.setAttribute("full_file_format", true)

            // Create a wrapped callback that will record the result
            val wrappedCallback: (FormatResult) -> Unit = { result ->
                val resultSpan =
                    tracer.spanBuilder("dprint.format_callback")
                        .setParent(Context.current().with(span))
                        .setAttribute(AttributeKeys.FILE_PATH, path)
                        .startSpan()

                try {
                    if (result.error != null) {
                        resultSpan.setAttribute("has_error", true)
                        resultSpan.setStatus(StatusCode.ERROR, result.error)
                    } else {
                        resultSpan.setAttribute("has_error", false)
                        resultSpan.setAttribute("content_changed", result.formattedContent != content)
                        if (result.formattedContent != null) {
                            resultSpan.setAttribute("result_length", result.formattedContent.length.toLong())
                        }
                    }

                    onFinished(result)
                } catch (e: Exception) {
                    resultSpan.recordException(e)
                    resultSpan.setStatus(StatusCode.ERROR, "Callback execution failed: ${e.message}")
                    throw e
                } finally {
                    resultSpan.end()
                }
            }

            format(formatId, path, content, null, null, wrappedCallback)
        } finally {
            span.end()
        }
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
        val span =
            tracer.spanBuilder("dprint.editor_service.format_range")
                .setAttribute(AttributeKeys.FILE_PATH, path)
                .setAttribute(AttributeKeys.CONTENT_LENGTH, content.length.toLong())
                .setAttribute(AttributeKeys.FORMATTING_ID, formatId?.toLong() ?: -1)
                .startSpan()

        if (startIndex != null) {
            span.setAttribute(AttributeKeys.RANGE_START, startIndex.toLong())
        }

        if (endIndex != null) {
            span.setAttribute(AttributeKeys.RANGE_END, endIndex.toLong())
        }

        span.setAttribute("is_range_format", startIndex != null && endIndex != null)

        try {
            val timeout = project.service<ProjectConfiguration>().state.commandTimeout
            span.setAttribute(AttributeKeys.TIMEOUT_MS, timeout)

            // Create a wrapped callback that will record the result
            val wrappedCallback: (FormatResult) -> Unit = { result ->
                val resultSpan =
                    tracer.spanBuilder("dprint.format_range_callback")
                        .setParent(Context.current().with(span))
                        .setAttribute(AttributeKeys.FILE_PATH, path)
                        .startSpan()

                try {
                    if (result.error != null) {
                        resultSpan.setAttribute("has_error", true)
                        resultSpan.setStatus(StatusCode.ERROR, result.error)
                    } else {
                        resultSpan.setAttribute("has_error", false)
                        resultSpan.setAttribute("content_changed", result.formattedContent != content)
                        if (result.formattedContent != null) {
                            resultSpan.setAttribute("result_length", result.formattedContent.length.toLong())
                        }
                    }

                    onFinished(result)
                } catch (e: Exception) {
                    resultSpan.recordException(e)
                    resultSpan.setStatus(StatusCode.ERROR, "Callback execution failed: ${e.message}")
                    throw e
                } finally {
                    resultSpan.end()
                }
            }

            editorServiceTaskQueue.createTaskWithTimeout(
                TaskInfo(TaskType.Format, path, formatId),
                DprintBundle.message("editor.service.manager.creating.formatting.task", path),
                {
                    val taskSpan =
                        tracer.spanBuilder("dprint.execute_format")
                            .setParent(Context.current().with(span))
                            .setSpanKind(SpanKind.CLIENT)
                            .setAttribute(AttributeKeys.FILE_PATH, path)
                            .startSpan()

                    try {
                        if (editorService == null) {
                            taskSpan.setAttribute("editor_service_available", false)
                            taskSpan.addEvent("editor_service_not_initialized")
                            warnLogWithConsole(
                                DprintBundle.message("editor.service.manager.not.initialised"),
                                project,
                                LOGGER,
                            )
                            taskSpan.end()
                        } else {
                            taskSpan.setAttribute("editor_service_available", true)
                            editorService?.fmt(formatId, path, content, startIndex, endIndex) { result ->
                                try {
                                    if (result.error != null) {
                                        taskSpan.setAttribute("has_error", true)
                                        taskSpan.setStatus(StatusCode.ERROR, result.error)
                                    } else {
                                        taskSpan.setAttribute("has_error", false)
                                        if (result.formattedContent != null) {
                                            taskSpan.setAttribute(
                                                "result_length",
                                                result.formattedContent.length.toLong(),
                                            )
                                        }
                                    }
                                } finally {
                                    taskSpan.end()
                                }
                                wrappedCallback(result)
                            }
                        }
                    } catch (e: Exception) {
                        taskSpan.recordException(e)
                        taskSpan.setStatus(StatusCode.ERROR, "Format execution failed: ${e.message}")
                        taskSpan.end()
                        throw e
                    }
                },
                timeout,
                {
                    wrappedCallback(FormatResult())
                },
            )
        } finally {
            span.end()
        }
    }

    private fun initialiseFreshEditorService(): Boolean {
        val span =
            tracer.spanBuilder("dprint.initialise_editor_service")
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan()

        try {
            span.makeCurrent().use { scope ->
                hasAttemptedInitialisation = true
                span.setAttribute("has_attempted_initialisation", true)

                configPath = getValidConfigPath(project)
                span.setAttribute(AttributeKeys.CONFIG_PATH, configPath ?: "null")

                val schemaVersion = getSchemaVersion(configPath)

                infoLogWithConsole(
                    DprintBundle.message(
                        "editor.service.manager.received.schema.version",
                        schemaVersion ?: "none",
                    ),
                    project,
                    LOGGER,
                )

                val createServiceSpan =
                    tracer.spanBuilder("dprint.create_editor_service")
                        .setAttribute("schema_version", schemaVersion?.toLong() ?: -1)
                        .startSpan()

                createServiceSpan.use {
                    when {
                        schemaVersion == null -> {
                            createServiceSpan.addEvent("schema_version_not_found")
                            project.messageBus.syncPublisher(DprintMessage.DPRINT_MESSAGE_TOPIC).info(
                                DprintBundle.message("config.dprint.schemaVersion.not.found"),
                            )
                        }

                        schemaVersion < SCHEMA_V4 -> {
                            createServiceSpan.setAttribute("schema_version_supported", false)
                            createServiceSpan.addEvent("schema_version_too_old")
                            project.messageBus.syncPublisher(DprintMessage.DPRINT_MESSAGE_TOPIC)
                                .info(
                                    DprintBundle.message("config.dprint.schemaVersion.older"),
                                )
                        }

                        schemaVersion == SCHEMA_V4 -> {
                            createServiceSpan.setAttribute("schema_version_supported", true)
                            createServiceSpan.setAttribute("service_type", "v4")
                            editorService = project.service<EditorServiceV4>()
                        }

                        schemaVersion == SCHEMA_V5 -> {
                            createServiceSpan.setAttribute("schema_version_supported", true)
                            createServiceSpan.setAttribute("service_type", "v5")
                            editorService = project.service<EditorServiceV5>()
                        }

                        schemaVersion > SCHEMA_V5 -> {
                            createServiceSpan.setAttribute("schema_version_supported", false)
                            createServiceSpan.addEvent("schema_version_too_new")
                            infoLogWithConsole(
                                DprintBundle.message("config.dprint.schemaVersion.newer"),
                                project,
                                LOGGER,
                            )
                        }
                    }

                    createServiceSpan.setAttribute("editor_service_created", editorService != null)
                }

                if (editorService == null) {
                    span.setAttribute("initialisation_success", false)
                    span.addEvent("editor_service_not_created")
                    return false
                }

                val initSpan =
                    tracer.spanBuilder("dprint.initialise_service_instance")
                        .setSpanKind(SpanKind.CLIENT)
                        .startSpan()

                try {
                    editorService?.initialiseEditorService()
                    initSpan.setStatus(StatusCode.OK)
                } catch (e: Exception) {
                    initSpan.recordException(e)
                    initSpan.setStatus(StatusCode.ERROR, "Service initialization failed: ${e.message}")
                    throw e
                } finally {
                    initSpan.end()
                }

                span.setAttribute("initialisation_success", true)
                return true
            }
        } catch (e: Exception) {
            span.recordException(e)
            span.setStatus(StatusCode.ERROR)
            throw e
        } finally {
            span.end()
        }
    }

    fun restartEditorService() {
        val span =
            tracer.spanBuilder("dprint.restart_editor_service")
                .startSpan()

        try {
            if (!project.service<ProjectConfiguration>().state.enabled) {
                span.setAttribute("plugin_enabled", false)
                span.addEvent("skipping_restart_disabled_plugin")
                return
            }

            span.setAttribute("plugin_enabled", true)

            // Slightly larger incase the json schema step times out
            val timeout = project.service<ProjectConfiguration>().state.initialisationTimeout
            span.setAttribute(AttributeKeys.TIMEOUT_MS, timeout)

            editorServiceTaskQueue.createTaskWithTimeout(
                TaskInfo(TaskType.Initialisation, null, null),
                DprintBundle.message("editor.service.manager.initialising.editor.service"),
                {
                    val taskSpan =
                        tracer.spanBuilder("dprint.execute_restart")
                            .setParent(Context.current().with(span))
                            .startSpan()

                    taskSpan.use {
                        clearCanFormatCache()
                        val initialised = initialiseFreshEditorService()
                        taskSpan.setAttribute("initialisation_success", initialised)

                        if (initialised) {
                            val primeSpan =
                                tracer.spanBuilder("dprint.prime_cache_for_open_files")
                                    .startSpan()

                            primeSpan.use {
                                val openFiles = FileEditorManager.getInstance(project).openFiles
                                primeSpan.setAttribute("open_files_count", openFiles.size.toLong())

                                var formattableFilesCount = 0
                                for (virtualFile in openFiles) {
                                    if (isFormattableFile(project, virtualFile)) {
                                        formattableFilesCount++
                                        primeCanFormatCacheForFile(virtualFile)
                                    }
                                }
                                primeSpan.setAttribute("formattable_files_count", formattableFilesCount.toLong())
                            }
                        }

                        taskSpan.setStatus(StatusCode.OK)
                    }
                },
                timeout,
                {
                    this.notifyFailedToStart()
                },
            )

            span.setStatus(StatusCode.OK)
        } catch (e: Exception) {
            span.recordException(e)
            span.setStatus(StatusCode.ERROR)
            throw e
        } finally {
            span.end()
        }
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
        val span =
            tracer.spanBuilder("dprint.destroy_editor_service")
                .startSpan()

        try {
            span.setAttribute("editor_service_available", editorService != null)

            if (editorService != null) {
                span.addEvent("destroying_editor_service")
                editorService?.destroyEditorService()
            } else {
                span.addEvent("no_editor_service_to_destroy")
            }
        } catch (e: Exception) {
            span.recordException(e)
            span.setStatus(StatusCode.ERROR, "Failed to destroy editor service: ${e.message}")
            throw e
        } finally {
            span.end()
        }
    }

    fun getConfigPath(): String? {
        return configPath
    }

    fun canRangeFormat(): Boolean {
        return editorService?.canRangeFormat() == true
    }

    fun maybeGetFormatId(): Int? {
        val span =
            tracer.spanBuilder("dprint.get_format_id")
                .setSpanKind(SpanKind.CLIENT)
                .startSpan()

        span.use {
            val formatId = editorService?.maybeGetFormatId()
            span.setAttribute("editor_service_available", editorService != null)
            if (formatId != null) {
                span.setAttribute(AttributeKeys.FORMATTING_ID, formatId.toLong())
            }
            return formatId
        }
    }

    fun cancelFormat(formatId: Int) {
        val span =
            tracer.spanBuilder("dprint.editor_service.cancel_format")
                .setAttribute(AttributeKeys.FORMATTING_ID, formatId.toLong())
                .startSpan()

        span.use {
            val timeout = project.service<ProjectConfiguration>().state.commandTimeout
            span.setAttribute(AttributeKeys.TIMEOUT_MS, timeout)

            editorServiceTaskQueue.createTaskWithTimeout(
                TaskInfo(TaskType.Cancel, null, formatId),
                "Cancelling format $formatId",
                {
                    val taskSpan =
                        tracer.spanBuilder("dprint.execute_cancel_format")
                            .setParent(Context.current().with(span))
                            .setSpanKind(SpanKind.CLIENT)
                            .setAttribute(AttributeKeys.FORMATTING_ID, formatId.toLong())
                            .startSpan()

                    try {
                        taskSpan.setAttribute("editor_service_available", editorService != null)
                        editorService?.cancelFormat(formatId)
                        taskSpan.setStatus(StatusCode.OK)
                    } catch (e: Exception) {
                        taskSpan.recordException(e)
                        taskSpan.setStatus(StatusCode.ERROR, "Cancel format failed: ${e.message}")
                        throw e
                    } finally {
                        taskSpan.end()
                    }
                },
                timeout,
            )
        }
    }

    fun canCancelFormat(): Boolean {
        return editorService?.canCancelFormat() == true
    }

    fun clearCanFormatCache() {
        canFormatCache.clear()
    }
}
