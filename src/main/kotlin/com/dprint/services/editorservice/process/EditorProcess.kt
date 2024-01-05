package com.dprint.services.editorservice.process

import com.dprint.config.UserConfiguration
import com.dprint.i18n.DprintBundle
import com.dprint.messages.DprintMessage
import com.dprint.services.editorservice.exceptions.ProcessUnavailableException
import com.dprint.utils.getValidConfigPath
import com.dprint.utils.getValidExecutablePath
import com.dprint.utils.infoLogWithConsole
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.ByteBuffer
import kotlin.concurrent.thread

private const val BUFFER_SIZE = 1024
private const val ZERO = 0
private const val U32_BYTE_SIZE = 4

private val LOGGER = logger<EditorProcess>()

// Dprint uses unsigned bytes of 4x255 for the success message and that translates
// to 4x-1 in the jvm's signed bytes.
private val SUCCESS_MESSAGE = byteArrayOf(-1, -1, -1, -1)

class EditorProcess(private val project: Project) {
    private var process: Process? = null
    private var stderrListener: Thread? = null

    fun initialize() {
        val executablePath = getValidExecutablePath(this.project)
        val configPath = getValidConfigPath(project)

        if (this.process != null) {
            destroy()
        }

        when {
            configPath.isNullOrBlank() -> {
                project.messageBus.syncPublisher(DprintMessage.DPRINT_MESSAGE_TOPIC)
                    .info(DprintBundle.message("error.config.path"))
            }

            executablePath.isNullOrBlank() -> {
                project.messageBus.syncPublisher(DprintMessage.DPRINT_MESSAGE_TOPIC)
                    .info(DprintBundle.message("error.executable.path"))
            }

            else -> {
                process = createEditorService(executablePath, configPath)
                process?.let { actualProcess ->
                    actualProcess.onExit().thenApply {
                        destroy()
                    }
                    createStderrListener(actualProcess)
                }
            }
        }
    }

    /**
     * Shuts down the editor service and destroys the process.
     */
    fun destroy() {
        stderrListener?.interrupt()
        process?.destroy()
        process = null
    }

    private fun createStderrListener(actualProcess: Process) {
        stderrListener =
            thread(start = true) {
                StdErrListener(project, actualProcess).listen()
            }
    }

    private fun createEditorService(
        executablePath: String,
        configPath: String,
    ): Process {
        val ijPid = ProcessHandle.current().pid()
        val userConfig = project.service<UserConfiguration>().state

        val args =
            mutableListOf(
                executablePath,
                "editor-service",
                "--config",
                configPath,
                "--parent-pid",
                ijPid.toString(),
            )

        if (userConfig.enableEditorServiceVerboseLogging) args.add("--verbose")

        val commandLine = GeneralCommandLine(args)
        val workingDir = File(configPath).parent

        when {
            workingDir != null -> {
                commandLine.withWorkDirectory(workingDir)
                infoLogWithConsole(
                    DprintBundle.message("editor.service.starting", executablePath, configPath, workingDir),
                    project,
                    LOGGER,
                )
            }

            else ->
                infoLogWithConsole(
                    DprintBundle.message("editor.service.starting.working.dir", executablePath, configPath),
                    project,
                    LOGGER,
                )
        }

        val rtnProcess = commandLine.createProcess()
        val processPid = rtnProcess.pid()
        rtnProcess.onExit().thenApply {
            infoLogWithConsole(
                DprintBundle.message("process.shut.down", processPid),
                project,
                LOGGER,
            )
        }
        return rtnProcess
    }

    private fun getProcess(): Process {
        val boundProcess = process

        if (boundProcess?.isAlive == true) {
            return boundProcess
        }
        throw ProcessUnavailableException(
            DprintBundle.message(
                "editor.process.cannot.get.editor.service.process",
            ),
        )
    }

    fun writeSuccess() {
        LOGGER.debug(DprintBundle.message("formatting.sending.success.to.editor.service"))
        val stdin = getProcess().outputStream
        stdin.write(SUCCESS_MESSAGE)
        stdin.flush()
    }

    fun writeInt(i: Int) {
        val stdin = getProcess().outputStream

        LOGGER.debug(DprintBundle.message("formatting.sending.to.editor.service", i))

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
            LOGGER.debug(DprintBundle.message("formatting.received.success"))
        } else {
            LOGGER.debug(DprintBundle.message("editor.process.cannot.get.editor.service.process"))
            initialize()
        }
    }

    fun readInt(): Int {
        val stdout = getProcess().inputStream
        val result = ByteBuffer.wrap(stdout.readNBytes(U32_BYTE_SIZE)).int
        LOGGER.debug(DprintBundle.message("formatting.received.value", result))
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
        LOGGER.debug(DprintBundle.message("formatting.received.value", decodedResult))
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
