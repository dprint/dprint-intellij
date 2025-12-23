package com.dprint.utils

import com.dprint.config.ProjectConfiguration
import com.dprint.i18n.DprintBundle
import com.intellij.diff.util.DiffUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.ide.scratch.ScratchUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

// Need this for the IntelliJ logger
private object FileUtils

private val LOGGER = logger<FileUtils>()
private val DEFAULT_CONFIG_NAMES =
    listOf(
        "dprint.json",
        ".dprint.json",
        "dprint.jsonc",
        ".dprint.jsonc",
    )

/*
 * Utils for checking that dprint is configured correctly outside intellij.
 */

/**
 * Validates that a path is a valid json file
 */
fun validateConfigFile(path: String): Boolean {
    val file = File(path)
    return file.exists() && (file.extension == "json" || file.extension == "jsonc")
}

/**
 * Gets a valid config file path
 */
fun getValidConfigPath(project: Project): String? {
    val config = project.service<ProjectConfiguration>()
    val configuredPath = config.state.configLocation

    when {
        validateConfigFile(configuredPath) -> return configuredPath
        configuredPath.isNotBlank() ->
            infoLogWithConsole(
                DprintBundle.message("notification.invalid.config.path"),
                project,
                LOGGER,
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
                file.exists() -> return file.path
                file.exists() ->
                    warnLogWithConsole(
                        DprintBundle.message("notification.invalid.default.config", file.path),
                        project,
                        LOGGER,
                    )
            }
        }
    }

    infoLogWithConsole(DprintBundle.message("notification.config.not.found"), project, LOGGER)

    return null
}

/**
 * Helper function to find out if a given virtual file is formattable. Some files,
 * such as scratch files and diff views will never be formattable by dprint, so
 * we use this to identify them early and thus save the trip to the dprint daemon.
 */
fun isFormattableFile(
    project: Project,
    virtualFile: VirtualFile,
): Boolean {
    val isScratch = ScratchUtil.isScratch(virtualFile)
    if (isScratch) {
        infoLogWithConsole(DprintBundle.message("formatting.scratch.files", virtualFile.path), project, LOGGER)
    }

    return virtualFile.isWritable &&
        virtualFile.isInLocalFileSystem &&
        !isScratch &&
        !DiffUtil.isFileWithoutContent(virtualFile)
}

/**
 * Validates a path ends with 'dprint' or 'dprint.exe' and is executable
 */
fun validateExecutablePath(path: String): Boolean = path.endsWith(getExecutableFile()) && File(path).canExecute()

/**
 * Attempts to get the dprint executable location by checking to see if it is discoverable.
 */
private fun getLocationFromThePath(): String? {
    val args = listOf(if (System.getProperty("os.name").lowercase().contains("win")) "where" else "which", "dprint")
    val commandLine = GeneralCommandLine(args)
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

private fun getExecutableFile(): String =
    when (System.getProperty("os.name").lowercase().contains("win")) {
        true -> "dprint.exe"
        false -> "dprint"
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
            errorLogWithConsole(
                DprintBundle.message("notification.invalid.executable.path"),
                project,
                LOGGER,
            )
    }

    getLocationFromTheNodeModules(project.basePath)?.let {
        return it
    }
    getLocationFromThePath()?.let {
        return it
    }

    errorLogWithConsole(DprintBundle.message("notification.executable.not.found"), project, LOGGER)

    return null
}
