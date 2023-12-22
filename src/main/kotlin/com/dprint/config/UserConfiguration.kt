package com.dprint.config

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Persists configuration between IDE sessions per project. Configuration is stored in .idea/dprintConfig.xml.
 */
@State(name = "DprintUserConfiguration", storages = [Storage("dprintUserConfig.xml")])
class UserConfiguration : PersistentStateComponent<UserConfiguration.State> {
    class State {
        var runOnSave = false
        var overrideIntelliJFormatter = true
        var enableEditorServiceVerboseLogging = true
    }

    private var internalState = State()

    override fun getState(): State {
        return internalState
    }

    override fun loadState(state: State) {
        internalState = state
    }
}
