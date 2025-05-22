package com.dprint.messages

import com.dprint.utils.errorLogWithConsole
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

interface DprintAction {
    companion object {
        val DPRINT_ACTION_TOPIC: Topic<DprintAction> = Topic.create("dprint.action", DprintAction::class.java)

        private val LOGGER = logger<DprintAction>()

        fun publishFormattingStarted(
            project: Project,
            filePath: String,
        ) {
            publishSafely(project) {
                project.messageBus.syncPublisher(DPRINT_ACTION_TOPIC)
                    .formattingStarted(filePath)
            }
        }

        fun publishFormattingSkipped(
            project: Project,
            filePath: String,
        ) {
            publishSafely(project) {
                project.messageBus.syncPublisher(DPRINT_ACTION_TOPIC)
                    .formattingSkipped(filePath)
            }
        }

        fun publishFormattingSucceeded(
            project: Project,
            filePath: String,
            timeElapsed: Long,
        ) {
            publishSafely(project) {
                project.messageBus.syncPublisher(DPRINT_ACTION_TOPIC)
                    .formattingSucceeded(filePath, timeElapsed)
            }
        }

        fun publishFormattingFailed(
            project: Project,
            filePath: String,
            timeElapsed: Long,
            message: String?,
        ) {
            publishSafely(project) {
                project.messageBus.syncPublisher(DPRINT_ACTION_TOPIC)
                    .formattingFailed(filePath, timeElapsed, message)
            }
        }

        private fun publishSafely(
            project: Project,
            runnable: () -> Unit,
        ) {
            try {
                runnable()
            } catch (e: Exception) {
                errorLogWithConsole("Failed to publish dprint action message", e, project, LOGGER)
            }
        }
    }

    fun formattingStarted(filePath: String)

    fun formattingSkipped(filePath: String)

    fun formattingSucceeded(
        filePath: String,
        timeElapsed: Long,
    )

    fun formattingFailed(
        filePath: String,
        timeElapsed: Long,
        message: String?,
    )
}
