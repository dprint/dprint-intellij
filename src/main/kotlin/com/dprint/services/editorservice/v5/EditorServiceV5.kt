package com.dprint.services.editorservice.v5

import com.dprint.i18n.DprintBundle
import com.dprint.services.editorservice.EditorProcess
import com.dprint.services.editorservice.EditorService
import com.dprint.services.editorservice.FormatResult
import com.dprint.services.editorservice.exceptions.ProcessUnavailableException
import com.dprint.utils.errorLogWithConsole
import com.dprint.utils.infoLogWithConsole
import com.dprint.utils.warnLogWithConsole
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.thread

private val LOGGER = logger<EditorServiceV5>()
private const val SHUTDOWN_TIMEOUT = 1000L

@Service(Service.Level.PROJECT)
class EditorServiceV5(val project: Project) : EditorService {
    private var editorProcess = EditorProcess(project)
    private var stdoutListener: Thread? = null
    private val pendingMessages = PendingMessages()

    private fun createStdoutListener(): Thread {
        return thread {
            StdoutListener(editorProcess, pendingMessages).run()
        }
    }

    override fun initialiseEditorService() {
        infoLogWithConsole(
            DprintBundle.message("editor.service.initialize", getName()),
            project,
            LOGGER,
        )
        dropMessages()
        if (stdoutListener != null) {
            stdoutListener?.interrupt()
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

        try {
            runBlocking {
                withTimeout(SHUTDOWN_TIMEOUT) {
                    launch {
                        try {
                            editorProcess.writeBuffer(message.build())
                        } catch (e: ProcessUnavailableException) {
                            LOGGER.warn(e)
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            errorLogWithConsole(DprintBundle.message("editor.service.shutting.down.timed.out"), e, project, LOGGER)
        } finally {
            stdoutListener?.interrupt()
            dropMessages()
            editorProcess.destroy()
        }
    }

    override fun canFormat(
        filePath: String,
        onFinished: (Boolean) -> Unit,
    ) {
        handleStaleMessages()

        infoLogWithConsole(DprintBundle.message("formatting.checking.can.format", filePath), project, LOGGER)
        val message = createNewMessage(MessageType.CanFormat)
        message.addString(filePath)

        val handler: (PendingMessages.Result) -> Unit = {
            if (it.type == MessageType.CanFormatResponse && it.data is Boolean) {
                onFinished(it.data)
            } else if (it.type == MessageType.ErrorResponse && it.data is String) {
                infoLogWithConsole(
                    DprintBundle.message("editor.service.format.check.failed", filePath, it.data),
                    project,
                    LOGGER,
                )
            } else if (it.type === MessageType.Dropped) {
                // do nothing
            } else {
                infoLogWithConsole(
                    DprintBundle.message("editor.service.unsupported.message.type", it.type),
                    project,
                    LOGGER,
                )
            }
        }

        pendingMessages.store(message.id, handler)

        try {
            editorProcess.writeBuffer(message.build())
        } catch (e: ProcessUnavailableException) {
            LOGGER.warn(e)
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
        val message = Message(formatId ?: getNextMessageId(), MessageType.FormatFile)
        message.addString(filePath)
        // TODO We need to properly handle string index to byte index here
        message.addInt(startIndex ?: 0) // for range formatting add starting index
        message.addInt(endIndex ?: content.encodeToByteArray().size) // add ending index
        message.addInt(0) // Override config
        message.addString(content)

        val handler: (PendingMessages.Result) -> Unit = {
            val formatResult = FormatResult()
            if (it.type == MessageType.FormatFileResponse && it.data is String?) {
                val successMessage =
                    when (it.data) {
                        null -> DprintBundle.message("editor.service.format.not.needed", filePath)
                        else -> DprintBundle.message("editor.service.format.succeeded", filePath)
                    }
                infoLogWithConsole(successMessage, project, LOGGER)
                formatResult.formattedContent = it.data
            } else if (it.type == MessageType.ErrorResponse && it.data is String) {
                warnLogWithConsole(
                    DprintBundle.message("editor.service.format.failed", filePath, it.data),
                    project,
                    LOGGER,
                )
                formatResult.error = it.data
            } else if (it.type != MessageType.Dropped) {
                val errorMessage = DprintBundle.message("editor.service.unsupported.message.type", it.type)
                warnLogWithConsole(
                    DprintBundle.message("editor.service.format.failed", filePath, errorMessage),
                    project,
                    LOGGER,
                )
                formatResult.error = errorMessage
            }
            onFinished(formatResult)
        }
        pendingMessages.store(message.id, handler)

        try {
            editorProcess.writeBuffer(message.build())
        } catch (e: ProcessUnavailableException) {
            LOGGER.warn(e)
        }

        infoLogWithConsole(
            DprintBundle.message("editor.service.created.formatting.task", filePath, message.id),
            project,
            LOGGER,
        )

        return message.id
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
        try {
            editorProcess.writeBuffer(message.build())
        } catch (e: ProcessUnavailableException) {
            LOGGER.warn(e)
        }
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
