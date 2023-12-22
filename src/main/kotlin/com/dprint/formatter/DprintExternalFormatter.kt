package com.dprint.formatter

import com.dprint.config.ProjectConfiguration
import com.dprint.config.UserConfiguration
import com.dprint.i18n.DprintBundle
import com.dprint.services.editorservice.EditorServiceManager
import com.dprint.services.editorservice.FormatResult
import com.dprint.utils.errorLogWithConsole
import com.dprint.utils.infoConsole
import com.dprint.utils.infoLogWithConsole
import com.dprint.utils.isFormattableFile
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val LOGGER = logger<DprintExternalFormatter>()
private const val NAME = "dprintfmt"
private const val FORMATTING_TIMEOUT = 10L

/**
 * Thia class is the recommended way to implement an external formatter in the IJ
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
        // To ensure that we don't allow IntelliJ range formatting on files that should be dprint formatted we need to
        // say we provide the FORMAT_FRAGMENTS features then handle them as a no op.
        return mutableSetOf(FormattingService.Feature.FORMAT_FRAGMENTS)
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
            virtualFile != null &&
                isFormattableFile(file.project, virtualFile) &&
                editorServiceManager.canFormatCached(virtualFile.path) != false

        if (canFormat) {
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

        // This exists as well as the condition in the canFormat method as that will prime the cache if
        // the file hasn't already been checked. Should probably never happen but let's be safe.
        if (editorServiceManager.canFormatCached(path) != true) return null

        if (!editorServiceManager.canRangeFormat() && isRangeFormat(formattingRequest)) {
            infoLogWithConsole(DprintBundle.message("external.formatter.range.formatting"), project, LOGGER)
            return null
        }

        infoLogWithConsole(DprintBundle.message("external.formatter.creating.task", path), project, LOGGER)

        return object : FormattingTask {
            private var formattingId: Int? = editorServiceManager.maybeGetFormatId()
            private var isCancelled = false
            private val baseFormatFuture = CompletableFuture<FormatResult>()
            private var activeFormatFuture: CompletableFuture<FormatResult>? = null

            override fun run() {
                val content = formattingRequest.documentText
                val ranges = formattingRequest.formattingRanges

                infoLogWithConsole(
                    DprintBundle.message("external.formatter.running.task", formattingId ?: path),
                    project,
                    LOGGER,
                )

                for (range in ranges.subList(1, ranges.size)) {
                    baseFormatFuture.thenApply {
                        if (isCancelled) {
                            it
                        } else {
                            val resultContent = it.formattedContent
                            val nextFuture = CompletableFuture<FormatResult>()
                            activeFormatFuture = nextFuture
                            val nextHandler: (FormatResult) -> Unit = { nextResult ->
                                nextFuture.complete(nextResult)
                            }
                            if (resultContent != null) {
                                // Need to update the formatting id so the correct job would be cancelled
                                formattingId = editorServiceManager.maybeGetFormatId()
                                editorServiceManager.format(
                                    formattingId,
                                    path,
                                    resultContent,
                                    range.startOffset,
                                    getEndOfRange(content, range),
                                    nextHandler,
                                )
                            }

                            getFuture(nextFuture)
                        }
                    }
                }

                // If the version can't range format we always return null, in this case the underlying process will
                // extract the start byte position (0) and end byte position (content.encodeToByteArray().size) for the
                // whole file
                val initialRange =
                    if (editorServiceManager.canRangeFormat() && ranges.size > 0) ranges.first() else null
                val initialHandler: (FormatResult) -> Unit = {
                    baseFormatFuture.complete(it)
                }

                editorServiceManager.format(
                    formattingId,
                    path,
                    content,
                    initialRange?.startOffset,
                    getEndOfRange(content, initialRange),
                    initialHandler,
                )
                // Timeouts are handled at the EditorServiceManager level and an empty result will be
                // returned if something goes wrong
                val result = getFuture(baseFormatFuture)

                if (isCancelled || result == null) return

                val error = result.error
                if (error != null) {
                    formattingRequest.onError(DprintBundle.message("formatting.error"), error)
                } else {
                    // If the result is a no op it will be null, in which case we pass the original content back in
                    formattingRequest.onTextReady(result.formattedContent ?: content)
                }
            }

            private fun getFuture(future: CompletableFuture<FormatResult>): FormatResult? {
                return try {
                    future.get(FORMATTING_TIMEOUT, TimeUnit.SECONDS)
                } catch (e: CancellationException) {
                    errorLogWithConsole("External format process cancelled", e, project, LOGGER)
                    null
                } catch (e: TimeoutException) {
                    errorLogWithConsole("External format process timed out", e, project, LOGGER)
                    formattingRequest.onError("Dprint external formatter", "Format process timed out")
                    editorServiceManager.restartEditorService()
                    null
                } catch (e: ExecutionException) {
                    errorLogWithConsole("External format process failed", e, project, LOGGER)
                    formattingRequest.onError("Dprint external formatter", "Format process failed")
                    editorServiceManager.restartEditorService()
                    null
                } catch (e: InterruptedException) {
                    errorLogWithConsole("External format process interrupted", e, project, LOGGER)
                    formattingRequest.onError("Dprint external formatter", "Format process interrupted")
                    editorServiceManager.restartEditorService()
                    null
                }
            }

            override fun cancel(): Boolean {
                if (!editorServiceManager.canCancelFormat()) return false

                val formatId = formattingId
                isCancelled = true
                if (formatId != null) {
                    infoLogWithConsole(
                        DprintBundle.message("external.formatter.cancelling.task", formattingId ?: path),
                        project,
                        LOGGER,
                    )
                    editorServiceManager.cancelFormat(formatId)
                }
                // Clean up state so process can complete
                baseFormatFuture.cancel(true)
                activeFormatFuture?.cancel(true)
                return true
            }

            override fun isRunUnderProgress(): Boolean {
                return true
            }
        }
    }

    /**
     * This function gets around an issue where IntelliJ will sometimes send in a formatting range that
     * is greater than the actual length of the file. Doing this will cause a no-op in dprint for a format.
     */
    private fun getEndOfRange(
        content: String,
        range: TextRange?,
    ): Int? {
        return when {
            range == null -> null
            range.endOffset > content.length -> content.length
            else -> range.endOffset
        }
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
