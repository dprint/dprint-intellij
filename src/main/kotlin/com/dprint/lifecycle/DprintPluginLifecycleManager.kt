package com.dprint.lifecycle

import com.dprint.i18n.DprintBundle
import com.dprint.services.DprintService
import com.dprint.utils.infoLogWithConsole
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager

private val LOGGER = logger<DprintPluginLifecycleManager>()
private const val DPRINT_PLUGIN_ID = "com.dprint.intellij.plugin"

/**
 * Manages the lifecycle of the Dprint plugin during dynamic plugin operations.
 * Ensures proper cleanup on plugin unload and reinitialization on plugin load.
 *
 * **Note on Dynamic Plugin Support:**
 *
 * While this plugin is not fully dynamic (still requires IDE restart for installation/updates),
 * implementing this lifecycle manager provides several important benefits:
 *
 * 1. **Cleaner Plugin Upgrades** - Even with restart required, ensures proper shutdown
 *    of dprint processes and clean state transitions between plugin versions
 *
 * 2. **Resource Cleanup** - Prevents orphaned dprint CLI processes and memory leaks
 *    during plugin unload, making upgrades more reliable
 *
 * **Why This Plugin Requires Restart:**
 * - Tool window registration (`<toolWindow>` extension)
 * - Formatting service integration (`<formattingService>` extension)
 * - Startup activities (`<postStartupActivity>` extension)
 * - Deep integration with IntelliJ's core editor and formatting systems
 *
 * This is normal and expected behavior for plugins with similar functionality
 * (ESLint, Prettier, SonarLint, etc. all require restart).
 */
class DprintPluginLifecycleManager : DynamicPluginListener {
    override fun beforePluginUnload(
        pluginDescriptor: IdeaPluginDescriptor,
        isUpdate: Boolean,
    ) {
        if (pluginDescriptor.pluginId.idString != DPRINT_PLUGIN_ID) {
            return
        }

        LOGGER.info("Dprint plugin unloading (isUpdate: $isUpdate). Shutting down all services.")
        shutdownAllDprintServices()
    }

    override fun pluginLoaded(pluginDescriptor: IdeaPluginDescriptor) {
        if (pluginDescriptor.pluginId.idString != DPRINT_PLUGIN_ID) {
            return
        }

        LOGGER.info("Dprint plugin loaded. Initializing services for open projects.")
        initializeAllDprintServices()
    }

    /**
     * Shuts down DprintService for all open projects to ensure clean plugin unload.
     */
    private fun shutdownAllDprintServices() {
        val projects = ProjectManager.getInstance().openProjects
        LOGGER.info("Shutting down Dprint services for ${projects.size} open projects")

        projects.forEach { project ->
            try {
                if (!project.isDisposed) {
                    val dprintService = project.service<DprintService>()
                    infoLogWithConsole(
                        DprintBundle.message("lifecycle.plugin.shutdown.service"),
                        project,
                        LOGGER,
                    )
                    dprintService.destroyEditorService()
                }
            } catch (e: Exception) {
                LOGGER.warn("Failed to shutdown Dprint service for project ${project.name}", e)
            }
        }
    }

    /**
     * Initializes DprintService for all open projects after plugin load.
     */
    private fun initializeAllDprintServices() {
        val projects = ProjectManager.getInstance().openProjects
        LOGGER.info("Initializing Dprint services for ${projects.size} open projects")

        projects.forEach { project ->
            try {
                if (!project.isDisposed) {
                    val dprintService = project.service<DprintService>()
                    infoLogWithConsole(
                        DprintBundle.message("lifecycle.plugin.initialize.service"),
                        project,
                        LOGGER,
                    )
                    dprintService.initializeEditorService()
                }
            } catch (e: Exception) {
                LOGGER.warn("Failed to initialize Dprint service for project ${project.name}", e)
            }
        }
    }
}
