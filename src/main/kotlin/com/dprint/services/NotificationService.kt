package com.dprint.services

import com.dprint.core.Bundle
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

private const val GROUP_ID = "Dprint"

@Service
class NotificationService(private val project: Project) {
    fun notifyOfConfigError(content: String) {
        notify(
            Bundle.message("notification.config.error.title"),
            content,
            NotificationType.ERROR
        )
    }

    fun notifyOfFormatFailure(content: String) {
        notify(
            Bundle.message("notification.format.failed.title"),
            content,
            NotificationType.ERROR
        )
    }

    fun notify(title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
            .createNotification(title, content, type).notify(project)
    }
}
