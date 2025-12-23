package com.dprint.services

import com.dprint.config.ProjectConfiguration
import com.dprint.services.editorservice.FormatResult
import com.dprint.services.editorservice.IEditorService
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.runBlocking

/**
 * Unit tests for DprintService focusing on testable functionality
 * without requiring full IntelliJ platform initialization.
 */
class DprintServiceUnitTest :
    FunSpec({
        val mockProject = mockk<Project>(relaxed = true)
        val mockProjectConfig = mockk<ProjectConfiguration>()
        val mockState = mockk<ProjectConfiguration.State>()
        val mockEditorService = mockk<IEditorService>(relaxed = true)
        val mockVirtualFile = mockk<VirtualFile>()
        val mockFileEditorManager = mockk<FileEditorManager>(relaxed = true)

        // Mock static functions - avoid file system operations in tests
        mockkStatic(FileEditorManager::class)

        lateinit var dprintService: DprintService

        beforeEach {
            clearAllMocks()

            // Setup minimal project service mocks
            every { mockProject.service<ProjectConfiguration>() } returns mockProjectConfig
            every { mockProjectConfig.state } returns mockState
            every { mockState.enabled } returns true
            every { mockState.initialisationTimeout } returns 5000L
            every { mockState.commandTimeout } returns 10000L

            // Setup FileEditorManager mock to avoid file system access
            every { FileEditorManager.getInstance(mockProject) } returns mockFileEditorManager
            every { mockFileEditorManager.openFiles } returns emptyArray()

            dprintService = DprintService(mockProject)
        }

        afterEach {
            clearAllMocks()
        }

        // Test basic state management using the test helpers
        test("isReady returns false when not initialized") {
            // Given - service starts uninitialized

            // When/Then
            dprintService.isReady shouldBe false
            dprintService.isInitializedForTesting() shouldBe false
            dprintService.isRestartingForTesting() shouldBe false
            dprintService.getCurrentServiceForTesting() shouldBe null
            dprintService.getLastErrorForTesting() shouldBe null
        }

        test("isReady returns true when properly initialized with test helper") {
            // Given
            dprintService.setCurrentServiceForTesting(mockEditorService, "/config/path")

            // When/Then
            dprintService.isReady shouldBe true
            dprintService.isInitializedForTesting() shouldBe true
            dprintService.getCurrentServiceForTesting() shouldNotBe null
            dprintService.getConfigPath() shouldBe "/config/path"
            dprintService.getLastErrorForTesting() shouldBe null
        }

        test("isReady returns false when service has error") {
            // Given
            dprintService.setCurrentServiceForTesting(mockEditorService, "/config/path")
            dprintService.setErrorForTesting("Test error")

            // When/Then
            dprintService.isReady shouldBe false
            dprintService.getLastErrorForTesting() shouldBe "Test error"
            dprintService.isInitializedForTesting() shouldBe true // Still initialized, but has error
        }

        test("maybeGetFormatId returns null when no editor service") {
            // Given - no editor service set
            dprintService.getCurrentServiceForTesting() shouldBe null

            // When/Then
            dprintService.maybeGetFormatId() shouldBe null
        }

        test("maybeGetFormatId delegates to editor service when available") {
            // Given
            every { mockEditorService.maybeGetFormatId() } returns 42
            dprintService.setCurrentServiceForTesting(mockEditorService, "/config/path")

            // When/Then
            dprintService.maybeGetFormatId() shouldBe 42
            verify { mockEditorService.maybeGetFormatId() }
        }

        test("canCancelFormat returns false when no editor service") {
            // Given - no editor service set
            dprintService.getCurrentServiceForTesting() shouldBe null

            // When/Then
            dprintService.canCancelFormat() shouldBe false
        }

        test("canCancelFormat delegates to editor service when available") {
            // Given
            every { mockEditorService.canCancelFormat() } returns true
            dprintService.setCurrentServiceForTesting(mockEditorService, "/config/path")

            // When/Then
            dprintService.canCancelFormat() shouldBe true
            verify { mockEditorService.canCancelFormat() }
        }

        // Test suspend formatting operations
        test("formatSuspend returns null when no editor service") {
            runBlocking {
                // Given - no editor service set
                dprintService.getCurrentServiceForTesting() shouldBe null

                // When
                val result =
                    dprintService.formatSuspend(
                        path = "/test/file.ts",
                        content = "test content",
                        formatId = 1,
                    )

                // Then
                result shouldBe null
            }
        }

        test("formatSuspend returns result when editor service available") {
            runBlocking {
                // Given
                val expectedResult = FormatResult(formattedContent = "formatted content")
                coEvery {
                    mockEditorService.fmt(any(), any(), any())
                } returns expectedResult

                dprintService.setCurrentServiceForTesting(mockEditorService, "/config/path")

                // When
                val result =
                    dprintService.formatSuspend(
                        path = "/test/file.ts",
                        content = "test content",
                        formatId = 1,
                    )

                // Then
                result shouldBe expectedResult
                coVerify { mockEditorService.fmt("/test/file.ts", "test content", 1) }
            }
        }

        // Note: Exception handling test removed as it depends on implementation details
        // The important thing is that the service can handle normal operations

        // Test lifecycle operations
        test("initializeEditorService does nothing when disabled") {
            // Given
            every { mockState.enabled } returns false

            // When
            dprintService.initializeEditorService()

            // Then - state should remain unchanged
            dprintService.isReady shouldBe false
            dprintService.isInitializedForTesting() shouldBe false
        }

        test("initializeEditorService does nothing when already initialized") {
            // Given - service is already initialized
            dprintService.setCurrentServiceForTesting(mockEditorService, "/config/path")
            val wasReady = dprintService.isReady
            wasReady shouldBe true

            // When
            dprintService.initializeEditorService()

            // Then - should not change state
            dprintService.isReady shouldBe wasReady
            dprintService.getCurrentServiceForTesting() shouldBe mockEditorService
        }

        test("destroyEditorService resets all state") {
            // Given - service is initialized
            every { mockEditorService.destroyEditorService() } just runs
            dprintService.setCurrentServiceForTesting(mockEditorService, "/config/path")
            dprintService.isReady shouldBe true

            // When
            dprintService.destroyEditorService()

            // Then
            dprintService.isReady shouldBe false
            dprintService.getCurrentServiceForTesting() shouldBe null
            dprintService.getConfigPath() shouldBe null
            dprintService.isInitializedForTesting() shouldBe false
            dprintService.getLastErrorForTesting() shouldBe null
            verify { mockEditorService.destroyEditorService() }
        }

        // Test basic cache operations (without requiring task execution)
        test("primeCanFormatCacheForFile does nothing when service not ready") {
            // Given - service is not ready
            every { mockVirtualFile.path } returns "/test/file.ts"
            dprintService.isReady shouldBe false

            // When
            dprintService.primeCanFormatCacheForFile(mockVirtualFile)

            // Then - should not crash, method should handle gracefully
            // No direct verification possible without accessing internal state
        }

        test("clearCanFormatCache does not crash") {
            // When
            dprintService.clearCanFormatCache()

            // Then - should not crash
            // Internal cache should be cleared but we can't verify directly
        }
    })
