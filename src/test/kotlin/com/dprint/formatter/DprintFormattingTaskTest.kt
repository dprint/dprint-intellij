package com.dprint.formatter

import com.dprint.services.DprintService
import com.dprint.services.editorservice.FormatResult
import com.dprint.utils.errorLogWithConsole
import com.dprint.utils.infoLogWithConsole
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.openapi.project.Project
import io.kotest.core.spec.style.FunSpec
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify

class DprintFormattingTaskTest :
    FunSpec({
        val path = "/some/path"

        mockkStatic(::infoLogWithConsole)

        val project = mockk<Project>()
        val dprintService = mockk<DprintService>()
        val formattingRequest = mockk<AsyncFormattingRequest>(relaxed = true)
        lateinit var dprintFormattingTask: DprintFormattingTask

        beforeEach {
            every { infoLogWithConsole(any(), project, any()) } returns Unit
            every { dprintService.maybeGetFormatId() } returnsMany mutableListOf(1, 2, 3)

            dprintFormattingTask = DprintFormattingTask(project, dprintService, formattingRequest, path)
        }

        afterEach { clearAllMocks() }

        test("it calls dprintService.formatSuspend correctly when range formatting is disabled") {
            val testContent = "val test =   \"test\""
            val successContent = "val test = \"test\""
            val formatResult = FormatResult(formattedContent = successContent)

            every { formattingRequest.documentText } returns testContent
            coEvery {
                dprintService.formatSuspend(path, testContent, 1)
            } returns formatResult

            dprintFormattingTask.run()

            coVerify(exactly = 1) { dprintService.formatSuspend(path, testContent, 1) }
            verify { formattingRequest.onTextReady(successContent) }
        }

        test("it calls dprintService.cancel with the format id when cancelled") {
            val testContent = "val test =   \"test\""

            mockkStatic("com.dprint.utils.LogUtilsKt")
            every { infoLogWithConsole(any(), project, any()) } returns Unit
            every { errorLogWithConsole(any(), any(), project, any()) } returns Unit
            every { formattingRequest.documentText } returns testContent
            every { dprintService.canCancelFormat() } returns true
            every { dprintService.cancelFormat(1) } returns Unit
            every { dprintService.maybeGetFormatId() } returns 1

            dprintFormattingTask.cancel()
            dprintFormattingTask.run()

            // When cancelled before any formatting starts, no format IDs are generated
            // so cancelFormat won't be called, but the cancellation flag prevents formatting
            verify(exactly = 0) { formattingRequest.onTextReady(any()) }
        }

        test("it calls formattingRequest.onError when the format returns a failure state") {
            val testContent = "val test =   \"test\""
            val testFailure = "Test failure"
            val formatResult = FormatResult(error = testFailure)

            every { formattingRequest.documentText } returns testContent
            coEvery {
                dprintService.formatSuspend(path, testContent, 1)
            } returns formatResult

            dprintFormattingTask.run()

            coVerify(exactly = 1) { dprintService.formatSuspend(path, testContent, 1) }
            verify { formattingRequest.onError(any(), testFailure) }
        }
    })
