package com.dprint.services.editorservice.v5

import com.dprint.core.Bundle
import com.dprint.messages.DprintMessage
import com.dprint.services.editorservice.EditorProcess
import com.dprint.services.editorservice.EditorService
import com.dprint.services.editorservice.FormatResult
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.lucene.util.ThreadInterruptedException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

private val LOGGER = logger<EditorServiceV5>()
private var messageId = AtomicInteger(0)
private const val SLEEP_TIME = 500L
private const val FORCE_DESTROY_DELAY = 1000L
private const val FORMATTING_TIMEOUT_SECONDS = 10L

@Service
class EditorServiceV5(val project: Project) : EditorService {

    private var editorProcess = EditorProcess(project)
    private var stdoutListener: Thread? = null
    private val pendingMessages = PendingMessages()

    // TODO pull this out into a listener class
    @Suppress("TooGenericExceptionCaught")
    private fun createStdoutListener(): Thread {
        if (stdoutListener != null) {
            stdoutListener?.interrupt()
        }
        val runnable = Runnable {
            LOGGER.info("Dprint: Started listener")
            while (true) {
                if (Thread.interrupted()) {
                    return@Runnable
                }
                try {
                    val messageId = editorProcess.readInt()
                    val messageType = editorProcess.readInt()
                    val bodyLength = editorProcess.readInt()
                    val body = MessageBody(editorProcess.readBuffer(bodyLength))
                    editorProcess.readAndAssertSuccess()

                    when (messageType) {
                        MessageType.SuccessResponse.intValue -> {
                            val responseId = body.readInt()
                            val result = PendingMessages.Result(MessageType.SuccessResponse, null)
                            pendingMessages.take(responseId)?.let { it(result) }
                        }
                        MessageType.ErrorResponse.intValue -> {
                            val responseId = body.readInt()
                            val errorMessage = body.readSizedString()
                            LOGGER.info(Bundle.message("editor.service.received.error.response", errorMessage))
                            val result = PendingMessages.Result(MessageType.ErrorResponse, errorMessage)
                            pendingMessages.take(responseId)?.let { it(result) }
                        }
                        MessageType.Active.intValue -> {
                            sendSuccess(messageId)
                        }
                        MessageType.CanFormatResponse.intValue -> {
                            val responseId = body.readInt()
                            val canFormatResult = body.readInt()
                            val result = PendingMessages.Result(MessageType.CanFormatResponse, canFormatResult == 1)
                            pendingMessages.take(responseId)?.let { it(result) }
                        }
                        MessageType.FormatFileResponse.intValue -> {
                            val responseId = body.readInt()
                            val hasChanged = body.readInt()
                            val text = when (hasChanged == 1) {
                                true -> body.readSizedString()
                                false -> null
                            }
                            val result = PendingMessages.Result(MessageType.FormatFileResponse, text)
                            pendingMessages.take(responseId)?.let { it(result) }
                        }
                        else -> {
                            val errorMessage = Bundle.message(
                                "editor.service.unsupported.message.type",
                                messageType
                            )
                            LOGGER.info(errorMessage)
                            sendFailure(messageId, errorMessage)
                        }
                    }
                } catch (e: ThreadInterruptedException) {
                    LOGGER.info(e)
                    return@Runnable
                } catch (e: Exception) {
                    LOGGER.warn(Bundle.message("editor.service.read.failed"), e)
                    Thread.sleep(SLEEP_TIME)
                }
            }
        }

        return thread {
            runnable.run()
        }
    }

    override fun initialiseEditorService() {
        pendingMessages.drain()
        editorProcess.initialize()
        stdoutListener = createStdoutListener()
    }

    override fun dispose() {
        destroyEditorService()
    }

    override fun destroyEditorService() {
        val message = createNewMessage(MessageType.ShutDownProcess)
        editorProcess.writeBuffer(message.build())
        stdoutListener?.interrupt()
        pendingMessages.drain()

        runBlocking {
            launch {
                delay(FORCE_DESTROY_DELAY)
                editorProcess.destroy()
            }
        }
    }

    override fun canFormat(filePath: String): Boolean {
        LOGGER.info(Bundle.message("formatting.checking.can.format", filePath))
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
                LOGGER.info(result.data)
                false
            } else {
                LOGGER.info(Bundle.message("editor.service.unsupported.message.type", result.type))
                false
            }
        } catch (e: TimeoutException) {
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
        LOGGER.info(Bundle.message("formatting.file", filePath))
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
                project.messageBus.syncPublisher(DprintMessage.DPRINT_MESSAGE_TOPIC).printMessage(it.data)
                LOGGER.info(it.data)
                formatResult.error = it.data
            } else {
                val errorMessage = Bundle.message("editor.service.unsupported.message.type", it.type)
                project.messageBus.syncPublisher(DprintMessage.DPRINT_MESSAGE_TOPIC).printMessage(errorMessage)
                LOGGER.info(errorMessage)
                formatResult.error = errorMessage
            }
            onFinished(formatResult)
        }
        pendingMessages.store(message.id, handler)

        editorProcess.writeBuffer(message.build())

        return message.id
    }

    override fun canCancelFormat(): Boolean {
        return true
    }

    override fun cancelFormat(formatId: Int) {
        val message = createNewMessage(MessageType.CancelFormat)
        message.addInt(formatId)
        editorProcess.writeBuffer(message.build())
        pendingMessages.take(formatId)
    }

    private fun sendResponse(message: Message) {
        editorProcess.writeBuffer(message.build())
    }

    private fun sendSuccess(messageId: Int) {
        val message = createNewMessage(MessageType.SuccessResponse)
        message.addInt(messageId)
        sendResponse(message)
    }

    private fun sendFailure(messageId: Int, errorMessage: String) {
        val message = createNewMessage(MessageType.ErrorResponse)
        message.addInt(messageId)
        message.addString(errorMessage)
        sendResponse(message)
    }

    private fun createNewMessage(type: MessageType): Message {
        return Message(messageId.incrementAndGet(), type)
    }
}
