package com.dprint.services.editorservice.v5

enum class MessageType(
    val intValue: Int,
) {
    /**
     * Used when pending messages are drained due to a shutdow so that the handlers can be completed with no action.
     */
    Dropped(-1),
    SuccessResponse(0),
    ErrorResponse(1),
    ShutDownProcess(2),
    Active(3),
    CanFormat(4),
    CanFormatResponse(5),
    FormatFile(6),
    FormatFileResponse(7),
    CancelFormat(8),
}
