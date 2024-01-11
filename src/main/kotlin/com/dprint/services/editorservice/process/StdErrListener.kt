package com.dprint.services.editorservice.process

import com.dprint.utils.errorLogWithConsole
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.nio.BufferUnderflowException

private val LOGGER = logger<StdErrListener>()

class StdErrListener(private val project: Project, private val process: Process) {
    fun listen() {
        while (true) {
            if (Thread.interrupted()) {
                return
            }

            try {
                process.errorStream?.bufferedReader()?.readLine()?.let { error ->
                    errorLogWithConsole("Dprint daemon ${process.pid()}: $error", project, LOGGER)
                }
            } catch (e: InterruptedException) {
                LOGGER.info(e)
                return
            } catch (e: BufferUnderflowException) {
                // Happens when the editor service is shut down while this thread is waiting to read output
                LOGGER.info(e)
                return
            } catch (e: Exception) {
                LOGGER.info(e)
                return
            }
        }
    }
}
