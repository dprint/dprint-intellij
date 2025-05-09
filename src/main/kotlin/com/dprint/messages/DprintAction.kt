package com.dprint.messages

import com.intellij.util.messages.Topic

interface DprintAction {

    companion object {
        val DPRINT_ACTION_TOPIC: Topic<DprintAction> = Topic.create("dprint.action", DprintAction::class.java)
    }

    fun formattingStarted(filePath: String)

    fun formattingSkipped(filePath: String)

    fun formattingSucceeded(filePath: String, timeElapsed: Long)

    fun formattingFailed(filePath: String, timeElapsed: Long, message: String?)
}