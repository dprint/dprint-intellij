package com.dprint.services.editorservice.process

import com.dprint.utils.errorLogWithConsole
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.nio.BufferUnderflowException
import kotlin.concurrent.thread

private val LOGGER = logger<StdErrListener>()

class StdErrListener(private val project: Project, private val process: Process) {
    private var listenerThread: Thread? = null
    private var disposing = false

    fun listen() {
        disposing = false
        listenerThread =
            thread(start = true) {
                while (true) {
                    if (Thread.interrupted()) {
                        return@thread
                    }

                    try {
                        process.errorStream?.bufferedReader()?.readLine()?.let { error ->
                            errorLogWithConsole("Dprint daemon ${process.pid()}: $error", project, LOGGER)
                        }
                    } catch (e: InterruptedException) {
                        if (!disposing) LOGGER.info(e)
                        return@thread
                    } catch (e: BufferUnderflowException) {
                        // Happens when the editor service is shut down while this thread is waiting to read output
                        if (!disposing) LOGGER.info(e)
                        return@thread
                    } catch (e: Exception) {
                        if (!disposing) LOGGER.info(e)
                        return@thread
                    }
                }
            }
    }

    fun dispose() {
        disposing = true
        listenerThread?.interrupt()
    }
}
