package com.dprint.messages

import com.intellij.util.messages.Topic

/**
 * A message for the internal IntelliJ message bus that allows us to push logging information to a tool window
 */
interface DprintMessage {
    companion object {
        val DPRINT_MESSAGE_TOPIC = Topic("dprint event message", Listener::class.java)
    }

    interface Listener {
        fun info(message: String)
        fun warn(message: String)
        fun error(message: String)
    }
}
