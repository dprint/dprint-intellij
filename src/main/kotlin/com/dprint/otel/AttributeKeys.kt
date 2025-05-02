package com.dprint.otel

import io.opentelemetry.api.common.AttributeKey

/** OpenTelemetry attribute keys */
object AttributeKeys {
    val FILE_PATH = AttributeKey.stringKey("file.path")
    val CONFIG_PATH = AttributeKey.stringKey("config.path")
    val SCHEMA_VERSION = AttributeKey.longKey("schema.version")
    val TIMEOUT_MS = AttributeKey.longKey("timeout.ms")
    val RANGE_START = AttributeKey.longKey("range.start")
    val RANGE_END = AttributeKey.longKey("range.end")
    val FORMATTING_ID = AttributeKey.longKey("formatting.id")
    val CONTENT_LENGTH = AttributeKey.longKey("content.length")
}
