package com.dprint.services.editorservice.process

import com.dprint.utils.errorLogWithConsole
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.BufferUnderflowException

private val LOGGER = logger<StdErrListener>()

class StdErrListener(
    private val project: Project,
    private val process: Process,
) {
    private var listenerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun listen() {
        listenerJob =
            scope.launch {
                try {
                    process.errorStream?.bufferedReader()?.use { reader ->
                        while (isActive) {
                            try {
                                val line =
                                    withContext(Dispatchers.IO) {
                                        reader.readLine()
                                    }

                                if (line != null) {
                                    errorLogWithConsole("Dprint daemon ${process.pid()}: $line", project, LOGGER)
                                } else {
                                    // End of stream reached
                                    break
                                }
                            } catch (e: Exception) {
                                if (isActive) {
                                    when (e) {
                                        is BufferUnderflowException -> {
                                            // Happens when the editor service is shut down while reading
                                            LOGGER.info("Buffer underflow while reading stderr", e)
                                        }
                                        else -> {
                                            LOGGER.info("Error reading stderr", e)
                                        }
                                    }
                                }
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        LOGGER.info("Error in stderr listener", e)
                    }
                }
            }
    }

    fun dispose() {
        listenerJob?.cancel()
        scope.cancel()
    }
}
