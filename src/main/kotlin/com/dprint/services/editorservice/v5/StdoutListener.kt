package com.dprint.services.editorservice.v5

import com.dprint.i18n.DprintBundle
import com.dprint.services.editorservice.process.EditorProcess
import com.intellij.openapi.diagnostic.logger
import java.nio.BufferUnderflowException
import kotlin.concurrent.thread

private const val SLEEP_TIME = 500L

private val LOGGER = logger<StdoutListener>()

class StdoutListener(private val editorProcess: EditorProcess, private val pendingMessages: PendingMessages) {
    private var listenerThread: Thread? = null
    var disposing = false

    fun listen() {
        disposing = false
        listenerThread =
            thread(start = true) {
                LOGGER.info(DprintBundle.message("editor.service.started.stdout.listener"))
                while (true) {
                    if (Thread.interrupted()) {
                        return@thread
                    }
                    try {
                        handleStdout()
                    } catch (e: InterruptedException) {
                        if (!disposing) LOGGER.info(e)
                        return@thread
                    } catch (e: BufferUnderflowException) {
                        // Happens when the editor service is shut down while this thread is waiting to read output
                        if (!disposing) LOGGER.info(e)
                        return@thread
                    } catch (e: Exception) {
                        if (!disposing) LOGGER.error(DprintBundle.message("editor.service.read.failed"), e)
                        Thread.sleep(SLEEP_TIME)
                    }
                }
            }
    }

    fun dispose() {
        listenerThread?.interrupt()
    }

    private fun handleStdout() {
        val messageId = editorProcess.readInt()
        val messageType = editorProcess.readInt()
        val bodyLength = editorProcess.readInt()
        val body = IncomingMessage(editorProcess.readBuffer(bodyLength))
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
                LOGGER.info(DprintBundle.message("editor.service.received.error.response", errorMessage))
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
                val text =
                    when (hasChanged == 1) {
                        true -> body.readSizedString()
                        false -> null
                    }
                val result = PendingMessages.Result(MessageType.FormatFileResponse, text)
                pendingMessages.take(responseId)?.let { it(result) }
            }

            else -> {
                val errorMessage = DprintBundle.message("editor.service.unsupported.message.type", messageType)
                LOGGER.info(errorMessage)
                sendFailure(messageId, errorMessage)
            }
        }
    }

    private fun sendSuccess(messageId: Int) {
        val message = createNewMessage(MessageType.SuccessResponse)
        message.addInt(messageId)
        sendResponse(message)
    }

    private fun sendFailure(
        messageId: Int,
        errorMessage: String,
    ) {
        val message = createNewMessage(MessageType.ErrorResponse)
        message.addInt(messageId)
        message.addString(errorMessage)
        sendResponse(message)
    }

    private fun sendResponse(outgoingMessage: OutgoingMessage) {
        editorProcess.writeBuffer(outgoingMessage.build())
    }
}
