package com.dprint.services

import com.dprint.config.ProjectConfiguration
import com.dprint.i18n.DprintBundle
import com.dprint.messages.DprintAction
import com.dprint.services.editorservice.EditorServiceCache
import com.dprint.services.editorservice.EditorServiceInitializer
import com.dprint.services.editorservice.FormatResult
import com.dprint.services.editorservice.IEditorService
import com.dprint.utils.isFormattableFile
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.measureTimeMillis

private val LOGGER = logger<DprintService>()

enum class ServiceState {
    UNINITIALIZED,
    INITIALIZING,
    READY,
    ERROR,
}

data class ServiceStateData(
    val state: ServiceState,
    val editorService: IEditorService? = null,
    val configPath: String? = null,
    val error: String? = null,
)

@Service(Service.Level.PROJECT)
class DprintService(
    private val project: Project,
) : Disposable {
    private val taskExecutor = DprintTaskExecutor(project)
    private val cache = EditorServiceCache(project)
    private val initializer = EditorServiceInitializer(project)

    private val serviceState = AtomicReference(ServiceStateData(ServiceState.UNINITIALIZED))

    val isReady: Boolean
        get() = serviceState.get().state == ServiceState.READY

    /**
     * Gets a cached canFormat result. If a result doesn't exist this will return null and start a request to fill the
     * value in the cache.
     */
    fun canFormatCached(path: String): Boolean? {
        val result = cache.canFormatCached(path)
        if (result == null && isReady) {
            primeCanFormatCache(path)
        }
        return result
    }

    /**
     * Primes the canFormat result cache for the passed in virtual file.
     */
    fun primeCanFormatCacheForFile(virtualFile: VirtualFile) {
        // Only prime cache if service is ready
        if (!isReady) {
            return
        }
        primeCanFormatCache(virtualFile.path)
    }

    private fun primeCanFormatCache(path: String) {
        cache.createPrimeCanFormatTask(path, { serviceState.get().editorService }, taskExecutor)
    }

    /**
     * Formats the given file in a background thread and runs the onFinished callback once complete.
     * This should be used when running processes on the EDT.
     */
    fun format(
        path: String,
        content: String,
        onFinished: (FormatResult) -> Unit,
    ) {
        val service = serviceState.get().editorService
        format(service?.maybeGetFormatId(), path, content, onFinished)
    }

    /**
     * Formats the given file using coroutines and returns the result directly.
     */
    suspend fun formatSuspend(
        path: String,
        content: String,
        formatId: Int?,
    ): FormatResult? {
        val editorService = serviceState.get().editorService
        if (editorService == null) {
            return null
        }

        return try {
            editorService.fmt(path, content, formatId)
        } catch (e: Exception) {
            LOGGER.error(DprintBundle.message("error.format.operation.failed"), e)
            FormatResult()
        }
    }

    /**
     * Formats the given file using coroutines with range formatting support and returns the result directly.
     */
    suspend fun formatSuspend(
        path: String,
        content: String,
        formatId: Int?,
        startIndex: Int?,
        endIndex: Int?,
    ): FormatResult? {
        val editorService = serviceState.get().editorService
        if (editorService == null) {
            return null
        }

        return try {
            editorService.fmt(path, content, formatId, startIndex, endIndex)
        } catch (e: Exception) {
            LOGGER.error(DprintBundle.message("error.range.format.operation.failed"), e)
            FormatResult()
        }
    }

    /**
     * Formats the given file in a background thread and runs the onFinished callback once complete.
     */
    fun format(
        formatId: Int?,
        path: String,
        content: String,
        onFinished: (FormatResult) -> Unit,
    ) {
        val timeout = project.service<ProjectConfiguration>().state.commandTimeout
        taskExecutor.createTaskWithTimeout(
            TaskInfo(
                taskType = TaskType.Format,
                path = path,
                formatId = formatId,
            ),
            DprintBundle.message("editor.service.manager.creating.formatting.task", path),
            {
                val editorService = serviceState.get().editorService
                if (editorService == null) {
                    onFinished(FormatResult())
                    return@createTaskWithTimeout
                }

                DprintAction.publishFormattingStarted(project, path)
                val timeMs =
                    measureTimeMillis {
                        runBlocking {
                            val result = editorService.fmt(path, content, formatId)
                            onFinished(result)
                        }
                    }
                DprintAction.publishFormattingSucceeded(project, path, timeMs)
            },
            timeout,
            {
                onFinished(FormatResult())
                DprintAction.publishFormattingFailed(project, path, it.message)
            },
        )
    }

    fun restartEditorService() {
        serviceState.set(ServiceStateData(ServiceState.INITIALIZING))

        val projectConfig = project.service<ProjectConfiguration>()
        val timeout = projectConfig.state.initialisationTimeout
        taskExecutor.createTaskWithTimeout(
            TaskInfo(
                taskType = TaskType.Initialisation,
                path = null,
                formatId = null,
            ),
            DprintBundle.message("status.initialising.editor.service"),
            {
                cache.clearCanFormatCache()
                val (service, config) = initializer.initialiseFreshEditorService()

                if (service != null && config != null) {
                    serviceState.set(
                        ServiceStateData(
                            state = ServiceState.READY,
                            editorService = service,
                            configPath = config,
                        ),
                    )

                    // Prime cache for all open files
                    for (virtualFile in FileEditorManager.getInstance(project).openFiles) {
                        if (isFormattableFile(project, virtualFile)) {
                            cache.createPrimeCanFormatTask(virtualFile.path, { service }, taskExecutor)
                        }
                    }
                } else {
                    val error =
                        when {
                            service == null -> DprintBundle.message("error.service.initialization.null")
                            config == null -> DprintBundle.message("error.config.path.null")
                            else -> DprintBundle.message("error.unknown.initialization")
                        }
                    serviceState.set(
                        ServiceStateData(
                            state = ServiceState.ERROR,
                            error = error,
                        ),
                    )
                    initializer.notifyFailedToStart()
                }
            },
            timeout,
            {
                serviceState.set(
                    ServiceStateData(
                        state = ServiceState.ERROR,
                        error = DprintBundle.message("error.service.initialization.failed"),
                    ),
                )
                initializer.notifyFailedToStart()
            },
        )
    }

    fun destroyEditorService() {
        LOGGER.info("Destroying editor service for project: ${project.name}")
        try {
            // Get current service before setting state
            val currentService = serviceState.get().editorService

            // Set state to uninitialized first to prevent new operations
            serviceState.set(ServiceStateData(ServiceState.UNINITIALIZED))

            // Clear the cache
            cache.clearCanFormatCache()

            // Destroy the editor service
            currentService?.destroyEditorService()

            LOGGER.info("Successfully destroyed editor service for project: ${project.name}")
        } catch (e: Exception) {
            LOGGER.error("Error destroying editor service for project: ${project.name}", e)
        }
    }

    fun getConfigPath(): String? = serviceState.get().configPath

    fun maybeGetFormatId(): Int? = serviceState.get().editorService?.maybeGetFormatId()

    fun cancelFormat(formatId: Int) {
        val timeout = project.service<ProjectConfiguration>().state.commandTimeout
        taskExecutor.createTaskWithTimeout(
            TaskInfo(
                taskType = TaskType.Cancel,
                path = null,
                formatId = formatId,
            ),
            DprintBundle.message("status.cancelling.format", formatId),
            { serviceState.get().editorService?.cancelFormat(formatId) },
            timeout,
        )
    }

    fun canRangeFormat(): Boolean = serviceState.get().editorService?.canRangeFormat() == true

    fun canCancelFormat(): Boolean = serviceState.get().editorService?.canCancelFormat() == true

    fun clearCanFormatCache() {
        cache.clearCanFormatCache()
    }

    /**
     * Initializes the editor service from an uninitialized state.
     */
    fun initializeEditorService() {
        if (!project.service<ProjectConfiguration>().state.enabled) {
            return // Dprint is disabled
        }
        val currentState = serviceState.get().state
        if (currentState == ServiceState.READY || currentState == ServiceState.INITIALIZING) {
            return // Already initialized or in progress
        }
        restartEditorService()
    }

    // Package-private test helpers
    internal fun setCurrentServiceForTesting(
        service: IEditorService?,
        configPath: String?,
    ) {
        serviceState.set(
            ServiceStateData(
                state = if (service != null) ServiceState.READY else ServiceState.UNINITIALIZED,
                editorService = service,
                configPath = configPath,
            ),
        )
    }

    internal fun setErrorForTesting(error: String) {
        val current = serviceState.get()
        serviceState.set(
            current.copy(
                state = ServiceState.ERROR,
                error = error,
            ),
        )
    }

    internal fun getCurrentServiceForTesting(): IEditorService? = serviceState.get().editorService

    internal fun getLastErrorForTesting(): String? = serviceState.get().error

    internal fun isInitializedForTesting(): Boolean {
        val state = serviceState.get()
        return state.editorService != null || state.state == ServiceState.READY
    }

    internal fun isRestartingForTesting(): Boolean = serviceState.get().state == ServiceState.INITIALIZING

    override fun dispose() {
        LOGGER.info("Disposing DprintService for project: ${project.name}")
        try {
            // Destroy editor service
            destroyEditorService()

            // Dispose task executor
            taskExecutor.dispose()

            LOGGER.info("Successfully disposed DprintService for project: ${project.name}")
        } catch (e: Exception) {
            LOGGER.error("Error disposing DprintService for project: ${project.name}", e)
        }
    }
}
