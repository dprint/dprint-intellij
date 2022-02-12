package com.dprint.core

import com.dprint.config.ProjectConfiguration
import com.dprint.services.NotificationService
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.io.File
import java.io.FileReader

private val LOGGER = logger<FileUtils>()
private val DEFAULT_CONFIG_NAMES = listOf(
    "dprint.json",
    ".dprint.json"
)

/**
 * Utils for checking that dprint is configured correctly outside of intellij.
 */
object FileUtils {

    /**
     * Validates that a path is a valid json file
     */
    fun validateConfigFile(path: String): Boolean {
        val file = File(path)
        return file.exists() &&
            file.extension == "json" &&
            checkIsValidJson(path)
    }

    /**
     * Gets a valid config file path
     */
    fun getValidConfigPath(project: Project): String? {
        val config = project.service<ProjectConfiguration>()
        val configuredPath = config.state.configLocation
        val notificationService = project.service<NotificationService>()

        when {
            validateConfigFile(configuredPath) -> return configuredPath
            configuredPath.isNotBlank() -> notificationService.notifyOfConfigError(
                Bundle.message("notification.invalid.config.path"),
            )
        }

        val basePath = project.basePath
        val allDirs = mutableListOf<String>()

        // get all parent directories
        var currentDir = basePath
        while (currentDir != null) {
            allDirs.add(currentDir)
            currentDir = File(currentDir).parent
        }

        // look for the first valid dprint config file by looking in the project base directory and
        // moving up its parents until one is found
        for (dir in allDirs) {
            for (fileName in DEFAULT_CONFIG_NAMES) {
                val file = File(dir, fileName)
                when {
                    file.exists() && checkIsValidJson(file.path) -> return file.path
                    file.exists() -> notificationService.notifyOfConfigError(
                        Bundle.message("notification.invalid.default.config", file.path)
                    )
                }
            }
        }

        notificationService.notifyOfConfigError(Bundle.message("notification.config.not.found"))

        return null
    }

    private fun checkIsValidJson(path: String): Boolean {
        return try {
            JsonParser.parseReader(FileReader(path))
            true
        } catch (e: JsonSyntaxException) {
            LOGGER.error(e)
            false
        }
    }

    /**
     * Validates a path ends with 'dprint' and is executable
     */
    fun validateExecutablePath(path: String): Boolean {
        return path.endsWith("dprint") && File(path).canExecute()
    }

    /**
     * Attempts to get the dprint executable location by checking to see if it is discoverable.
     */
    private fun getLocationFromThePath(workingDirectory: String): String? {
        val commandLine = GeneralCommandLine(
            if (System.getProperty("os.name").lowercase().contains("win")) "where" else "which",
            "dprint"
        )
        commandLine.withWorkDirectory(workingDirectory)
        val output = ExecUtil.execAndGetOutput(commandLine)

        if (output.checkSuccess(LOGGER)) {
            val maybePath = output.stdout.trim()
            if (File(maybePath).canExecute()) {
                return maybePath
            }
        }

        return null
    }

    /**
     * Gets a valid dprint executable path. It will try to use the configured path and will fall
     * back to a path that is discoverable via the command line
     *
     * TODO Cache this per session so we only need to get the location from the path once
     */
    fun getValidExecutablePath(project: Project): String? {
        val config = project.service<ProjectConfiguration>()
        val notificationService = project.service<NotificationService>()
        val configuredExecutablePath = config.state.executableLocation

        when {
            validateExecutablePath(configuredExecutablePath) -> return configuredExecutablePath
            configuredExecutablePath.isNotBlank() -> notificationService.notifyOfConfigError(
                Bundle.message("notification.invalid.executable.path"),
            )
        }

        project.basePath?.let { workingDirectory ->
            getLocationFromThePath(workingDirectory)?.let { executablePath ->
                return executablePath
            }
        }

        notificationService.notifyOfConfigError(
            Bundle.message("notification.executable.not.found")
        )

        return null
    }
}
