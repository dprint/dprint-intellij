package com.dprint.config

import com.intellij.openapi.components.PersistentStateComponent

/**
 * Base class for configuration that eliminates boilerplate while maintaining type safety.
 * Subclasses only need to define their State class and specify storage details via annotations.
 */
abstract class BaseConfiguration<T : Any> : PersistentStateComponent<T> {
    protected abstract fun createDefaultState(): T

    private var internalState: T? = null

    override fun getState(): T {
        if (internalState == null) {
            internalState = createDefaultState()
        }
        return internalState!!
    }

    override fun loadState(state: T) {
        internalState = state
    }
}
