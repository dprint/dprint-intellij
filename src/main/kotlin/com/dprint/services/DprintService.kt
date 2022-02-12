package com.dprint.services

import com.dprint.config.ProjectConfiguration
import com.dprint.core.Bundle
import com.dprint.core.FileUtils
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

private const val BUFFER_SIZE = 1024
private const val ZERO = 0
private const val CHECK_COMMAND = 1
private const val FORMAT_COMMAND = 2
private const val U32_BYTE_SIZE = 4
private const val SUPPORTED_SCHEMA_VERSION = 4

private val LOGGER = logger<DprintService>()

// Dprint uses unsigned bytes of 4x255 for the success message and that translates
// to 4x-1 in the jvm's signed bytes.
private val SUCCESS_MESSAGE = byteArrayOf(-1, -1, -1, -1)

/**
 * A command line wrapper to run Dprint.
 */
@Service
class DprintService(private val project: Project) {
    private var editorServiceProcess: Process? = null
    private val notificationService = project.service<NotificationService>()

    /**
     * The resulting state of running the Dprint formatter.
     */
    class DprintResult {
        /**
         * The results of the formatting if successful.
         */
        var formattedContent: String? = null

        /**
         * The error message if formatting was not successful. This can come from custom messages, stderr or stdin.
         */
        var error: String? = null
    }

    /**
     * If enabled, initialises the dprint editor-service so it is ready to format
     */
    fun initialiseEditorService() {
        // If not enabled we don't start the editor service
        if (!project.service<ProjectConfiguration>().state.enabled) return

        AppExecutorUtil.getAppExecutorService().submit {
            val schemaVersion = this.getSchemaVersion()

            val error = when {
                schemaVersion == null -> Bundle.message("config.dprint.schemaVersion.not.found")
                schemaVersion < SUPPORTED_SCHEMA_VERSION -> Bundle.message("config.dprint.schemaVersion.older")
                schemaVersion > SUPPORTED_SCHEMA_VERSION -> Bundle.message("config.dprint.schemaVersion.newer")
                else -> null
            }

            if (error == null) {
                maybeCreateEditorService()
            } else {
                this.notificationService.notifyOfConfigError(error)
            }
        }
    }

    /**
     * Shuts down the editor service and destroys the process.
     */
    fun destroyEditorService() {
        val stdout = this.editorServiceProcess?.inputStream
        val stdin = this.editorServiceProcess?.outputStream
        val stderr = this.editorServiceProcess?.errorStream
        LOGGER.info(Bundle.message("editor.service.shutting.down"))
        stdin?.close()
        LOGGER.info(stdout?.bufferedReader().use { it?.readText() })
        stdout?.close()
        LOGGER.info(stderr?.bufferedReader().use { it?.readText() })
        stderr?.close()
        this.editorServiceProcess?.destroy()
        this.editorServiceProcess = null
    }

    private fun getEditorService(): Process? {
        if (editorServiceProcess?.isAlive == true) {
            return editorServiceProcess
        }

        return maybeCreateEditorService()
    }

    private fun maybeCreateEditorService(): Process? {
        val notificationService = project.service<NotificationService>()
        val executablePath = FileUtils.getValidExecutablePath(this.project)
        val configPath = FileUtils.getValidConfigPath(project)

        if (this.editorServiceProcess != null) {
            this.destroyEditorService()
        }

        when {
            configPath.isNullOrBlank() -> {
                notificationService.notifyOfConfigError(Bundle.message("error.config.path"))
            }
            executablePath.isNullOrBlank() -> {
                notificationService.notifyOfConfigError(Bundle.message("error.executable.path"))
            }
            else -> editorServiceProcess = createEditorService(executablePath, configPath)
        }

        return editorServiceProcess
    }

    private fun createEditorService(executablePath: String, configPath: String): Process {
        val pid = ProcessHandle.current().pid()
        val commandLine = GeneralCommandLine(
            executablePath,
            "editor-service",
            "--config",
            configPath,
            "--parent-pid",
            pid.toString()
        )

        val workingDir = File(configPath).parent

        when {
            workingDir != null -> {
                commandLine.withWorkDirectory(workingDir)
                LOGGER.info(Bundle.message("editor.service.starting", executablePath, configPath, workingDir))
            }
            else -> LOGGER.info(Bundle.message("editor.service.starting.working.dir", executablePath, configPath))
        }

        return commandLine.createProcess()
    }

    private fun writeSuccess(stdin: OutputStream) {
        LOGGER.info(Bundle.message("formatting.sending.success.to.editor.service"))

        stdin.write(SUCCESS_MESSAGE)
        stdin.flush()
    }

    private fun writeInt(stdin: OutputStream, i: Int) {
        val buffer = ByteBuffer.allocate(U32_BYTE_SIZE)
        buffer.putInt(i)

        LOGGER.info(Bundle.message("formatting.sending.to.editor.service", i))

        stdin.write(buffer.array())
        stdin.flush()
    }

    private fun writeString(stdout: InputStream, stdin: OutputStream, string: String) {
        val byteArray = string.encodeToByteArray()
        var pointer = 0

        writeInt(stdin, byteArray.size)
        stdin.flush()

        LOGGER.info(Bundle.message("formatting.sending.to.editor.service", string))

        while (pointer < byteArray.size) {
            if (pointer != 0) {
                readInt(stdout)
            }
            val end = if (byteArray.size - pointer < BUFFER_SIZE) byteArray.size else pointer + BUFFER_SIZE
            val range = IntRange(pointer, end - 1)
            val chunk = byteArray.slice(range).toByteArray()
            stdin.write(chunk)
            stdin.flush()
            pointer = end
        }
    }

    private fun readAndAssertSuccess(stdout: InputStream) {
        val bytes = stdout.readNBytes(U32_BYTE_SIZE)
        for (i in 0 until U32_BYTE_SIZE) {
            assert(bytes[i] == SUCCESS_MESSAGE[i])
        }
        LOGGER.info(Bundle.message("formatting.received.success"))
    }

    private fun readInt(stdout: InputStream): Int {
        val result = ByteBuffer.wrap(stdout.readNBytes(U32_BYTE_SIZE)).int
        LOGGER.info(Bundle.message("formatting.received.value", result))
        return result
    }

    private fun readString(stdout: InputStream, stdin: OutputStream): String {
        val totalBytes = readInt(stdout)
        var result = ByteArray(0)

        var index = 0

        while (index < totalBytes) {
            if (index != 0) {
                writeInt(stdin, ZERO)
            }

            val numBytes = if (totalBytes - index < BUFFER_SIZE) totalBytes - index else BUFFER_SIZE
            val bytes = stdout.readNBytes(numBytes)
            result += bytes
            index += numBytes
        }

        return result.decodeToString()
    }

    // The less generic error is kotlinx.serialization.json.internal.JsonDecodingException and is not accessible
    // unfortunately
    @Suppress("TooGenericExceptionCaught")
    private fun getSchemaVersion(): Int? {
        val executablePath = FileUtils.getValidExecutablePath(this.project)

        val commandLine = GeneralCommandLine(
            executablePath,
            "editor-info",
        )
        val result = ExecUtil.execAndGetOutput(commandLine)

        return try {
            Json.parseToJsonElement(result.stdout).jsonObject["schemaVersion"]?.jsonPrimitive?.int
        } catch (e: RuntimeException) {
            LOGGER.error(Bundle.message("error.failed.to.parse.json.schema", result.stdout, result.stderr), e)
            null
        }
    }

    fun canFormat(filePath: String): Boolean {
        LOGGER.info(Bundle.message("formatting.checking.can.format", filePath))
        getEditorService()?.let { editorService ->
            val stdin = editorService.outputStream
            val stdout = editorService.inputStream

            writeInt(stdin, CHECK_COMMAND)
            writeString(stdout, stdin, filePath)
            writeSuccess(stdin)

            // https://github.com/dprint/dprint/blob/main/docs/editor-extension-development.md
            // this command sequence returns 1 if the file can be formatted
            val status = readInt(stdout)
            readAndAssertSuccess(stdout)
            return status == 1
        }

        return false
    }

    /**
     * This runs dprint using the editor service with the supplied file path and content as stdin.
     * @param filePath The path of the file being formatted. This is needed so the correct dprint configuration file
     * located.
     * @param content The content of the file as a string. This is formatted via Dprint and returned via the result.
     * @return A result object containing the formatted content is successful or an error.
     */
    fun fmt(filePath: String, content: String): DprintResult? {
        val result = DprintResult()

        if (!canFormat(filePath)) {
            result.error = Bundle.message("formatting.cannot.format", filePath)
            return result
        }

        LOGGER.info(Bundle.message("formatting.file", filePath))
        getEditorService()?.let { editorService ->
            val stdin = editorService.outputStream
            val stdout = editorService.inputStream

            writeInt(stdin, FORMAT_COMMAND)
            writeString(stdout, stdin, filePath)
            writeString(stdout, stdin, content)
            writeSuccess(stdin)

            when (readInt(stdout)) {
                0 -> Unit // no-op as content didn't change
                1 -> result.formattedContent = readString(stdout, stdin)
                2 -> result.error = readString(stdout, stdin)
            }

            readAndAssertSuccess(stdout)
        }

        return result
    }
}
