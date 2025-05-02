package com.dprint.otel

import com.intellij.platform.diagnostic.telemetry.Scope

object DprintScope {
    val FormatterScope = Scope("com.dprint.formatter")
    val EditorServiceScope = Scope("com.dprint.editorservice")
}
