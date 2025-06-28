package com.dprint.services.editorservice.v5

import com.dprint.config.ProjectConfiguration
import com.dprint.i18n.DprintBundle
import com.dprint.services.editorservice.FormatResult
import com.dprint.services.editorservice.IEditorService
import com.dprint.services.editorservice.process.EditorProcess
import com.dprint.utils.errorLogWithConsole
import com.dprint.utils.infoLogWithConsole
import com.dprint.utils.warnLogWithConsole
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private val LOGGER = logger<EditorServiceV5>()
private const val SHUTDOWN_TIMEOUT = 1000L

@Service(Service.Level.PROJECT)
class EditorServiceV5(private val project: Project) : IEditorService {
    private var stdoutListener: StdoutListener? = null

    private fun createStdoutListener(): StdoutListener {
        val stdoutListener = StdoutListener(project.service<EditorProcess>(), project.service<MessageChannel>())
        stdoutListener.listen()
        return stdoutListener
    }

    override fun initialiseEditorService() {
        infoLogWithConsole(
            DprintBundle.message("editor.service.initialize", getName()),
            project,
            LOGGER,
        )
        dropMessages()
        if (stdoutListener != null) {
            stdoutListener?.dispose()
            stdoutListener = null
        }

        project.service<EditorProcess>().initialize()
        stdoutListener = createStdoutListener()
    }

    override fun dispose() {
        destroyEditorService()
    }

    override fun destroyEditorService() {
        infoLogWithConsole(DprintBundle.message("editor.service.destroy", getName()), project, LOGGER)
        val message = createNewMessage(MessageType.ShutDownProcess)
        try {
            runBlocking {
                withTimeout(SHUTDOWN_TIMEOUT) {
                    launch {
                        project.service<EditorProcess>().writeBuffer(message.build())
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            errorLogWithConsole(DprintBundle.message("editor.service.shutting.down.timed.out"), e, project, LOGGER)
        } finally {
            stdoutListener?.dispose()
            dropMessages()
            project.service<EditorProcess>().destroy()
        }
    }

    override suspend fun canFormat(filePath: String): Boolean? {
        handleStaleMessages()

        infoLogWithConsole(DprintBundle.message("formatting.checking.can.format", filePath), project, LOGGER)
        val message = createNewMessage(MessageType.CanFormat)
        message.addString(filePath)

        val timeout = project.service<ProjectConfiguration>().state.commandTimeout
        project.service<EditorProcess>().writeBuffer(message.build())
        val result = project.service<MessageChannel>().sendRequest(message.id, timeout)
        return handleCanFormatResult(result, filePath)
    }

    private fun handleCanFormatResult(
        result: MessageChannel.Result?,
        filePath: String,
    ): Boolean? {
        if (result == null) {
            return null
        }
        return when {
            (result.type == MessageType.CanFormatResponse && result.data is Boolean) -> {
                result.data
            }

            (result.type == MessageType.ErrorResponse && result.data is String) -> {
                infoLogWithConsole(
                    DprintBundle.message("editor.service.format.check.failed", filePath, result.data),
                    project,
                    LOGGER,
                )
                null
            }

            (result.type === MessageType.Dropped) -> {
                null
            }

            else -> {
                infoLogWithConsole(
                    DprintBundle.message("editor.service.unsupported.message.type", result.type),
                    project,
                    LOGGER,
                )
                null
            }
        }
    }

    /**
     * If we find pending messages we assume there is an issue with the underlying process and try restart. In the event
     * that doesn't work, it is likely there is a problem with the underlying daemon and the IJ process that runs on top
     * of it is not aware of its unhealthy state.
     */
    private fun handleStaleMessages() {
        val messageChannel = project.service<MessageChannel>()
        if (messageChannel.hasStaleRequests()) {
            val removedCount = messageChannel.removeStaleRequests()
            infoLogWithConsole(
                DprintBundle.message("status.stale.requests.removed", removedCount),
                project,
                LOGGER,
            )
            this.initialiseEditorService()
        }
    }

    override suspend fun fmt(
        filePath: String,
        content: String,
        formatId: Int?,
    ): FormatResult {
        return fmt(filePath, content, formatId, null, null)
    }

    override suspend fun fmt(
        filePath: String,
        content: String,
        formatId: Int?,
        startIndex: Int?,
        endIndex: Int?,
    ): FormatResult {
        infoLogWithConsole(DprintBundle.message("formatting.file", filePath), project, LOGGER)
        val message = createFormatMessage(formatId, filePath, content, startIndex, endIndex)
        val timeout = project.service<ProjectConfiguration>().state.commandTimeout

        project.service<EditorProcess>().writeBuffer(message.build())
        val result = project.service<MessageChannel>().sendRequest(message.id, timeout)
        val formatResult: FormatResult = mapResultToFormatResult(result, filePath)

        infoLogWithConsole(
            DprintBundle.message("editor.service.created.formatting.task", filePath, message.id),
            project,
            LOGGER,
        )

        return formatResult
    }

    private fun createFormatMessage(
        formatId: Int?,
        filePath: String,
        content: String,
        startIndex: Int?,
        endIndex: Int?,
    ): OutgoingMessage {
        val outgoingMessage = OutgoingMessage(formatId ?: getNextMessageId(), MessageType.FormatFile)
        outgoingMessage.addString(filePath)

        // Converting string indices to bytes
        val startByteIndex =
            if (startIndex != null) {
                getByteIndex(content, startIndex)
            } else {
                0
            }

        val endByteIndex =
            if (endIndex != null) {
                getByteIndex(content, endIndex)
            } else {
                content.encodeToByteArray().size
            }

        outgoingMessage.addInt(startByteIndex) // for range formatting add starting index
        outgoingMessage.addInt(endByteIndex) // add ending index
        outgoingMessage.addInt(0) // Override config
        outgoingMessage.addString(content)
        return outgoingMessage
    }

    private fun getByteIndex(
        content: String,
        stringIndex: Int,
    ): Int {
        // Handle edge cases
        if (stringIndex <= 0) return 0
        if (stringIndex >= content.length) return content.encodeToByteArray().size

        // Get substring up to the string index and convert to bytes
        return content.substring(0, stringIndex).encodeToByteArray().size
    }

    private fun mapResultToFormatResult(
        result: MessageChannel.Result?,
        filePath: String,
    ): FormatResult {
        if (result == null) {
            return FormatResult()
        }
        return when {
            (result.type == MessageType.FormatFileResponse && result.data is String?) -> {
                val successMessage =
                    when (result.data) {
                        null -> DprintBundle.message("editor.service.format.not.needed", filePath)
                        else -> DprintBundle.message("editor.service.format.succeeded", filePath)
                    }
                infoLogWithConsole(successMessage, project, LOGGER)
                FormatResult(formattedContent = result.data)
            }

            (result.type == MessageType.ErrorResponse && result.data is String) -> {
                warnLogWithConsole(
                    DprintBundle.message("editor.service.format.failed", filePath, result.data),
                    project,
                    LOGGER,
                )
                FormatResult(error = result.data)
            }

            (result.type != MessageType.Dropped) -> {
                val errorMessage = DprintBundle.message("editor.service.unsupported.message.type", result.type)
                warnLogWithConsole(
                    DprintBundle.message("editor.service.format.failed", filePath, errorMessage),
                    project,
                    LOGGER,
                )
                FormatResult(error = errorMessage)
            }

            else -> {
                FormatResult()
            }
        }
    }

    override fun canRangeFormat(): Boolean {
        return false
    }

    override fun canCancelFormat(): Boolean {
        return true
    }

    override fun maybeGetFormatId(): Int {
        return getNextMessageId()
    }

    override fun cancelFormat(formatId: Int) {
        val message = createNewMessage(MessageType.CancelFormat)
        infoLogWithConsole(DprintBundle.message("editor.service.cancel.format", formatId), project, LOGGER)
        message.addInt(formatId)
        runBlocking {
            project.service<EditorProcess>().writeBuffer(message.build())
        }
        project.service<MessageChannel>().cancelRequest(formatId)
    }

    private fun dropMessages() {
        val cancelledIds = project.service<MessageChannel>().cancelAllRequests()
        for (messageId in cancelledIds) {
            infoLogWithConsole(DprintBundle.message("editor.service.clearing.message", messageId), project, LOGGER)
        }
    }

    private fun getName(): String {
        return this::class.java.simpleName
    }
}
