package com.dprint.services.editorservice

import com.dprint.config.ProjectConfiguration
import com.dprint.services.DprintTaskExecutor
import com.dprint.services.TaskType
import com.dprint.utils.warnLogWithConsole
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.verify

class EditorServiceCacheTest :
    FunSpec({
        val mockProject = mockk<Project>()
        val mockEditorService = mockk<IEditorService>()
        val mockTaskExecutor = mockk<DprintTaskExecutor>(relaxed = true)
        val mockProjectConfiguration = mockk<ProjectConfiguration>()
        val mockState = mockk<ProjectConfiguration.State>()

        // Mock static logging functions
        mockkStatic("com.dprint.utils.LogUtilsKt")

        beforeEach {
            // Setup project service mock
            every { mockProject.service<ProjectConfiguration>() } returns mockProjectConfiguration
            every { mockProjectConfiguration.state } returns mockState
            every { mockState.commandTimeout } returns 5000L

            // Mock logging functions to do nothing
            every { warnLogWithConsole(any(), any(), any()) } just runs
        }

        afterEach {
            clearAllMocks()
        }

        test("canFormatCached when result exists returns result") {
            // Given
            val cache = EditorServiceCache(mockProject)
            val path = "/test/file.ts"
            cache.putCanFormat(path, true)

            // When
            val result = cache.canFormatCached(path)

            // Then
            result shouldBe true
        }

        test("canFormatCached when result does not exist returns null") {
            // Given
            val cache = EditorServiceCache(mockProject)
            val path = "/test/file.ts"

            // When
            val result = cache.canFormatCached(path)

            // Then
            result shouldBe null
        }

        test("clearCanFormatCache removes all entries") {
            // Given
            val cache = EditorServiceCache(mockProject)
            cache.putCanFormat("/test/file1.ts", true)
            cache.putCanFormat("/test/file2.ts", false)

            // When
            cache.clearCanFormatCache()

            // Then
            cache.canFormatCached("/test/file1.ts") shouldBe null
            cache.canFormatCached("/test/file2.ts") shouldBe null
        }

        test("createPrimeCanFormatTask when editor service exists creates task and updates cache") {
            // Given
            val cache = EditorServiceCache(mockProject)
            val path = "/test/file.ts"
            coEvery { mockEditorService.canFormat(path) } returns true

            // When
            cache.createPrimeCanFormatTask(path, { mockEditorService }, mockTaskExecutor)

            // Then
            verify {
                mockTaskExecutor.createTaskWithTimeout(
                    match { it.taskType == TaskType.PrimeCanFormat && it.path == path },
                    any(),
                    any(),
                    5000L,
                )
            }
        }

        test("putCanFormat overwrites existing value") {
            // Given
            val cache = EditorServiceCache(mockProject)
            val path = "/test/file.ts"
            cache.putCanFormat(path, true)

            // When
            cache.putCanFormat(path, false)

            // Then
            cache.canFormatCached(path) shouldBe false
        }
    })
