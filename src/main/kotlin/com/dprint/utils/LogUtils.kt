package com.dprint.utils

import com.dprint.messages.DprintMessage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBus

data class MessageWithThrowable(
    val message: String,
    val throwable: Throwable?,
) {
    override fun toString(): String {
        if (throwable != null) {
            return "$message\n\t$throwable"
        }
        return message
    }
}

fun infoConsole(
    message: String,
    project: Project,
) {
    maybeGetMessageBus(project)?.syncPublisher(DprintMessage.DPRINT_MESSAGE_TOPIC)?.info(message)
}

fun infoLogWithConsole(
    message: String,
    project: Project,
    logger: Logger,
) {
    logger.info(message)
    infoConsole(message, project)
}

fun warnLogWithConsole(
    message: String,
    project: Project,
    logger: Logger,
) {
    // Always use info for system level logging as it throws notifications into the UI
    logger.info(message)
    maybeGetMessageBus(project)?.syncPublisher(DprintMessage.DPRINT_MESSAGE_TOPIC)?.warn(message)
}

fun warnLogWithConsole(
    message: String,
    throwable: Throwable?,
    project: Project,
    logger: Logger,
) {
    // Always use info for system level logging as it throws notifications into the UI
    logger.warn(message, throwable)
    maybeGetMessageBus(
        project,
    )?.syncPublisher(DprintMessage.DPRINT_MESSAGE_TOPIC)?.warn(MessageWithThrowable(message, throwable).toString())
}

fun errorLogWithConsole(
    message: String,
    project: Project,
    logger: Logger,
) {
    errorLogWithConsole(message, null, project, logger)
}

fun errorLogWithConsole(
    message: String,
    throwable: Throwable?,
    project: Project,
    logger: Logger,
) {
    // Always use info for system level logging as it throws notifications into the UI
    logger.error(message, throwable)
    maybeGetMessageBus(
        project,
    )?.syncPublisher(DprintMessage.DPRINT_MESSAGE_TOPIC)?.error(MessageWithThrowable(message, throwable).toString())
}

private fun maybeGetMessageBus(project: Project): MessageBus? {
    if (project.isDisposed) {
        return null
    }

    return project.messageBus
}
