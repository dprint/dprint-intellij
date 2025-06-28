package com.dprint.config

import com.dprint.i18n.DprintBundle
import com.dprint.services.editorservice.EditorServiceManager
import com.dprint.utils.validateConfigFile
import com.dprint.utils.validateExecutablePath
import com.intellij.ide.actionsOnSave.ActionOnSaveBackedByOwnConfigurable
import com.intellij.ide.actionsOnSave.ActionOnSaveContext
import com.intellij.ide.actionsOnSave.ActionOnSaveInfo
import com.intellij.ide.actionsOnSave.ActionOnSaveInfoProvider
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.JCheckBox

const val CONFIG_ID = "com.dprint.config"

/**
 * Sets up the configuration panel for Dprint in the Tools section of preferences.
 */
class DprintConfigurable(private val project: Project) : BoundSearchableConfigurable(
    DprintBundle.message("config.name"),
    "reference.settings.dprint",
    CONFIG_ID,
) {
    lateinit var enabledCheckBox: JCheckBox
    lateinit var runOnSaveCheckbox: JCheckBox

    override fun createPanel(): DialogPanel {
        val projectConfig = project.service<ProjectConfiguration>()
        val userConfig = project.service<UserConfiguration>()
        val dprintService = project.service<EditorServiceManager>()

        return panel {
            // Restart or destroy editor service on apply
            onApply {
                if (projectConfig.state.enabled) {
                    dprintService.restartEditorService()
                } else {
                    dprintService.destroyEditorService()
                }
            }

            // Enabled checkbox
            row {
                enabledCheckBox =
                    checkBox(DprintBundle.message("config.enable"))
                        .bindSelected(
                            { projectConfig.state.enabled },
                            { projectConfig.state.enabled = it },
                        )
                        .comment(DprintBundle.message("config.enable.description"))
                        .component
            }

            // Format on save checkbox
            row {
                runOnSaveCheckbox =
                    checkBox(DprintBundle.message("config.run.on.save"))
                        .bindSelected(
                            { userConfig.state.runOnSave },
                            { userConfig.state.runOnSave = it },
                        )
                        .comment(DprintBundle.message("config.run.on.save.description"))
                        .component
            }

            // Default IJ override checkbox
            row {
                checkBox(DprintBundle.message("config.override.intellij.formatter"))
                    .bindSelected(
                        { userConfig.state.overrideIntelliJFormatter },
                        { userConfig.state.overrideIntelliJFormatter = it },
                    )
                    .comment(DprintBundle.message("config.override.intellij.formatter.description"))
            }

            // Verbose logging checkbox
            row {
                checkBox(DprintBundle.message("config.verbose.logging"))
                    .bindSelected(
                        { userConfig.state.enableEditorServiceVerboseLogging },
                        { userConfig.state.enableEditorServiceVerboseLogging = it },
                    )
                    .comment(DprintBundle.message("config.verbose.logging.description"))
            }

            // dprint.json path input and file finder
            indent {
                row {
                    textFieldWithBrowseButton()
                        .bindText(
                            { projectConfig.state.configLocation },
                            { projectConfig.state.configLocation = it },
                        )
                        .label(DprintBundle.message("config.dprint.config.path"), LabelPosition.TOP)
                        .comment(DprintBundle.message("config.dprint.config.path.description"))
                        .validationOnInput {
                            if (it.text.isEmpty() || validateConfigFile(it.text)) {
                                null
                            } else {
                                this.error(DprintBundle.message("config.dprint.config.invalid"))
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
                            { projectConfig.state.executableLocation = it },
                        )
                        .label(DprintBundle.message("config.dprint.executable.path"), LabelPosition.TOP)
                        .comment(DprintBundle.message("config.dprint.executable.path.description"))
                        .validationOnInput {
                            if (it.text.isEmpty() || validateExecutablePath(it.text)) {
                                null
                            } else {
                                this.error(DprintBundle.message("config.dprint.executable.invalid"))
                            }
                        }
                }
            }

            indent {
                row {
                    intTextField(IntRange(0, 100_000), 1_000)
                        .bindText(
                            { projectConfig.state.initialisationTimeout.toString() },
                            { projectConfig.state.initialisationTimeout = it.toLong() },
                        )
                        .label(DprintBundle.message("config.dprint.initialisation.timeout"), LabelPosition.TOP)
                        .comment(DprintBundle.message("config.dprint.initialisation.timeout.description"))
                        .validationOnInput {
                            if (it.text.toLongOrNull() != null) {
                                null
                            } else {
                                this.error(DprintBundle.message("config.dprint.initialisation.timeout.error"))
                            }
                        }
                }
            }

            indent {
                row {
                    intTextField(IntRange(0, 100_000), 1_000)
                        .bindText(
                            { projectConfig.state.commandTimeout.toString() },
                            { projectConfig.state.commandTimeout = it.toLong() },
                        )
                        .label(DprintBundle.message("config.dprint.command.timeout"), LabelPosition.TOP)
                        .comment(DprintBundle.message("config.dprint.command.timeout.description"))
                        .validationOnInput {
                            if (it.text.toLongOrNull() != null) {
                                null
                            } else {
                                this.error(DprintBundle.message("config.dprint.command.timeout.error"))
                            }
                        }
                }
            }

            // Reset to defaults button
            indent {
                row {
                    button(DprintBundle.message("config.reset")) {
                        // Reset project configuration to defaults
                        val defaultProjectState = ProjectConfiguration.State()
                        projectConfig.state.enabled = defaultProjectState.enabled
                        projectConfig.state.configLocation = defaultProjectState.configLocation
                        projectConfig.state.executableLocation = defaultProjectState.executableLocation
                        projectConfig.state.initialisationTimeout = defaultProjectState.initialisationTimeout
                        projectConfig.state.commandTimeout = defaultProjectState.commandTimeout

                        // Reset user configuration to defaults
                        val defaultUserState = UserConfiguration.State()
                        userConfig.state.runOnSave = defaultUserState.runOnSave
                        userConfig.state.overrideIntelliJFormatter =
                            defaultUserState.overrideIntelliJFormatter
                        userConfig.state.enableEditorServiceVerboseLogging =
                            defaultUserState.enableEditorServiceVerboseLogging

                        // Reset the form to reflect the new values
                        reset()

                        // Restart dprint service with new defaults
                        if (projectConfig.state.enabled) {
                            dprintService.restartEditorService()
                        } else {
                            dprintService.destroyEditorService()
                        }
                    }.comment(DprintBundle.message("config.reset.description"))
                }
            }
        }
    }

    // This is used so that we can also have our "run on save" checkbox in the
    // Tools -> Actions On Save menu
    class DprintActionOnSaveInfoProvider : ActionOnSaveInfoProvider() {
        override fun getActionOnSaveInfos(
            actionOnSaveContext: ActionOnSaveContext,
        ): MutableCollection<out ActionOnSaveInfo> {
            return mutableListOf(DprintActionOnSaveInfo(actionOnSaveContext))
        }

        override fun getSearchableOptions(): MutableCollection<String> {
            return mutableSetOf(DprintBundle.message("config.dprint.actions.on.save.run.dprint"))
        }
    }

    private class DprintActionOnSaveInfo(actionOnSaveContext: ActionOnSaveContext) :
        ActionOnSaveBackedByOwnConfigurable<DprintConfigurable>(
            actionOnSaveContext,
            CONFIG_ID,
            DprintConfigurable::class.java,
        ) {
        override fun getActionOnSaveName() = DprintBundle.message("config.dprint.actions.on.save.run.dprint")

        override fun isApplicableAccordingToStoredState(): Boolean {
            return project.service<ProjectConfiguration>().state.enabled
        }

        override fun isApplicableAccordingToUiState(configurable: DprintConfigurable): Boolean {
            return configurable.enabledCheckBox.isSelected
        }

        override fun isActionOnSaveEnabledAccordingToStoredState(): Boolean {
            return project.service<UserConfiguration>().state.runOnSave
        }

        override fun isActionOnSaveEnabledAccordingToUiState(configurable: DprintConfigurable) =
            configurable.runOnSaveCheckbox.isSelected

        override fun setActionOnSaveEnabled(
            configurable: DprintConfigurable,
            enabled: Boolean,
        ) {
            configurable.runOnSaveCheckbox.isSelected = enabled
        }
    }
}
