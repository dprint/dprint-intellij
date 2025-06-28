package com.dprint.config

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * User-level configuration for personal preferences.
 * These settings are stored in .idea/dprintUserConfig.xml and should NOT be checked into version control.
 */
@Service(Service.Level.PROJECT)
@State(name = "DprintUserConfiguration", storages = [Storage("dprintUserConfig.xml")])
class UserConfiguration : BaseConfiguration<UserConfiguration.State>() {
    data class State(
        var runOnSave: Boolean = false,
        var overrideIntelliJFormatter: Boolean = true,
        var enableEditorServiceVerboseLogging: Boolean = true,
    )

    override fun createDefaultState(): State = State()
}
