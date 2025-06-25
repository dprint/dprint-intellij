package com.dprint.services.editorservice.v5

import com.dprint.config.UserConfiguration
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
class EditorServiceV5(val project: Project) : IEditorService {
    private val impl =
        EditorServiceV5Impl(project, EditorProcess(project, project.service<UserConfiguration>()), PendingMessages())

    override fun initialiseEditorService() {
        impl.initialiseEditorService()
    }

    override fun destroyEditorService() {
        impl.destroyEditorService()
    }

    override fun canFormat(
        filePath: String,
        onFinished: (Boolean?) -> Unit,
    ) {
        impl.canFormat(filePath, onFinished)
    }

    override fun canRangeFormat(): Boolean {
        return impl.canRangeFormat()
    }

    override fun fmt(
        formatId: Int?,
        filePath: String,
        content: String,
        startIndex: Int?,
        endIndex: Int?,
        onFinished: (FormatResult) -> Unit,
    ): Int {
        return impl.fmt(formatId, filePath, content, startIndex, endIndex, onFinished)
    }

    override fun canCancelFormat(): Boolean {
        return impl.canCancelFormat()
    }

    override fun maybeGetFormatId(): Int {
        return impl.maybeGetFormatId()
    }

    override fun dispose() {
        impl.dispose()
    }
}

class EditorServiceV5Impl(
    private val project: Project,
    private val editorProcess: EditorProcess,
    private val pendingMessages: PendingMessages,
) : IEditorService {
    private var stdoutListener: StdoutListener? = null

    private fun createStdoutListener(): StdoutListener {
        val stdoutListener = StdoutListener(editorProcess, pendingMessages)
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

        editorProcess.initialize()
        stdoutListener = createStdoutListener()
    }

    override fun dispose() {
        destroyEditorService()
    }

    override fun destroyEditorService() {
        infoLogWithConsole(DprintBundle.message("editor.service.destroy", getName()), project, LOGGER)
        val message = createNewMessage(MessageType.ShutDownProcess)
        stdoutListener?.disposing = true
        try {
            runBlocking {
                withTimeout(SHUTDOWN_TIMEOUT) {
                    launch {
                        editorProcess.writeBuffer(message.build())
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            errorLogWithConsole(DprintBundle.message("editor.service.shutting.down.timed.out"), e, project, LOGGER)
        } finally {
            stdoutListener?.dispose()
            dropMessages()
            editorProcess.destroy()
        }
    }

    override fun canFormat(
        filePath: String,
        onFinished: (Boolean?) -> Unit,
    ) {
        handleStaleMessages()

        infoLogWithConsole(DprintBundle.message("formatting.checking.can.format", filePath), project, LOGGER)
        val message = createNewMessage(MessageType.CanFormat)
        message.addString(filePath)

        val handler: (PendingMessages.Result) -> Unit = {
            handleCanFormatResult(it, onFinished, filePath)
        }

        pendingMessages.store(message.id, handler)
        editorProcess.writeBuffer(message.build())
    }

    private fun handleCanFormatResult(
        result: PendingMessages.Result,
        onFinished: (Boolean?) -> Unit,
        filePath: String,
    ) {
        when {
            (result.type == MessageType.CanFormatResponse && result.data is Boolean) -> {
                onFinished(result.data)
            }

            (result.type == MessageType.ErrorResponse && result.data is String) -> {
                infoLogWithConsole(
                    DprintBundle.message("editor.service.format.check.failed", filePath, result.data),
                    project,
                    LOGGER,
                )
                onFinished(null)
            }

            (result.type === MessageType.Dropped) -> {
                // do nothing
                onFinished(null)
            }

            else -> {
                infoLogWithConsole(
                    DprintBundle.message("editor.service.unsupported.message.type", result.type),
                    project,
                    LOGGER,
                )
                onFinished(null)
            }
        }
    }

    /**
     * If we find stale messages we assume there is an issue with the underlying process and try restart. In the event
     * that doesn't work, it is likely there is a problem with the underlying daemon and the IJ process that runs on top
     * of it is not aware of its unhealthy state.
     */
    private fun handleStaleMessages() {
        if (pendingMessages.hasStaleMessages()) {
            infoLogWithConsole(DprintBundle.message("editor.service.stale.tasks"), project, LOGGER)
            this.initialiseEditorService()
        }
    }

    override fun canRangeFormat(): Boolean {
        // TODO before we can enable this we need to ensure that the formatting indexes passed into fmt are converted
        //  from string index to byte index correctly
        return false
    }

    override fun fmt(
        formatId: Int?,
        filePath: String,
        content: String,
        startIndex: Int?,
        endIndex: Int?,
        onFinished: (FormatResult) -> Unit,
    ): Int {
        infoLogWithConsole(DprintBundle.message("formatting.file", filePath), project, LOGGER)
        val message = createFormatMessage(formatId, filePath, startIndex, endIndex, content)
        val handler: (PendingMessages.Result) -> Unit = {
            val formatResult: FormatResult = mapResultToFormatResult(it, filePath)
            onFinished(formatResult)
        }
        pendingMessages.store(message.id, handler)
        editorProcess.writeBuffer(message.build())

        infoLogWithConsole(
            DprintBundle.message("editor.service.created.formatting.task", filePath, message.id),
            project,
            LOGGER,
        )

        return message.id
    }

    private fun createFormatMessage(
        formatId: Int?,
        filePath: String,
        startIndex: Int?,
        endIndex: Int?,
        content: String,
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
        result: PendingMessages.Result,
        filePath: String,
    ): FormatResult {
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
        editorProcess.writeBuffer(message.build())
        pendingMessages.take(formatId)
    }

    private fun dropMessages() {
        for (message in pendingMessages.drain()) {
            infoLogWithConsole(DprintBundle.message("editor.service.clearing.message", message.first), project, LOGGER)
            message.second(PendingMessages.Result(MessageType.Dropped, null))
        }
    }

    private fun getName(): String {
        return this::class.java.simpleName
    }
}
