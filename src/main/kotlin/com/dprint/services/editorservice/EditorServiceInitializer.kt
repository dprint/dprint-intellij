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
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private val LOGGER = logger<EditorServiceInitializer>()
private const val SCHEMA_V4 = 4
private const val SCHEMA_V5 = 5

/**
 * Handles initialization and lifecycle management of editor services.
 * Detects schema versions and creates appropriate service implementations.
 */
class EditorServiceInitializer(private val project: Project) {
    private fun getSchemaVersion(configPath: String?): Int? {
        val executablePath = getValidExecutablePath(project)
        if (executablePath == null) {
            errorLogWithConsole(
                DprintBundle.message("error.executable.path"),
                project,
                LOGGER,
            )
            return null
        }

        val timeout = project.service<ProjectConfiguration>().state.initialisationTimeout

        val commandLine =
            GeneralCommandLine(
                executablePath,
                "editor-info",
            )

        // Set working directory - use config directory if available, otherwise use project base path
        val workingDir = configPath?.let { File(it).parent } ?: project.basePath
        workingDir?.let { commandLine.withWorkDirectory(it) }

        val result = ExecUtil.execAndGetOutput(commandLine, timeout.toInt())

        return try {
            val jsonText = result.stdout.trim()
            infoLogWithConsole(DprintBundle.message("config.dprint.editor.info", jsonText), project, LOGGER)

            if (jsonText.isEmpty()) {
                val workingDir = configPath?.let { File(it).parent } ?: project.basePath
                errorLogWithConsole(
                    DprintBundle.message("error.dprint.editor.info.empty", workingDir ?: "unknown"),
                    project,
                    LOGGER,
                )
                return null
            }

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
     * Initializes a fresh editor service based on the detected schema version.
     * Returns the initialized service and config path.
     */
    fun initialiseFreshEditorService(): Pair<IEditorService?, String?> {
        val configPath = getValidConfigPath(project)

        // Log which config file is being used or if none was found
        if (configPath != null) {
            infoLogWithConsole(
                DprintBundle.message("config.dprint.using.config", configPath),
                project,
                LOGGER,
            )
        } else {
            infoLogWithConsole(
                DprintBundle.message("config.dprint.no.config.found", project.basePath ?: "unknown"),
                project,
                LOGGER,
            )
        }

        val schemaVersion = getSchemaVersion(configPath)
        infoLogWithConsole(
            DprintBundle.message(
                "editor.service.manager.received.schema.version",
                schemaVersion ?: "none",
            ),
            project,
            LOGGER,
        )

        val editorService =
            when {
                schemaVersion == null -> {
                    project.messageBus.syncPublisher(DprintMessage.DPRINT_MESSAGE_TOPIC).info(
                        DprintBundle.message("config.dprint.schemaVersion.not.found"),
                    )
                    null
                }

                schemaVersion < SCHEMA_V4 -> {
                    project.messageBus.syncPublisher(DprintMessage.DPRINT_MESSAGE_TOPIC)
                        .info(
                            DprintBundle.message("config.dprint.schemaVersion.older"),
                        )
                    null
                }

                schemaVersion == SCHEMA_V4 -> project.service<EditorServiceV4>()
                schemaVersion == SCHEMA_V5 -> project.service<EditorServiceV5>()
                schemaVersion > SCHEMA_V5 -> {
                    infoLogWithConsole(
                        DprintBundle.message("config.dprint.schemaVersion.newer"),
                        project,
                        LOGGER,
                    )
                    null
                }

                else -> null
            }

        editorService?.initialiseEditorService()
        return Pair(editorService, configPath)
    }

    /**
     * Shows a notification when the editor service fails to start.
     */
    fun notifyFailedToStart() {
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
}
