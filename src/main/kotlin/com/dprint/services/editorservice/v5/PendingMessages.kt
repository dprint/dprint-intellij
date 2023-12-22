package com.dprint.services.editorservice.v5

import java.util.concurrent.ConcurrentHashMap

typealias Handler = (PendingMessages.Result) -> Unit

class PendingMessages {
    private val concurrentHashMap = ConcurrentHashMap<Int, Handler>()

    /**
     * @param type The message type for the result. If null
     */
    class Result(val type: MessageType, val data: Any?)

    fun store(
        id: Int,
        handler: Handler,
    ) {
        concurrentHashMap[id] = handler
    }

    fun take(id: Int): Handler? {
        val handlers = concurrentHashMap[id]
        handlers?.let {
            concurrentHashMap.remove(id)
        }
        return handlers
    }

    fun drain(): List<MutableMap.MutableEntry<Int, Handler>> {
        val allEntries = concurrentHashMap.entries.toList()
        concurrentHashMap.clear()
        return allEntries
    }
}
