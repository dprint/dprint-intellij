package com.dprint.services.editorservice

import com.dprint.config.ProjectConfiguration
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.kotest.core.spec.style.FunSpec
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.verify

class EditorServiceInitializerTest :
    FunSpec({
        val mockProject = mockk<Project>()
        val mockCache = mockk<EditorServiceCache>(relaxed = true)
        val mockProjectConfiguration = mockk<ProjectConfiguration>()
        val mockState = mockk<ProjectConfiguration.State>()

        // Mock static functions that might be called
        mockkStatic(NotificationGroupManager::class)
        val mockNotificationGroupManager = mockk<NotificationGroupManager>()
        val mockNotificationGroup = mockk<com.intellij.notification.NotificationGroup>()
        val mockNotification = mockk<com.intellij.notification.Notification>()

        beforeEach {
            // Setup project service mock
            every { mockProject.service<ProjectConfiguration>() } returns mockProjectConfiguration
            every { mockProjectConfiguration.state } returns mockState

            // Setup notification mocking
            every { NotificationGroupManager.getInstance() } returns mockNotificationGroupManager
            every { mockNotificationGroupManager.getNotificationGroup("Dprint") } returns mockNotificationGroup
            every {
                mockNotificationGroup.createNotification(any<String>(), any<String>(), any<NotificationType>())
            } returns mockNotification
            every { mockNotification.notify(any()) } just runs
        }

        afterEach {
            clearAllMocks()
        }

        val initializer = EditorServiceInitializer(mockProject)

        // Test removed - hasAttemptedInitialisation is no longer needed with state-based architecture

        // Tests for createRestartTask removed - this functionality is now handled directly in DprintService

        test("notifyFailedToStart shows notification") {
            // When
            initializer.notifyFailedToStart()

            // Then - verify notification creation and display
            verify { mockNotificationGroup.createNotification(any<String>(), any<String>(), any<NotificationType>()) }
            verify { mockNotification.notify(mockProject) }
        }
    })
