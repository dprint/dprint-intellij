package com.dprint.config

import com.dprint.core.Bundle
import com.dprint.core.FileUtils
import com.dprint.services.editorservice.EditorServiceManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

private const val CONFIG_ID = "com.dprint.config"

/**
 * Sets up the configuration panel for Dprint in the Tools section of preferences.
 */
class ProjectConfigurable(private val project: Project) : BoundSearchableConfigurable(
    Bundle.message("config.name"), "reference.settings.dprint", CONFIG_ID
) {
    @Suppress("LongMethod")
    override fun createPanel(): DialogPanel {
        val projectConfig = project.service<ProjectConfiguration>()
        val userConfig = project.service<UserConfiguration>()
        val editorServiceManager = project.service<EditorServiceManager>()

        return panel {
            // Restart or destroy editor service on apply
            onApply {
                if (projectConfig.state.enabled) {
                    editorServiceManager.restartEditorService()
                } else {
                    editorServiceManager.destroyEditorService()
                }
            }

            // Settings files explanations
            row {
                rowComment(Bundle.message("config.description"))
            }

            // Enabled checkbox
            row {
                checkBox(Bundle.message("config.enable"))
                    .bindSelected(
                        { projectConfig.state.enabled },
                        { projectConfig.state.enabled = it }
                    )
                    .comment(Bundle.message("config.enable.description"))
            }

            // Format on save checkbox
            row {
                checkBox(Bundle.message("config.run.on.save"))
                    .bindSelected(
                        { userConfig.state.runOnSave },
                        { userConfig.state.runOnSave = it }
                    )
                    .comment(Bundle.message("config.run.on.save.description"))
            }

            // Default IJ override checkbox
            row {
                checkBox(Bundle.message("config.override.intellij.formatter"))
                    .bindSelected(
                        { userConfig.state.overrideIntelliJFormatter },
                        { userConfig.state.overrideIntelliJFormatter = it }
                    )
                    .comment(Bundle.message("config.override.intellij.formatter.description"))
            }

            // Verbose logging checkbox
            row {
                checkBox(Bundle.message("config.verbose.logging"))
                    .bindSelected(
                        { userConfig.state.runOnSave },
                        { userConfig.state.runOnSave = it }
                    )
                    .comment(Bundle.message("config.verbose.logging.description"))
            }

            // dprint.json path input and file finder
            indent {
                row {
                    textFieldWithBrowseButton()
                        .bindText(
                            { projectConfig.state.configLocation },
                            { projectConfig.state.configLocation = it }
                        )
                        .label(Bundle.message("config.dprint.config.path"), LabelPosition.TOP)
                        .comment(Bundle.message("config.dprint.config.path.description"))
                        .validationOnInput {
                            if (it.text.isEmpty() || FileUtils.validateConfigFile(project, it.text)) {
                                null
                            } else {
                                this.error(Bundle.message("config.dprint.config.invalid"))
                            }
                        }
                }
            }

            // dprint executable input and file finder
            indent {
                row {
                    textFieldWithBrowseButton()
                        .bindText(
                            { projectConfig.state.executableLocation },
                            { projectConfig.state.executableLocation = it }
                        )
                        .label(Bundle.message("config.dprint.executable.path"), LabelPosition.TOP)
                        .comment(Bundle.message("config.dprint.executable.path.description"))
                        .validationOnInput {
                            if (it.text.isEmpty() || FileUtils.validateExecutablePath(it.text)) {
                                null
                            } else {
                                this.error(Bundle.message("config.dprint.executable.invalid"))
                            }
                        }
                }
            }

            // Restart button
            indent {
                row {
                    button(Bundle.message("config.reload")) {
                        editorServiceManager.restartEditorService()
                    }.comment(Bundle.message("config.reload.description"))
                }
            }
        }
    }
}
