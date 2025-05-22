package com.dprint.formatter

import com.dprint.config.ProjectConfiguration
import com.dprint.config.UserConfiguration
import com.dprint.i18n.DprintBundle
import com.dprint.services.editorservice.EditorServiceManager
import com.dprint.utils.infoConsole
import com.dprint.utils.infoLogWithConsole
import com.dprint.utils.isFormattableFile
import com.dprint.utils.warnLogWithConsole
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiFile

private val LOGGER = logger<DprintExternalFormatter>()
private const val NAME = "dprintfmt"

/**
 * This class is the recommended way to implement an external formatter in the IJ
 * framework.
 *
 * How it works is that extends AsyncDocumentFormattingService and IJ
 * will use the `canFormat` method to determine if this formatter should be used
 * for a given file. If yes, then this will be run and the IJ formatter will not.
 * If no, it passes through his formatter and checks the next registered formatter
 * until it eventually gets to the IJ formatter as a last resort.
 */
class DprintExternalFormatter : AsyncDocumentFormattingService() {
    override fun getFeatures(): MutableSet<FormattingService.Feature> {
        // When range formatting is available we need to specify format fragments here.
        return mutableSetOf()
    }

    override fun canFormat(file: PsiFile): Boolean {
        val projectConfig = file.project.service<ProjectConfiguration>().state
        val userConfig = file.project.service<UserConfiguration>().state
        val editorServiceManager = file.project.service<EditorServiceManager>()

        if (!projectConfig.enabled) return false

        if (!userConfig.overrideIntelliJFormatter) {
            infoConsole(DprintBundle.message("external.formatter.not.configured.to.override"), file.project)
        }

        // If we don't have a cached can format response then we return true and let the formatting task figure that
        // out. Worse case scenario is that a file that cannot be formatted by dprint won't trigger the default IntelliJ
        // formatter. This is a minor issue and should be resolved if they run it again. We need to take this approach
        // as it appears that blocking the EDT here causes quite a few issues. Also, we ignore scratch files as a perf
        // optimisation because they are not part of the project and thus never in config.
        val virtualFile = file.virtualFile ?: file.originalFile.virtualFile
        val canFormat =
            if (virtualFile != null && isFormattableFile(file.project, virtualFile)) {
                editorServiceManager.canFormatCached(virtualFile.path)
            } else {
                false
            }

        if (canFormat == null) {
            warnLogWithConsole(DprintBundle.message("external.formatter.can.format.unknown"), file.project, LOGGER)
            return false
        } else if (canFormat) {
            infoConsole(DprintBundle.message("external.formatter.can.format", virtualFile.path), file.project)
        } else if (virtualFile?.path != null) {
            // If a virtual file path doesn't exist then it is an ephemeral file such as a scratch file. Dprint needs
            // real files to work. I have tried to log this in the past but it seems to be called frequently resulting
            // in log spam, so in the case the path doesn't exist, we just do nothing.
            infoConsole(DprintBundle.message("external.formatter.cannot.format", virtualFile.path), file.project)
        }

        return canFormat
    }

    override fun createFormattingTask(formattingRequest: AsyncFormattingRequest): FormattingTask? {
        val project = formattingRequest.context.project

        val editorServiceManager = project.service<EditorServiceManager>()
        val path = formattingRequest.ioFile?.path

        if (path == null) {
            infoLogWithConsole(DprintBundle.message("formatting.cannot.determine.file.path"), project, LOGGER)
            return null
        }

        if (!editorServiceManager.canRangeFormat() && isRangeFormat(formattingRequest)) {
            infoLogWithConsole(DprintBundle.message("external.formatter.range.formatting"), project, LOGGER)
            return null
        }

        if (doAnyRangesIntersect(formattingRequest)) {
            infoLogWithConsole(DprintBundle.message("external.formatter.range.overlapping"), project, LOGGER)
            return null
        }

        infoLogWithConsole(DprintBundle.message("external.formatter.creating.task", path), project, LOGGER)

        return object : FormattingTask {
            val dprintTask = DprintFormattingTask(project, editorServiceManager, formattingRequest, path)

            override fun run() {
                return dprintTask.run()
            }

            override fun cancel(): Boolean {
                return dprintTask.cancel()
            }

            override fun isRunUnderProgress(): Boolean {
                return dprintTask.isRunUnderProgress()
            }
        }
    }

    /**
     * We make assumptions that ranges do not overlap each other in our formatting algorithm.
     */
    private fun doAnyRangesIntersect(formattingRequest: AsyncFormattingRequest): Boolean {
        val ranges = formattingRequest.formattingRanges.sortedBy { textRange -> textRange.startOffset }
        for (i in ranges.indices) {
            if (i + 1 >= ranges.size) {
                continue
            }
            val current = ranges[i]
            val next = ranges[i + 1]

            if (current.intersects(next)) {
                return true
            }
        }
        return false
    }

    private fun isRangeFormat(formattingRequest: AsyncFormattingRequest): Boolean {
        return when {
            formattingRequest.formattingRanges.size > 1 -> true
            formattingRequest.formattingRanges.size == 1 -> {
                val range = formattingRequest.formattingRanges[0]
                return range.startOffset > 0 || range.endOffset < formattingRequest.documentText.length
            }

            else -> false
        }
    }

    override fun getNotificationGroupId(): String {
        return "Dprint"
    }

    override fun getName(): String {
        return NAME
    }
}
