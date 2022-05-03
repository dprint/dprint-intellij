package com.dprint.services.editorservice.v5

import java.util.concurrent.ConcurrentHashMap

private typealias Handler = (PendingMessages.Result) -> Unit

class PendingMessages {
    private val concurrentHashMap = ConcurrentHashMap<Int, Handler>()

    class Result(val type: MessageType, val data: Any?)

    fun store(id: Int, handler: Handler) {
        concurrentHashMap[id] = handler
    }

    fun take(id: Int): Handler? {
        val handlers = concurrentHashMap[id]
        handlers?.let {
            concurrentHashMap.remove(id)
        }
        return handlers
    }

    fun drain(): List<Handler> {
        val allHandlers = concurrentHashMap.values.toList()
        concurrentHashMap.clear()
        return allHandlers
    }
}
