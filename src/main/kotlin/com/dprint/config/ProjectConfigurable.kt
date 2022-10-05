package com.dprint.config

import com.dprint.core.Bundle
import com.dprint.core.FileUtils
import com.dprint.services.editorservice.EditorServiceManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.layout.PropertyBinding
import com.intellij.ui.layout.panel

private const val CONFIG_ID = "com.dprint.config"

/**
 * Sets up the configuration panel for Dprint in the Tools section of preferences.
 */
class ProjectConfigurable(private val project: Project) : BoundSearchableConfigurable(
    Bundle.message("config.name"), "reference.settings.dprint", CONFIG_ID
) {
    override fun createPanel(): DialogPanel {
        val projectConfig = project.service<ProjectConfiguration>()
        val userConfig = project.service<UserConfiguration>()
        val editorServiceManager = project.service<EditorServiceManager>()

        return panel {
            row {
                comment(Bundle.message("config.description"))
            }
            row {
                checkBox(
                    Bundle.message("config.enable"),
                    { projectConfig.state.enabled },
                    {
                        projectConfig.state.enabled = it

                        if (it) {
                            editorServiceManager.restartEditorService()
                        } else {
                            editorServiceManager.destroyEditorService()
                        }
                    },
                    Bundle.message("config.enable.description")
                )
            }

            row {
                checkBox(
                    Bundle.message("config.run.on.save"),
                    { userConfig.state.runOnSave },
                    { userConfig.state.runOnSave = it },
                    Bundle.message("config.run.on.save.description")
                )
            }

            row {
                checkBox(
                    Bundle.message("config.override.intellij.formatter"),
                    { userConfig.state.overrideIntelliJFormatter },
                    { userConfig.state.overrideIntelliJFormatter = it },
                    Bundle.message("config.override.intellij.formatter.description"),
                )
            }

            row {
                checkBox(
                    Bundle.message("config.verbose.logging"),
                    { userConfig.state.runOnSave },
                    { userConfig.state.runOnSave = it },
                    Bundle.message("config.verbose.logging.description"),
                )
            }

            row {
                label(Bundle.message("config.dprint.config.location"))
                textFieldWithBrowseButton(
                    PropertyBinding(
                        { projectConfig.state.configLocation },
                        {
                            projectConfig.state.configLocation = it
                            editorServiceManager.restartEditorService()
                        }
                    )
                ).withValidationOnInput {
                    if (it.text.isEmpty() || FileUtils.validateConfigFile(project, it.text)) {
                        null
                    } else {
                        this.error(Bundle.message("config.dprint.config.invalid"))
                    }
                }
            }

            row {
                label(Bundle.message("config.dprint.executable.location"))
                textFieldWithBrowseButton(
                    PropertyBinding(
                        { projectConfig.state.executableLocation },
                        {
                            projectConfig.state.executableLocation = it
                            editorServiceManager.restartEditorService()
                        }
                    )
                ).withValidationOnInput {
                    if (it.text.isEmpty() || FileUtils.validateExecutablePath(it.text)) {
                        null
                    } else {
                        this.error(Bundle.message("config.dprint.executable.invalid"))
                    }
                }
            }
            row {
                button(Bundle.message("config.reload")) {
                    editorServiceManager.restartEditorService()
                }
            }
        }
    }
}
