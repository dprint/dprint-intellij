package com.dprint.services.editorservice.process

import com.dprint.config.UserConfiguration
import com.dprint.i18n.DprintBundle
import com.dprint.messages.DprintMessage
import com.dprint.services.editorservice.exceptions.ProcessUnavailableException
import com.dprint.utils.getValidConfigPath
import com.dprint.utils.getValidExecutablePath
import com.dprint.utils.infoLogWithConsole
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

private const val BUFFER_SIZE = 1024
private const val ZERO = 0
private const val U32_BYTE_SIZE = 4

private val LOGGER = logger<EditorProcess>()

// Dprint uses unsigned bytes of 4x255 for the success message and that translates
// to 4x-1 in the jvm's signed bytes.
private val SUCCESS_MESSAGE = byteArrayOf(-1, -1, -1, -1)

@Service(Service.Level.PROJECT)
class EditorProcess(
    private val project: Project,
) {
    private var process: Process? = null
    private var stderrListener: StdErrListener? = null
    private val isDestroyed = AtomicBoolean(true) // Start as destroyed (not alive)

    fun initialize() {
        val executablePath = getValidExecutablePath(project)
        val configPath = getValidConfigPath(project)

        if (this.process != null) {
            destroy()
        }

        when {
            configPath.isNullOrBlank() -> {
                project.messageBus
                    .syncPublisher(DprintMessage.DPRINT_MESSAGE_TOPIC)
                    .info(DprintBundle.message("error.config.path"))
            }

            executablePath.isNullOrBlank() -> {
                project.messageBus
                    .syncPublisher(DprintMessage.DPRINT_MESSAGE_TOPIC)
                    .info(DprintBundle.message("error.executable.path"))
            }

            else -> {
                process = createDprintDaemon(executablePath, configPath)
                process?.let { actualProcess ->
                    actualProcess.onExit().thenApply {
                        destroy()
                    }
                    isDestroyed.set(false)
                    createStderrListener(actualProcess)
                }
            }
        }
    }

    /**
     * Shuts down the editor service and destroys the process.
     * This method is idempotent and safe to call multiple times.
     */
    fun destroy() {
        if (!isDestroyed.compareAndSet(false, true)) {
            // Already destroyed, return early
            return
        }
        stderrListener?.dispose()
        process?.destroy()
        process = null
    }

    fun isAlive(): Boolean = !isDestroyed.get()

    private fun createStderrListener(actualProcess: Process): StdErrListener {
        val stdErrListener = StdErrListener(project, actualProcess)
        stdErrListener.listen()
        return stdErrListener
    }

    private fun createDprintDaemon(
        executablePath: String,
        configPath: String,
    ): Process {
        val ijPid = ProcessHandle.current().pid()

        val args =
            mutableListOf(
                executablePath,
                "editor-service",
                "--config",
                configPath,
                "--parent-pid",
                ijPid.toString(),
            )

        if (project.service<UserConfiguration>().state.enableEditorServiceVerboseLogging) args.add("--verbose")

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
        rtnProcess.onExit().thenApply { exitedProcess ->
            infoLogWithConsole(
                DprintBundle.message("process.shut.down", exitedProcess.pid()),
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

    suspend fun writeSuccess() {
        LOGGER.debug(DprintBundle.message("formatting.sending.success.to.editor.service"))
        withContext(Dispatchers.IO) {
            val stdin = getProcess().outputStream
            stdin.write(SUCCESS_MESSAGE)
            stdin.flush()
        }
    }

    suspend fun writeInt(i: Int) {
        LOGGER.debug(DprintBundle.message("formatting.sending.to.editor.service", i))
        withContext(Dispatchers.IO) {
            val stdin = getProcess().outputStream
            val buffer = ByteBuffer.allocate(U32_BYTE_SIZE)
            buffer.putInt(i)
            stdin.write(buffer.array())
            stdin.flush()
        }
    }

    suspend fun writeString(string: String) {
        val byteArray = string.encodeToByteArray()
        var pointer = 0

        writeInt(byteArray.size)

        while (pointer < byteArray.size) {
            if (pointer != 0) {
                readInt()
            }
            val end = if (byteArray.size - pointer < BUFFER_SIZE) byteArray.size else pointer + BUFFER_SIZE
            val range = IntRange(pointer, end - 1)
            val chunk = byteArray.slice(range).toByteArray()

            withContext(Dispatchers.IO) {
                val stdin = getProcess().outputStream
                stdin.write(chunk)
                stdin.flush()
            }
            pointer = end
        }
    }

    suspend fun readAndAssertSuccess() {
        val stdout = process?.inputStream
        if (stdout != null) {
            withContext(Dispatchers.IO) {
                val bytes = stdout.readNBytes(U32_BYTE_SIZE)
                for (i in 0 until U32_BYTE_SIZE) {
                    assert(bytes[i] == SUCCESS_MESSAGE[i])
                }
            }
            LOGGER.debug(DprintBundle.message("formatting.received.success"))
        } else {
            LOGGER.debug(DprintBundle.message("editor.process.cannot.get.editor.service.process"))
            initialize()
        }
    }

    suspend fun readInt(): Int {
        val result =
            withContext(Dispatchers.IO) {
                val stdout = getProcess().inputStream
                ByteBuffer.wrap(stdout.readNBytes(U32_BYTE_SIZE)).int
            }
        LOGGER.debug(DprintBundle.message("formatting.received.value", result))
        return result
    }

    suspend fun readString(): String {
        val totalBytes = readInt()
        var result = ByteArray(0)
        var index = 0

        while (index < totalBytes) {
            if (index != 0) {
                writeInt(ZERO)
            }

            val numBytes = if (totalBytes - index < BUFFER_SIZE) totalBytes - index else BUFFER_SIZE
            val bytes =
                withContext(Dispatchers.IO) {
                    val stdout = getProcess().inputStream
                    stdout.readNBytes(numBytes)
                }
            result += bytes
            index += numBytes
        }

        val decodedResult = result.decodeToString()
        LOGGER.debug(DprintBundle.message("formatting.received.value", decodedResult))
        return decodedResult
    }

    suspend fun writeBuffer(byteArray: ByteArray) {
        withContext(Dispatchers.IO) {
            val stdin = getProcess().outputStream
            stdin.write(byteArray)
            stdin.flush()
        }
    }

    suspend fun readBuffer(totalBytes: Int): ByteArray =
        withContext(Dispatchers.IO) {
            val stdout = getProcess().inputStream
            stdout.readNBytes(totalBytes)
        }
}
