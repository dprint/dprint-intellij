package com.dprint.config

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Persists configuration between IDE sessions per project. Configuration is stored in .idea/dprintConfig.xml.
 */
@Service(Service.Level.PROJECT)
@State(name = "DprintProjectConfiguration", storages = [Storage("dprintProjectConfig.xml")])
class ProjectConfiguration : PersistentStateComponent<ProjectConfiguration.State> {
    class State {
        var enabled: Boolean = false
        var configLocation: String = ""
        var executableLocation: String = ""
    }

    private var internalState = State()

    override fun getState(): State {
        return internalState
    }

    override fun loadState(state: State) {
        internalState = state
    }
}
