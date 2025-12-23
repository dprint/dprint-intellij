package com.dprint.services

import com.dprint.i18n.DprintBundle
import com.dprint.utils.errorLogWithConsole
import com.dprint.utils.infoLogWithConsole
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

enum class TaskType {
    Initialisation,
    PrimeCanFormat,
    Format,
    Cancel,
}

data class TaskInfo(
    val taskType: TaskType,
    val path: String?,
    val formatId: Int?,
)

private val LOGGER = logger<DprintTaskExecutor>()

/**
 * Simplified task executor that combines the functionality of EditorServiceTaskQueue
 * and CoroutineBackgroundTaskQueue into a single, more maintainable class.
 */
class DprintTaskExecutor(
    private val project: Project,
) : CoroutineScope,
    Disposable {
    private val job = SupervisorJob()
    private val taskQueue = Channel<QueuedTask>(Channel.UNLIMITED)
    private val activeTasks = HashSet<TaskInfo>()

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + job

    init {
        launch {
            for (task in taskQueue) {
                try {
                    executeTask(task)
                } catch (e: Exception) {
                    LOGGER.error("Task failed: ${task.title}", e)
                }
            }
        }
    }

    data class QueuedTask(
        val taskInfo: TaskInfo,
        val title: String,
        val operation: () -> Unit,
        val timeout: Long,
        val canBeCancelled: Boolean = true,
        val onFailure: ((Throwable) -> Unit)? = null,
    )

    fun createTaskWithTimeout(
        taskInfo: TaskInfo,
        title: String,
        operation: () -> Unit,
        timeout: Long,
    ) {
        createTaskWithTimeout(taskInfo, title, operation, timeout, null)
    }

    fun createTaskWithTimeout(
        taskInfo: TaskInfo,
        title: String,
        operation: () -> Unit,
        timeout: Long,
        onFailure: ((Throwable) -> Unit)?,
    ) {
        if (activeTasks.contains(taskInfo)) {
            infoLogWithConsole(DprintBundle.message("error.task.already.queued", taskInfo), project, LOGGER)
            return
        }
        activeTasks.add(taskInfo)

        val task =
            QueuedTask(
                taskInfo = taskInfo,
                title = title,
                operation = {
                    try {
                        operation()
                    } finally {
                        // Always remove from active tasks, whether success or failure
                        activeTasks.remove(taskInfo)
                    }
                },
                timeout = timeout,
                canBeCancelled = true,
                onFailure = { e ->
                    onFailure?.invoke(e)
                    errorLogWithConsole(DprintBundle.message("error.unexpected", title), e, project, LOGGER)
                },
            )

        launch {
            taskQueue.send(task)
        }
    }

    private fun executeTask(queueTask: QueuedTask) {
        val task =
            object : Task.Backgroundable(project, queueTask.title, queueTask.canBeCancelled) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.text = queueTask.title
                    LOGGER.info(DprintBundle.message("status.task.executor.running", queueTask.title))

                    runBlocking {
                        try {
                            withTimeout(queueTask.timeout.milliseconds) {
                                withContext(Dispatchers.Default) {
                                    queueTask.operation()
                                }
                            }
                        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                            queueTask.onFailure?.invoke(
                                java.util.concurrent.TimeoutException(
                                    DprintBundle.message("error.operation.timeout", queueTask.timeout),
                                ),
                            )
                            LOGGER.error("Timeout: ${queueTask.title}", e)
                        } catch (e: Exception) {
                            queueTask.onFailure?.invoke(e)
                            LOGGER.error("Exception in task: ${queueTask.title}", e)
                        }
                    }
                }
            }

        ProgressManager.getInstance().run(task)
    }

    override fun dispose() {
        LOGGER.info("Disposing DprintTaskExecutor for project: ${project.name}")
        try {
            // Clear active tasks
            activeTasks.clear()

            // Close the task queue
            taskQueue.close()

            // Cancel the coroutine job with a descriptive message
            job.cancel(DprintBundle.message("error.task.executor.disposed"))

            LOGGER.info("Successfully disposed DprintTaskExecutor for project: ${project.name}")
        } catch (e: Exception) {
            LOGGER.error("Error disposing DprintTaskExecutor for project: ${project.name}", e)
        }
    }
}
