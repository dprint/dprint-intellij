package com.dprint.services.editorservice.v5

import com.dprint.core.Bundle
import com.dprint.core.LogUtils
import com.dprint.services.editorservice.EditorProcess
import com.dprint.services.editorservice.EditorService
import com.dprint.services.editorservice.FormatResult
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

private val LOGGER = logger<EditorServiceV5>()
private const val FORCE_DESTROY_DELAY = 1000L
private const val FORMATTING_TIMEOUT_SECONDS = 10L

@Service
class EditorServiceV5(val project: Project) : EditorService {

    private var editorProcess = EditorProcess(project)
    private var stdoutListener: Thread? = null
    private val pendingMessages = PendingMessages()

    private fun createStdoutListener(): Thread {
        if (stdoutListener != null) {
            stdoutListener?.interrupt()
        }

        return thread {
            StdoutListener(editorProcess, pendingMessages).run()
        }
    }

    override fun initialiseEditorService() {
        LogUtils.info(
            Bundle.message("editor.service.initialize", getName()), project, LOGGER
        )
        dropMessages()
        editorProcess.initialize()
        stdoutListener = createStdoutListener()
    }

    override fun dispose() {
        destroyEditorService()
    }

    override fun destroyEditorService() {
        LogUtils.info(Bundle.message("editor.service.destroy", getName()), project, LOGGER)
        val message = createNewMessage(MessageType.ShutDownProcess)
        editorProcess.writeBuffer(message.build())
        stdoutListener?.interrupt()
        dropMessages()

        runBlocking {
            launch {
                delay(FORCE_DESTROY_DELAY)
                editorProcess.destroy()
            }
        }
    }

    override fun canFormat(filePath: String): Boolean {
        LogUtils.info(Bundle.message("formatting.checking.can.format", filePath), project, LOGGER)
        val message = createNewMessage(MessageType.CanFormat)
        message.addString(filePath)
        val future = CompletableFuture<PendingMessages.Result>()

        val handler: (PendingMessages.Result) -> Unit = { future.complete(it) }
        pendingMessages.store(message.id, handler)

        editorProcess.writeBuffer(message.build())

        return try {
            val result = future.get(FORMATTING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (result.type == MessageType.CanFormatResponse && result.data is Boolean) {
                result.data
            } else if (result.type == MessageType.ErrorResponse && result.data is String) {
                LogUtils.info(result.data, project, LOGGER)
                false
            } else if (result.type === MessageType.Dropped) {
                false
            } else {
                LogUtils.info(Bundle.message("editor.service.unsupported.message.type", result.type), project, LOGGER)
                false
            }
        } catch (e: TimeoutException) {
            LOGGER.error(e)
            pendingMessages.take(message.id)
            initialiseEditorService()
            false
        } catch (e: ExecutionException) {
            LOGGER.error(e)
            pendingMessages.take(message.id)
            initialiseEditorService()
            false
        }
    }

    override fun canRangeFormat(): Boolean {
        return false
    }

    override fun fmt(
        filePath: String,
        content: String,
        startIndex: Int?,
        endIndex: Int?,
        onFinished: (FormatResult) -> Unit
    ): Int {
        LogUtils.info(Bundle.message("formatting.file", filePath), project, LOGGER)
        val message = createNewMessage(MessageType.FormatFile)
        message.addString(filePath)
        message.addInt(startIndex ?: 0) // for range formatting add starting index
        message.addInt(endIndex ?: content.encodeToByteArray().size) // add ending index
        message.addInt(0) // Override config
        message.addString(content)

        val handler: (PendingMessages.Result) -> Unit = {
            val formatResult = FormatResult()
            if (it.type == MessageType.FormatFileResponse && it.data is String?) {
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
