package com.dprint.core

import com.dprint.messages.DprintMessage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

object LogUtils {
    fun info(message: String, project: Project, logger: Logger) {
        logger.info(message)
        project.messageBus.syncPublisher(DprintMessage.DPRINT_MESSAGE_TOPIC).info(message)
    }

    fun warn(message: String, project: Project, logger: Logger) {
        // Always use info for system level logging as it throws notifications into the UI
        logger.info(message)
        project.messageBus.syncPublisher(DprintMessage.DPRINT_MESSAGE_TOPIC).warn(message)
    }

    fun error(message: String, project: Project, logger: Logger) {
        error(message, null, project, logger)
    }

    fun error(message: String, throwable: Throwable?, project: Project, logger: Logger) {
        // Always use info for system level logging as it throws notifications into the UI
        logger.info(message, throwable)
        project.messageBus.syncPublisher(DprintMessage.DPRINT_MESSAGE_TOPIC).error(message)
    }
}
