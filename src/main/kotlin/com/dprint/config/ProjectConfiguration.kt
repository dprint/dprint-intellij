package com.dprint.config

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Project-level configuration that should be consistent across the team.
 * These settings are stored in .idea/dprintProjectConfig.xml and can be checked into version control.
 */
@Service(Service.Level.PROJECT)
@State(name = "DprintProjectConfiguration", storages = [Storage("dprintProjectConfig.xml")])
class ProjectConfiguration : BaseConfiguration<ProjectConfiguration.State>() {
    data class State(
        var enabled: Boolean = false,
        var configLocation: String = "",
        var executableLocation: String = "",
        var initialisationTimeout: Long = 10_000,
        var commandTimeout: Long = 5_000,
    )

    override fun createDefaultState(): State = State()
}
