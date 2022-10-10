package com.dprint.core

import com.dprint.config.ProjectConfiguration
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.intellij.diff.util.DiffUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.ide.scratch.ScratchUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.io.FileReader

private val LOGGER = logger<FileUtils>()
private val DEFAULT_CONFIG_NAMES = listOf(
    "dprint.json",
    ".dprint.json"
)

/**
 * Utils for checking that dprint is configured correctly outside intellij.
 */
object FileUtils {

    /**
     * Validates that a path is a valid json file
     */
    fun validateConfigFile(project: Project, path: String): Boolean {
        val file = File(path)
        return file.exists() &&
            file.extension == "json" &&
            checkIsValidJson(project, path)
    }

    /**
     * Gets a valid config file path
     */
    fun getValidConfigPath(project: Project): String? {
        val config = project.service<ProjectConfiguration>()
        val configuredPath = config.state.configLocation

        when {
            validateConfigFile(project, configuredPath) -> return configuredPath
            configuredPath.isNotBlank() -> LogUtils.info(
                Bundle.message("notification.invalid.config.path"),
                project,
                LOGGER
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
                    file.exists() && checkIsValidJson(project, file.path) -> return file.path
                    file.exists() -> LogUtils.warn(
                        Bundle.message("notification.invalid.default.config", file.path),
                        project,
                        LOGGER
                    )
                }
            }
        }

        LogUtils.info(Bundle.message("notification.config.not.found"), project, LOGGER)

        return null
    }

    /**
     * Helper function to find out if a given virtual file is formattable. Some files,
     * such as scratch files and diff views will never be formattable by dprint, so
     * we use this to identify them early and thus save the trip to the dprint daemon.
     */
    fun isFormattableFile(project: Project, virtualFile: VirtualFile): Boolean {
        val isScratch = ScratchUtil.isScratch(virtualFile)
        if (isScratch) {
            LogUtils.info(Bundle.message("formatting.scratch.files", virtualFile.path), project, LOGGER)
        }

        return virtualFile.isWritable &&
            virtualFile.isInLocalFileSystem &&
            !isScratch &&
            !DiffUtil.isFileWithoutContent(virtualFile)
    }

    private fun checkIsValidJson(project: Project, path: String): Boolean {
        return try {
            JsonParser.parseReader(FileReader(path))
            true
        } catch (e: JsonSyntaxException) {
            LogUtils.error(e.message ?: "Failed to parse config JSON", e, project, LOGGER)
            false
        }
    }

    /**
     * Validates a path ends with 'dprint' or 'dprint.exe' and is executable
     */
    fun validateExecutablePath(path: String): Boolean {
        return path.endsWith(getExecutableFile()) && File(path).canExecute()
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
     * Attempts to get the dprint executable location by checking node modules
     */
    private fun getLocationFromTheNodeModules(basePath: String?): String? {
        basePath?.let {
            val path = "$it/node_modules/dprint/${getExecutableFile()}"
            if (validateExecutablePath(path)) return path
        }
        return null
    }

    private fun getExecutableFile(): String {
        return when (System.getProperty("os.name").lowercase().contains("win")) {
            true -> "dprint.exe"
            false -> "dprint"
        }
    }

    /**
     * Gets a valid dprint executable path. It will try to use the configured path and will fall
     * back to a path that is discoverable via the command line
     *
     * TODO Cache this per session so we only need to get the location from the path once
     */
    fun getValidExecutablePath(project: Project): String? {
        val config = project.service<ProjectConfiguration>()
        val configuredExecutablePath = config.state.executableLocation

        when {
            validateExecutablePath(configuredExecutablePath) -> return configuredExecutablePath
            configuredExecutablePath.isNotBlank() ->
                LogUtils.error(Bundle.message("notification.invalid.executable.path"), project, LOGGER)
        }

        project.basePath?.let { workingDirectory ->
            getLocationFromTheNodeModules(project.basePath)?.let {
                return it
            }
            getLocationFromThePath(workingDirectory)?.let {
                return it
            }
        }

        LogUtils.error(Bundle.message("notification.executable.not.found"), project, LOGGER)

        return null
    }
}
