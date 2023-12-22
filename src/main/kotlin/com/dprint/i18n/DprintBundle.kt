package com.dprint.i18n

import com.intellij.AbstractBundle
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val DPRINT_BUNDLE = "messages.Bundle"

object DprintBundle : AbstractBundle(DPRINT_BUNDLE) {
    @Suppress("SpreadOperator")
    @JvmStatic
    fun message(
        @PropertyKey(resourceBundle = DPRINT_BUNDLE) key: String,
        vararg params: Any,
    ) = getMessage(key, *params)
}
