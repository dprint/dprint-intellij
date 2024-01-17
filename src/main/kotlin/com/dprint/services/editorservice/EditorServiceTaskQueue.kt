package com.dprint.services.editorservice

import com.dprint.i18n.DprintBundle
import com.dprint.services.editorservice.exceptions.ProcessUnavailableException
import com.dprint.utils.errorLogWithConsole
import com.dprint.utils.infoLogWithConsole
import com.dprint.utils.warnLogWithConsole
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.BackgroundTaskQueue
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.lang.Exception
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

enum class TaskType {
    Initialisation,
    PrimeCanFormat,
    Format,
    Cancel,
}

data class TaskInfo(val taskType: TaskType, val path: String?, val formatId: Int?)

private val LOGGER = logger<EditorServiceTaskQueue>()

class EditorServiceTaskQueue(private val project: Project) {
    private val taskQueue = BackgroundTaskQueue(project, "Dprint manager task queue")
    private val activeTasks = HashSet<TaskInfo>()

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
        if (activeTasks.contains(taskInfo)) {
            infoLogWithConsole("Task is already queued so this will be dropped: $taskInfo", project, LOGGER)
            return
        }
        activeTasks.add(taskInfo)
        val future = CompletableFuture<Unit>()
        val task =
            object : Task.Backgroundable(project, title, true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.text = title
                    infoLogWithConsole(indicator.text, project, LOGGER)
                    try {
                        future.completeAsync(operation)
                        future.get(timeout, TimeUnit.SECONDS)
                    } catch (e: TimeoutException) {
                        onFailure?.invoke()
                        errorLogWithConsole("Dprint timeout: $title", e, project, LOGGER)
                    } catch (e: ExecutionException) {
                        onFailure?.invoke()
                        if (e.cause is ProcessUnavailableException) {
                            warnLogWithConsole(
                                DprintBundle.message("editor.service.process.is.dead"),
                                e.cause,
                                project,
                                LOGGER,
                            )
                        }
                        errorLogWithConsole("Dprint execution exception: $title", e, project, LOGGER)
                    } catch (e: InterruptedException) {
                        onFailure?.invoke()
                        errorLogWithConsole("Dprint interruption: $title", e, project, LOGGER)
                    } catch (e: CancellationException) {
                        onFailure?.invoke()
                        errorLogWithConsole("Dprint cancellation: $title", e, project, LOGGER)
                    } catch (e: Exception) {
                        onFailure?.invoke()
                        activeTasks.remove(taskInfo)
                        throw e
                    } finally {
                        activeTasks.remove(taskInfo)
                    }
                }

                override fun onCancel() {
                    super.onCancel()
                    future.cancel(true)
                }
            }
        taskQueue.run(task)
    }
}