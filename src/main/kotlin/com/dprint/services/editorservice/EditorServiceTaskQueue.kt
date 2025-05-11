package com.dprint.services.editorservice

import com.dprint.i18n.DprintBundle
import com.dprint.services.editorservice.exceptions.ProcessUnavailableException
import com.dprint.utils.errorLogWithConsole
import com.dprint.utils.infoLogWithConsole
import com.dprint.utils.warnLogWithConsole
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.CoroutineContext

enum class TaskType {
    Initialisation,
    PrimeCanFormat,
    Format,
    Cancel,
}

data class TaskInfo(val taskType: TaskType, val path: String?, val formatId: Int?)

private val LOGGER = logger<EditorServiceTaskQueue>()

class EditorServiceTaskQueue(private val project: Project) : CoroutineScope, Disposable {
    private val job = SupervisorJob()
    private val mutex = Mutex() // For synchronized access to activeTasks
    private val activeTasks = HashSet<TaskInfo>()
    private val taskQueue = Channel<Pair<TaskInfo, () -> Unit>>(Channel.UNLIMITED)

    // Use the Application Dispatcher for background operations
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + job

    init {
        // Start the processor that handles queued tasks one by one
        launch {
            for ((taskInfo, task) in taskQueue) {
                try {
                    mutex.withLock {
                        if (!project.isDisposed) activeTasks.add(taskInfo)
                    }

                    withContext(Dispatchers.Default) {
                        runTask(taskInfo, task)
                    }
                } catch (e: Exception) {
                    errorLogWithConsole("Task failed: $taskInfo", e, project, LOGGER)
                } finally {
                    mutex.withLock {
                        activeTasks.remove(taskInfo)
                    }
                }
            }
        }
    }

    private fun runTask(
        taskInfo: TaskInfo,
        operation: () -> Unit,
    ) {
        val progressManager = ProgressManager.getInstance()
        val taskTitle = getTaskTitle(taskInfo)

        progressManager.run(
            object : Task.Backgroundable(project, taskTitle, true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.text = taskTitle
                    infoLogWithConsole(indicator.text, project, LOGGER)
                    operation()
                }
            },
        )
    }

    private fun getTaskTitle(taskInfo: TaskInfo): String {
        return when (taskInfo.taskType) {
            TaskType.Initialisation -> "Initializing Dprint"
            TaskType.PrimeCanFormat -> "Checking formatter availability"
            TaskType.Format -> "Formatting file ${taskInfo.path ?: ""}"
            TaskType.Cancel -> "Cancelling Dprint operations"
        }
    }

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
        onFailure: (() -> Unit)?,
    ) {
        launch {
            // Check if task is already queued
            mutex.withLock {
                if (activeTasks.contains(taskInfo)) {
                    infoLogWithConsole("Task is already queued so this will be dropped: $taskInfo", project, LOGGER)
                    return@launch
                }
            }

            // Queue the task
            taskQueue.send(
                taskInfo to {
                    // Use a countdown latch to wait for operation completion or timeout
                    val latch = CountDownLatch(1)

                    // Use a volatile flag to track completion
                    var operationCompleted = false
                    var operationFailed = false

                    // Create a job for the operation that we can cancel if it times out
                    val operationJob =
                        launch(Dispatchers.Default) {
                            try {
                                operation()
                                operationCompleted = true
                            } catch (e: Exception) {
                                operationFailed = true
                                onFailure?.invoke()
                                when (e) {
                                    is ProcessUnavailableException -> {
                                        warnLogWithConsole(
                                            DprintBundle.message("editor.service.process.is.dead"),
                                            e,
                                            project,
                                            LOGGER,
                                        )
                                    }

                                    is CancellationException -> {
                                        errorLogWithConsole(
                                            "Dprint cancellation: $title",
                                            e,
                                            project,
                                            LOGGER,
                                        )
                                    }

                                    else ->
                                        errorLogWithConsole(
                                            "Dprint execution exception: $title",
                                            e,
                                            project,
                                            LOGGER,
                                        )
                                }
                            } finally {
                                latch.countDown()
                            }
                        }

                    // Wait for either completion or timeout
                    val completed = latch.await(timeout, TimeUnit.MILLISECONDS)

                    // If the operation hasn't completed and hasn't failed, it's a timeout
                    if (!completed && !operationCompleted && !operationFailed) {
                        operationJob.cancel()
                        onFailure?.invoke()
                        errorLogWithConsole("Dprint timeout: $title", TimeoutException(), project, LOGGER)
                    }
                },
            )
        }
    }

    fun isEmpty(): Boolean {
        return runBlocking {
            mutex.withLock {
                activeTasks.isEmpty()
            }
        }
    }
    
    override fun dispose() {
        job.cancel()
    }
}
