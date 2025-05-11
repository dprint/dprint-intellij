package com.dprint.services.editorservice.v5

import com.dprint.i18n.DprintBundle
import com.dprint.services.editorservice.process.EditorProcess
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.BufferUnderflowException

private const val SLEEP_TIME = 500L

private val LOGGER = logger<StdoutListener>()

class StdoutListener(private val editorProcess: EditorProcess, private val messageChannel: MessageChannel) {
    private var listenerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun listen() {
        listenerJob =
            scope.launch {
                try {
                    LOGGER.info(DprintBundle.message("editor.service.started.stdout.listener"))
                    while (isActive) {
                        try {
                            handleStdout()
                        } catch (e: BufferUnderflowException) {
                            // Happens when the editor service is shut down while waiting to read output
                            if (isActive) {
                                LOGGER.info("Buffer underflow while reading stdout", e)
                            }
                            break
                        } catch (e: Exception) {
                            if (isActive) {
                                LOGGER.error(DprintBundle.message("editor.service.read.failed"), e)
                                delay(SLEEP_TIME)
                            } else {
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        LOGGER.error("Error in stdout listener", e)
                    }
                }
            }
    }

    fun dispose() {
        listenerJob?.cancel()
        scope.cancel()
    }

    private suspend fun handleStdout() {
        val messageId = editorProcess.readInt()
        val messageType = editorProcess.readInt()
        val bodyLength = editorProcess.readInt()
        val body = IncomingMessage(editorProcess.readBuffer(bodyLength))
        editorProcess.readAndAssertSuccess()

        when (messageType) {
            MessageType.SuccessResponse.intValue -> {
                val responseId = body.readInt()
                val result = MessageChannel.Result(MessageType.SuccessResponse, null)
                messageChannel.receiveResponse(responseId, result)
            }

            MessageType.ErrorResponse.intValue -> {
                val responseId = body.readInt()
                val errorMessage = body.readSizedString()
                LOGGER.info(DprintBundle.message("editor.service.received.error.response", errorMessage))
                val result = MessageChannel.Result(MessageType.ErrorResponse, errorMessage)
                messageChannel.receiveResponse(responseId, result)
            }

            MessageType.Active.intValue -> {
                sendSuccess(messageId)
            }

            MessageType.CanFormatResponse.intValue -> {
                val responseId = body.readInt()
                val canFormatResult = body.readInt()
                val result = MessageChannel.Result(MessageType.CanFormatResponse, canFormatResult == 1)
                messageChannel.receiveResponse(responseId, result)
            }

            MessageType.FormatFileResponse.intValue -> {
                val responseId = body.readInt()
                val hasChanged = body.readInt()
                val text =
                    when (hasChanged == 1) {
                        true -> body.readSizedString()
                        false -> null
                    }
                val result = MessageChannel.Result(MessageType.FormatFileResponse, text)
                messageChannel.receiveResponse(responseId, result)
            }

            else -> {
                val errorMessage = DprintBundle.message("editor.service.unsupported.message.type", messageType)
                LOGGER.info(errorMessage)
                sendFailure(messageId, errorMessage)
            }
        }
    }

    private suspend fun sendSuccess(messageId: Int) {
        val message = createNewMessage(MessageType.SuccessResponse)
        message.addInt(messageId)
        sendResponse(message)
    }

    private suspend fun sendFailure(
        messageId: Int,
        errorMessage: String,
    ) {
        val message = createNewMessage(MessageType.ErrorResponse)
        message.addInt(messageId)
        message.addString(errorMessage)
        sendResponse(message)
    }

    private suspend fun sendResponse(outgoingMessage: OutgoingMessage) {
        editorProcess.writeBuffer(outgoingMessage.build())
    }
}
