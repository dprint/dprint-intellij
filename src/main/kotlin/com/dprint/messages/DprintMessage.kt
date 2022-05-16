package com.dprint.messages

import com.intellij.util.messages.Topic

interface DprintMessage {
    companion object {
        val DPRINT_MESSAGE_TOPIC = Topic("dprint event message", DprintMessageListener::class.java)
    }

    fun interface DprintMessageListener {
        fun printMessage(message: String)
    }
}
