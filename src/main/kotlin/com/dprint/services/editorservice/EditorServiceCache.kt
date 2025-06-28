package com.dprint.services.editorservice

import com.dprint.config.ProjectConfiguration
import com.dprint.i18n.DprintBundle
import com.dprint.services.DprintTaskExecutor
import com.dprint.services.TaskInfo
import com.dprint.services.TaskType
import com.dprint.utils.warnLogWithConsole
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.runBlocking
import org.apache.commons.collections4.map.LRUMap

private val LOGGER = logger<EditorServiceCache>()

/**
 * Manages caching of canFormat results so that the external formatter can do fast synchronous checks.
 * Uses an LRU cache to limit memory usage while maintaining performance.
 */
class EditorServiceCache(private val project: Project) {
    private var canFormatCache = LRUMap<String, Boolean>()

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
        }

        return result
    }

    /**
     * Stores a canFormat result in the cache.
     */
    fun putCanFormat(
        path: String,
        canFormat: Boolean,
    ) {
        canFormatCache[path] = canFormat
    }

    /**
     * Clears all cached canFormat results.
     */
    fun clearCanFormatCache() {
        canFormatCache.clear()
    }

    /**
     * Creates a task to prime the canFormat cache for the given path.
     * This is used to populate the cache asynchronously.
     */
    fun createPrimeCanFormatTask(
        path: String,
        editorServiceProvider: () -> IEditorService?,
        taskExecutor: DprintTaskExecutor,
    ) {
        val timeout = project.service<ProjectConfiguration>().state.commandTimeout
        taskExecutor.createTaskWithTimeout(
            TaskInfo(
                taskType = TaskType.PrimeCanFormat,
                path = path,
                formatId = null,
            ),
            DprintBundle.message("editor.service.manager.priming.can.format.cache", path),
            {
                val editorService = editorServiceProvider()
                if (editorService == null) {
                    warnLogWithConsole(
                        DprintBundle.message("editor.service.manager.not.initialised"),
                        project,
                        LOGGER,
                    )
                    return@createTaskWithTimeout
                }

                try {
                    runBlocking {
                        val canFormat = editorService.canFormat(path)
                        if (canFormat == null) {
                            com.dprint.utils.infoLogWithConsole(
                                "Unable to determine if $path can be formatted.",
                                project,
                                LOGGER,
                            )
                        } else {
                            putCanFormat(path, canFormat)
                            com.dprint.utils.infoLogWithConsole(
                                "$path ${if (canFormat) "can" else "cannot"} be formatted",
                                project,
                                LOGGER,
                            )
                        }
                    }
                } catch (e: com.dprint.services.editorservice.exceptions.ProcessUnavailableException) {
                    warnLogWithConsole(
                        "Editor service process not ready for $path, will retry on next access",
                        project,
                        LOGGER,
                    )
                } catch (e: Exception) {
                    warnLogWithConsole(
                        "Failed to check if $path can be formatted: ${e.message}",
                        project,
                        LOGGER,
                    )
                }
            },
            timeout,
        )
    }
}
