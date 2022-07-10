package com.dprint.services.editorservice

import com.dprint.core.Bundle
import com.dprint.core.FileUtils
import com.dprint.core.LogUtils
import com.dprint.messages.DprintMessage
import com.dprint.services.editorservice.exceptions.ProcessUnavailableException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import org.apache.lucene.util.ThreadInterruptedException
import java.io.File
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import kotlin.concurrent.thread

private const val BUFFER_SIZE = 1024
private const val ZERO = 0
private const val U32_BYTE_SIZE = 4
private const val SLEEP_TIME = 500L

private val LOGGER = logger<EditorProcess>()

// Dprint uses unsigned bytes of 4x255 for the success message and that translates
// to 4x-1 in the jvm's signed bytes.
private val SUCCESS_MESSAGE = byteArrayOf(-1, -1, -1, -1)

class EditorProcess(private val project: Project) {
    private var process: Process? = null
    private var stderrListener: Thread? = null

    fun initialize() {
        val executablePath = FileUtils.getValidExecutablePath(this.project)
        val configPath = FileUtils.getValidConfigPath(project)

        if (this.process != null) {
            destroy()
        }

        when {
            configPath.isNullOrBlank() -> {
                project.messageBus.syncPublisher(DprintMessage.DPRINT_MESSAGE_TOPIC)
                    .info(Bundle.message("error.config.path"))
            }
            executablePath.isNullOrBlank() -> {
                project.messageBus.syncPublisher(DprintMessage.DPRINT_MESSAGE_TOPIC)
                    .info(Bundle.message("error.executable.path"))
            }
            else -> {
                process = createEditorService(executablePath, configPath)
                createStderrListener()
            }
        }
    }

    /**
     * Shuts down the editor service and destroys the process.
     */
    fun destroy() {
        stderrListener?.interrupt()
        // Ensure that we read whatever is left in the error stream before shutting down
        LOGGER.info(process?.errorStream?.bufferedReader().use { if (it?.ready() == true) it.readText() else "" })
        process?.destroy()
        process = null
    }

    @Suppress("TooGenericExceptionCaught")
    private fun createStderrListener() {
        val listener = Runnable {
            while (true) {
                if (Thread.interrupted()) {
                    return@Runnable
                }

                try {
                    process?.errorStream?.bufferedReader()?.let {
                        LogUtils.error("Dprint: ${it.readLine()}}", project, LOGGER)
                    }
                } catch (e: ThreadInterruptedException) {
                    LOGGER.info(e)
                    return@Runnable
                } catch (e: BufferUnderflowException) {
                    // Happens when the editor service is shut down while this thread is waiting to read output
                    LOGGER.info(e)
                } catch (e: Exception) {
                    LogUtils.error("Dprint: stderr reader failed", e, project, LOGGER)
                    Thread.sleep(SLEEP_TIME)
                }
            }
        }
        stderrListener = thread {
            listener.run()
        }
    }

    private fun createEditorService(executablePath: String, configPath: String): Process {
        val pid = ProcessHandle.current().pid()
        val commandLine = GeneralCommandLine(
            executablePath,
            "editor-service",
            "--config",
            configPath,
            "--parent-pid",
            pid.toString(),
            "--verbose" // TODO Make this configurable
        )

        val workingDir = File(configPath).parent

        when {
            workingDir != null -> {
                commandLine.withWorkDirectory(workingDir)
                LogUtils.info(
                    Bundle.message("editor.service.starting", executablePath, configPath, workingDir), project, LOGGER
                )
            }
            else -> LogUtils.info(
                Bundle.message("editor.service.starting.working.dir", executablePath, configPath), project, LOGGER
            )
        }

        return commandLine.createProcess()
    }

    private fun getProcess(): Process {
        return process
            ?: throw ProcessUnavailableException(Bundle.message("editor.process.cannot.get.editor.service.process"))
    }

    fun writeSuccess() {
        LOGGER.debug(Bundle.message("formatting.sending.success.to.editor.service"))
        val stdin = getProcess().outputStream
        stdin.write(SUCCESS_MESSAGE)
        stdin.flush()
    }

    fun writeInt(i: Int) {
        val stdin = getProcess().outputStream

        LOGGER.debug(Bundle.message("formatting.sending.to.editor.service", i))

        val buffer = ByteBuffer.allocate(U32_BYTE_SIZE)
        buffer.putInt(i)
        stdin.write(buffer.array())
        stdin.flush()
    }

    fun writeString(string: String) {
        val stdin = getProcess().outputStream
        val byteArray = string.encodeToByteArray()
        var pointer = 0

        writeInt(byteArray.size)
        stdin.flush()

        while (pointer < byteArray.size) {
            if (pointer != 0) {
                readInt()
            }
            val end = if (byteArray.size - pointer < BUFFER_SIZE) byteArray.size else pointer + BUFFER_SIZE
            val range = IntRange(pointer, end - 1)
            val chunk = byteArray.slice(range).toByteArray()
            stdin.write(chunk)
            stdin.flush()
            pointer = end
        }
    }

    fun readAndAssertSuccess() {
        val stdout = process?.inputStream
        if (stdout != null) {
            val bytes = stdout.readNBytes(U32_BYTE_SIZE)
            for (i in 0 until U32_BYTE_SIZE) {
                assert(bytes[i] == SUCCESS_MESSAGE[i])
            }
            LOGGER.debug(Bundle.message("formatting.received.success"))
        } else {
            LOGGER.debug(Bundle.message("editor.process.cannot.get.editor.service.process"))
            initialize()
        }
    }

    fun readInt(): Int {
        val stdout = getProcess().inputStream
        val result = ByteBuffer.wrap(stdout.readNBytes(U32_BYTE_SIZE)).int
        LOGGER.debug(Bundle.message("formatting.received.value", result))
        return result
    }

    fun readString(): String {
        val stdout = getProcess().inputStream
        val totalBytes = readInt()
        var result = ByteArray(0)

        var index = 0

        while (index < totalBytes) {
            if (index != 0) {
                writeInt(ZERO)
            }

            val numBytes = if (totalBytes - index < BUFFER_SIZE) totalBytes - index else BUFFER_SIZE
            val bytes = stdout.readNBytes(numBytes)
            result += bytes
            index += numBytes
        }

        val decodedResult = result.decodeToString()
        LOGGER.debug(Bundle.message("formatting.received.value", decodedResult))
        return decodedResult
    }

    fun writeBuffer(byteArray: ByteArray) {
        val stdin = getProcess().outputStream
        stdin.write(byteArray)
        stdin.flush()
    }

    fun readBuffer(totalBytes: Int): ByteArray {
        val stdout = getProcess().inputStream
        return stdout.readNBytes(totalBytes)
    }
}
