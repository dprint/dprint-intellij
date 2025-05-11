package com.dprint.services.editorservice

import com.dprint.i18n.DprintBundle
import com.dprint.services.editorservice.exceptions.ProcessUnavailableException
import com.dprint.utils.errorLogWithConsole
import com.dprint.utils.infoLogWithConsole
import com.dprint.utils.warnLogWithConsole
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

enum class TaskType {
    Initialisation,
    PrimeCanFormat,
    Format,
    Cancel,
}

data class TaskInfo(val taskType: TaskType, val path: String?, val formatId: Int?)

private val LOGGER = logger<EditorServiceTaskQueue>()

class EditorServiceTaskQueue(private val project: Project) : CoroutineScope {
    private val job = SupervisorJob()
    private val mutex = Mutex() // For synchronized access to activeTasks
    private val activeTasks = HashSet<TaskInfo>()
    private val taskQueue = Channel<Pair<TaskInfo, suspend (ProgressIndicator) -> Unit>>(Channel.UNLIMITED)

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
        operation: suspend (ProgressIndicator) -> Unit,
    ) {
        val progressManager = ProgressManager.getInstance()
        val taskTitle = getTaskTitle(taskInfo)

        progressManager.run(
            object : Task.Backgroundable(project, taskTitle, true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.text = taskTitle
                    infoLogWithConsole(indicator.text, project, LOGGER)

                    // Use a CountDownLatch to wait for the coroutine to complete
                    val latch = CountDownLatch(1)

                    // Launch a coroutine to run the operation with access to the indicator
                    launch(Dispatchers.Default) {
                        try {
                            operation(indicator)
                        } finally {
                            latch.countDown()
                        }
                    }

                    // Wait for the operation to complete - this will keep the progress indicator shown
                    latch.await()
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
                taskInfo to { indicator ->
                    // Use thread-safe flags for tracking completion status
                    val operationCompleted = AtomicBoolean(false)
                    val operationFailed = AtomicBoolean(false)

                    // The actual operation coroutine
                    val operationJob =
                        launch(Dispatchers.Default) {
                            try {
                                operation()
                                operationCompleted.set(true)
                            } catch (e: Exception) {
                                operationFailed.set(true)
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
                                        errorLogWithConsole("Dprint cancellation: $title", e, project, LOGGER)
                                    }

                                    else ->
                                        errorLogWithConsole(
                                            "Dprint execution exception: $title",
                                            e,
                                            project,
                                            LOGGER,
                                        )
                                }
                            }
                        }

                    // The timeout coroutine
                    val timeoutJob =
                        launch(Dispatchers.Default) {
                            delay(timeout)
                            if (!operationCompleted.get() && !operationFailed.get() && operationJob.isActive) {
                                operationJob.cancel()
                                onFailure?.invoke()

                                // Update the indicator text to show timeout
                                indicator.text = "$title - Timed out"
                                errorLogWithConsole("Dprint timeout: $title", TimeoutException(), project, LOGGER)
                            }
                        }

                    // Wait for completion of the operation
                    operationJob.join()
                    timeoutJob.cancel()
                },
            )
        }
    }

    fun clear() {
        // Cancel all current tasks
        job.cancel()
    }

    fun isEmpty(): Boolean {
        return runBlocking {
            mutex.withLock {
                activeTasks.isEmpty()
            }
        }
    }

    fun waitForTasksToFinish() {
        runBlocking {
            job.children.forEach { it.join() }
        }
    }

    fun dispose() {
        job.cancel()
    }
}
