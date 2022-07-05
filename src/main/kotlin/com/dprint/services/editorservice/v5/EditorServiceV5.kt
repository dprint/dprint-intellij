package com.dprint.services.editorservice.v5

import com.dprint.core.Bundle
import com.dprint.core.LogUtils
import com.dprint.services.editorservice.EditorProcess
import com.dprint.services.editorservice.EditorService
import com.dprint.services.editorservice.FormatResult
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

@Service
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
        LogUtils.info(
            Bundle.message("editor.service.initialize", getName()), project, LOGGER
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
        LogUtils.info(Bundle.message("editor.service.destroy", getName()), project, LOGGER)
        val message = createNewMessage(MessageType.ShutDownProcess)

        try {
            runBlocking {
                withTimeout(SHUTDOWN_TIMEOUT) {
                    launch {
                        editorProcess.writeBuffer(message.build())
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            LogUtils.error(Bundle.message("editor.service.shutting.down.timed.out"), e, project, LOGGER)
        } finally {
            stdoutListener?.interrupt()
            dropMessages()
            editorProcess.destroy()
        }
    }

    override fun canFormat(filePath: String, onFinished: (Boolean) -> Unit) {
        LogUtils.info(Bundle.message("formatting.checking.can.format", filePath), project, LOGGER)
        val message = createNewMessage(MessageType.CanFormat)
        message.addString(filePath)

        val handler: (PendingMessages.Result) -> Unit = {
            if (it.type == MessageType.CanFormatResponse && it.data is Boolean) {
                onFinished(it.data)
            } else if (it.type == MessageType.ErrorResponse && it.data is String) {
                LogUtils.info(
                    Bundle.message("editor.service.format.check.failed", filePath, it.data),
                    project,
                    LOGGER
                )
            } else if (it.type === MessageType.Dropped) {
                // do nothing
            } else {
                LogUtils.info(Bundle.message("editor.service.unsupported.message.type", it.type), project, LOGGER)
            }
        }

        pendingMessages.store(message.id, handler)

        editorProcess.writeBuffer(message.build())
    }

    override fun canRangeFormat(): Boolean {
        return false
    }

    override fun fmt(
        formatId: Int?,
        filePath: String,
        content: String,
        startIndex: Int?,
        endIndex: Int?,
        onFinished: (FormatResult) -> Unit
    ): Int {
        LogUtils.info(Bundle.message("formatting.file", filePath), project, LOGGER)
        val message = Message(formatId ?: getNextMessageId(), MessageType.FormatFile)
        message.addString(filePath)
        message.addInt(startIndex ?: 0) // for range formatting add starting index
        message.addInt(endIndex ?: content.encodeToByteArray().size) // add ending index
        message.addInt(0) // Override config
        message.addString(content)

        val handler: (PendingMessages.Result) -> Unit = {
            val formatResult = FormatResult()
            if (it.type == MessageType.FormatFileResponse && it.data is String?) {
                val successMessage = when (it.data) {
                    null -> Bundle.message("editor.service.format.not.needed", filePath)
                    else -> Bundle.message("editor.service.format.succeeded", filePath)
                }
                LogUtils.info(successMessage, project, LOGGER)
                formatResult.formattedContent = it.data
            } else if (it.type == MessageType.ErrorResponse && it.data is String) {
                LogUtils.warn(Bundle.message("editor.service.format.failed", filePath, it.data), project, LOGGER)
                formatResult.error = it.data
            } else if (it.type != MessageType.Dropped) {
                val errorMessage = Bundle.message("editor.service.unsupported.message.type", it.type)
                LogUtils.warn(Bundle.message("editor.service.format.failed", filePath, errorMessage), project, LOGGER)
                formatResult.error = errorMessage
            }
            onFinished(formatResult)
        }
        pendingMessages.store(message.id, handler)

        editorProcess.writeBuffer(message.build())

        LogUtils.info(Bundle.message("editor.service.created.formatting.task", filePath, message.id), project, LOGGER)

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
        LogUtils.info(Bundle.message("editor.service.cancel.format", formatId), project, LOGGER)
        message.addInt(formatId)
        editorProcess.writeBuffer(message.build())
        pendingMessages.take(formatId)
    }

    private fun dropMessages() {
        for (message in pendingMessages.drain()) {
            LogUtils.info(Bundle.message("editor.service.clearing.message", message.key), project, LOGGER)
            message.value(PendingMessages.Result(MessageType.Dropped, null))
        }
    }

    private fun getName(): String {
        return this::class.java.simpleName
    }
}
