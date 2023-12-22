package com.dprint.services.editorservice.v4

import com.dprint.config.ProjectConfiguration
import com.dprint.i18n.DprintBundle
import com.dprint.services.editorservice.EditorProcess
import com.dprint.services.editorservice.EditorService
import com.dprint.services.editorservice.FormatResult
import com.dprint.utils.infoLogWithConsole
import com.dprint.utils.warnLogWithConsole
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project

private const val CHECK_COMMAND = 1
private const val FORMAT_COMMAND = 2

private val LOGGER = logger<EditorServiceV4>()

@Service(Service.Level.PROJECT)
class EditorServiceV4(private val project: Project) : EditorService {
    private var editorProcess = EditorProcess(project)

    override fun initialiseEditorService() {
        // If not enabled we don't start the editor service
        if (!project.service<ProjectConfiguration>().state.enabled) return
        editorProcess.initialize()
        infoLogWithConsole(
            DprintBundle.message("editor.service.initialize", getName()),
            project,
            LOGGER,
        )
    }

    override fun dispose() {
        destroyEditorService()
    }

    override fun destroyEditorService() {
        infoLogWithConsole(DprintBundle.message("editor.service.destroy", getName()), project, LOGGER)
        editorProcess.destroy()
    }

    override fun canFormat(
        filePath: String,
        onFinished: (Boolean) -> Unit,
    ) {
        var status = 0
        infoLogWithConsole(DprintBundle.message("formatting.checking.can.format", filePath), project, LOGGER)

        editorProcess.writeInt(CHECK_COMMAND)
        editorProcess.writeString(filePath)
        editorProcess.writeSuccess()

        // https://github.com/dprint/dprint/blob/main/docs/editor-extension-development.md
        // this command sequence returns 1 if the file can be formatted
        status = editorProcess.readInt()
        editorProcess.readAndAssertSuccess()

        val result = status == 1
        when (result) {
            true -> infoLogWithConsole(DprintBundle.message("formatting.can.format", filePath), project, LOGGER)
            false -> infoLogWithConsole(DprintBundle.message("formatting.cannot.format", filePath), project, LOGGER)
        }
        onFinished(result)
    }

    override fun canRangeFormat(): Boolean {
        return false
    }

    override fun fmt(
        filePath: String,
        content: String,
        onFinished: (FormatResult) -> Unit,
    ): Int? {
        val result = FormatResult()

        infoLogWithConsole(DprintBundle.message("formatting.file", filePath), project, LOGGER)
        editorProcess.writeInt(FORMAT_COMMAND)
        editorProcess.writeString(filePath)
        editorProcess.writeString(content)
        editorProcess.writeSuccess()

        when (editorProcess.readInt()) {
            0 -> {
                infoLogWithConsole(
                    DprintBundle.message("editor.service.format.not.needed", filePath),
                    project,
                    LOGGER,
                )
            } // no-op as content didn't change
            1 -> {
                result.formattedContent = editorProcess.readString()
                infoLogWithConsole(
                    DprintBundle.message("editor.service.format.succeeded", filePath),
                    project,
                    LOGGER,
                )
            }

            2 -> {
                val error = editorProcess.readString()
                result.error = error
                warnLogWithConsole(
                    DprintBundle.message("editor.service.format.failed", filePath, error),
                    project,
                    LOGGER,
                )
            }
        }

        editorProcess.readAndAssertSuccess()

        onFinished(result)

        // We cannot cancel in V4 so return null
        return null
    }

    override fun fmt(
        formatId: Int?,
        filePath: String,
        content: String,
        startIndex: Int?,
        endIndex: Int?,
        onFinished: (FormatResult) -> Unit,
    ): Int? {
        return fmt(filePath, content, onFinished)
    }

    override fun canCancelFormat(): Boolean {
        return false
    }

    override fun maybeGetFormatId(): Int? {
        return null
    }

    private fun getName(): String {
        return this::class.java.simpleName
    }
}
