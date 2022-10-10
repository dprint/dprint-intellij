package com.dprint.services.editorservice.v5

import com.dprint.core.Bundle
import com.dprint.services.editorservice.EditorProcess
import com.dprint.services.editorservice.exceptions.ProcessUnavailableException
import com.intellij.openapi.diagnostic.logger
import java.nio.BufferUnderflowException

private const val SLEEP_TIME = 500L

private val LOGGER = logger<StdoutListener>()

class StdoutListener(private val editorProcess: EditorProcess, private val pendingMessages: PendingMessages) :
    Runnable {
    @Suppress("TooGenericExceptionCaught")
    override fun run() {
        LOGGER.info(Bundle.message("editor.service.started.stdout.listener"))
        while (true) {
            if (Thread.interrupted()) {
                return
            }
            try {
                handleStdout()
            } catch (e: InterruptedException) {
                LOGGER.info(e)
                return
            } catch (e: BufferUnderflowException) {
                // Happens when the editor service is shut down while this thread is waiting to read output
                LOGGER.info(e)
                return
            } catch (e: Exception) {
                LOGGER.error(Bundle.message("editor.service.read.failed"), e)
                Thread.sleep(SLEEP_TIME)
            }
        }
    }

    private fun handleStdout() {
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
        } catch (e: ProcessUnavailableException) {
            LOGGER.warn(e)
        }
    }

    private fun sendSuccess(messageId: Int) {
        val message = createNewMessage(MessageType.SuccessResponse)
        message.addInt(messageId)
        try {
            sendResponse(message)
        } catch (e: ProcessUnavailableException) {
            LOGGER.warn(e)
        }
    }

    private fun sendFailure(messageId: Int, errorMessage: String) {
        val message = createNewMessage(MessageType.ErrorResponse)
        message.addInt(messageId)
        message.addString(errorMessage)
        try {
            sendResponse(message)
        } catch (e: ProcessUnavailableException) {
            LOGGER.warn(e)
        }
    }

    private fun sendResponse(message: Message) {
        editorProcess.writeBuffer(message.build())
    }
}
