package com.dprint.services.editorservice.v5

import java.util.concurrent.ConcurrentHashMap

typealias Handler = (PendingMessages.Result) -> Unit

data class MessageInfo(val handler: Handler, val timeStored: Long)

const val STALE_LENGTH_MS = 10_000

class PendingMessages {
    private val concurrentHashMap = ConcurrentHashMap<Int, MessageInfo>()

    /**
     * @param type The message type for the result. If null
     */
    class Result(val type: MessageType, val data: Any?)

    fun store(id: Int, handler: Handler) {
        concurrentHashMap[id] = MessageInfo(handler, System.currentTimeMillis())
    }

    fun take(id: Int): Handler? {
        val info = concurrentHashMap[id]
        info?.let {
            concurrentHashMap.remove(id)
        }
        return info?.handler
    }

    fun drain(): List<Pair<Int, Handler>> {
        val allEntries = concurrentHashMap.entries.map { Pair(it.key, it.value.handler) }
        concurrentHashMap.clear()
        return allEntries
    }

    fun hasStaleMessages(): Boolean {
        val now = System.currentTimeMillis()
        return concurrentHashMap.values.any { now - it.timeStored > STALE_LENGTH_MS }
    }
}
