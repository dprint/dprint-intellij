package com.dprint.services

import com.dprint.utils.isFormattableFile
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.kotest.core.spec.style.FunSpec
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify

class FormatterServiceTest : FunSpec({
    val testPath = "/test/path"
    val testText = "val test = \"test\""

    mockkStatic(::isFormattableFile)

    val virtualFile = mockk<VirtualFile>()
    val document = mockk<Document>()
    val project = mockk<Project>()
    val dprintService = mockk<DprintService>(relaxed = true)

    val formatterService = FormatterService(project)

    beforeEach {
        every { virtualFile.path } returns testPath
        every { document.text } returns testText
        every { project.service<DprintService>() } returns dprintService
    }

    afterEach {
        clearAllMocks()
    }

    test("It doesn't format if cached can format result is false") {
        every { isFormattableFile(project, virtualFile) } returns true
        every { dprintService.canFormatCached(testPath) } returns false

        formatterService.format(virtualFile, document)

        verify(exactly = 0) { dprintService.format(testPath, testPath, any()) }
    }

    test("It doesn't format if cached can format result is null") {
        every { isFormattableFile(project, virtualFile) } returns true
        every { dprintService.canFormatCached(testPath) } returns null

        formatterService.format(virtualFile, document)

        verify(exactly = 0) { dprintService.format(testPath, testPath, any()) }
    }

    test("It formats if cached can format result is true") {
        every { isFormattableFile(project, virtualFile) } returns true
        every { dprintService.canFormatCached(testPath) } returns true

        formatterService.format(virtualFile, document)

        verify(exactly = 1) { dprintService.format(testPath, testText, any()) }
    }
})
