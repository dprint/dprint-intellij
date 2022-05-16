package com.dprint.services.editorservice.v4

import com.dprint.config.ProjectConfiguration
import com.dprint.core.Bundle
import com.dprint.core.LogUtils
import com.dprint.messages.DprintMessage
import com.dprint.services.editorservice.EditorProcess
import com.dprint.services.editorservice.EditorService
import com.dprint.services.editorservice.FormatResult
import com.dprint.services.editorservice.exceptions.ProcessUnavailableException
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project

private const val CHECK_COMMAND = 1
private const val FORMAT_COMMAND = 2

private val LOGGER = logger<EditorServiceV4>()

/**
 * A command line wrapper to run Dprint.
 */
@Service
class EditorServiceV4(private val project: Project) : EditorService {
    private var editorProcess = EditorProcess(project)

    override fun initialiseEditorService() {
        // If not enabled we don't start the editor service
        if (!project.service<ProjectConfiguration>().state.enabled) return
        editorProcess.initialize()
    }

    override fun dispose() {
        destroyEditorService()
    }

    override fun destroyEditorService() {
        editorProcess.destroy()
    }

    override fun canFormat(filePath: String): Boolean {
        var status = 0
        LogUtils.info(Bundle.message("formatting.checking.can.format", filePath), project, LOGGER)

        try {
            synchronized(this) {
                editorProcess.writeInt(CHECK_COMMAND)
                editorProcess.writeString(filePath)
                editorProcess.writeSuccess()

                // https://github.com/dprint/dprint/blob/main/docs/editor-extension-development.md
                // this command sequence returns 1 if the file can be formatted
                status = editorProcess.readInt()
                editorProcess.readAndAssertSuccess()
            }
        } catch (e: ProcessUnavailableException) {
            LogUtils.error(
                Bundle.message("editor.service.unable.to.determine.if.can.format", filePath),
                e,
                project,
                LOGGER
            )
        }

        val result = status == 1
        when (result) {
            true -> LogUtils.info(Bundle.message("formatting.can.format", filePath), project, LOGGER)
            false -> LogUtils.info(Bundle.message("formatting.cannot.format", filePath), project, LOGGER)
        }

        return result
    }

    override fun canRangeFormat(): Boolean {
        return false
    }

    /**
     * This runs dprint using the editor service with the supplied file path and content as stdin.
     * @param filePath The path of the file being formatted. This is needed so the correct dprint configuration file
     * located.
     * @param content The content of the file as a string. This is formatted via Dprint and returned via the result.
     * @return A result object containing the formatted content is successful or an error.
     */
    override fun fmt(filePath: String, content: String, onFinished: (FormatResult) -> Unit): Int? {
        val result = FormatResult()

        LogUtils.info(Bundle.message("formatting.file", filePath), project, LOGGER)

        try {
            synchronized(this) {
                editorProcess.writeInt(FORMAT_COMMAND)
                editorProcess.writeString(filePath)
                editorProcess.writeString(content)
                editorProcess.writeSuccess()

                when (editorProcess.readInt()) {
                    0 -> Unit // no-op as content didn't change
                    1 -> result.formattedContent = editorProcess.readString()
                    2 -> result.error = editorProcess.readString()
                }

                editorProcess.readAndAssertSuccess()
            }
        } catch (e: ProcessUnavailableException) {
            LogUtils.error(
                Bundle.message("editor.service.unable.to.determine.if.can.format", filePath),
                e,
                project,
                LOGGER
            )
        }

        onFinished(result)

        // We cannot cancel in V4 so return null
        return null
    }

    override fun fmt(
        filePath: String,
        content: String,
        startIndex: Int?,
        endIndex: Int?,
        onFinished: (FormatResult) -> Unit
    ): Int? {
        return fmt(filePath, content, onFinished)
    }

    override fun canCancelFormat(): Boolean {
        return false
    }
}
