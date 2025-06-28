package com.dprint.services.editorservice.v5

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * Request data including the deferred result and timestamp for stale detection
 */
data class PendingRequest(val deferred: CompletableDeferred<MessageChannel.Result>, val timestamp: Long)

/**
 * Channel-based message handling that replaces PendingMessages.
 * Uses CompletableDeferred for cleaner async operations and built-in timeout handling.
 * Includes proper timestamp-based stale message detection.
 */
@Service(Service.Level.PROJECT)
class MessageChannel(private val project: Project) {
    private val pendingRequests = ConcurrentHashMap<Int, PendingRequest>()

    companion object {
        private const val DEFAULT_STALE_TIMEOUT_MS = 30_000L // 30 seconds - increased from original 10s
    }

    /**
     * Result wrapper that matches the original PendingMessages.Result structure
     */
    data class Result(val type: MessageType, val data: Any?)

    /**
     * Send a request and wait for the response with timeout
     */
    suspend fun sendRequest(
        id: Int,
        timeoutMs: Long,
    ): Result? {
        val deferred = CompletableDeferred<Result>()
        pendingRequests[id] = PendingRequest(deferred, System.currentTimeMillis())

        return try {
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            pendingRequests.remove(id)
            null
        }
    }

    /**
     * Receive a response for a pending request
     */
    fun receiveResponse(
        id: Int,
        result: Result,
    ) {
        pendingRequests.remove(id)?.deferred?.complete(result)
    }

    /**
     * Cancel a specific request
     */
    fun cancelRequest(id: Int): Boolean {
        return pendingRequests.remove(id)?.let { pendingRequest ->
            pendingRequest.deferred.complete(Result(MessageType.Dropped, null))
            true
        } ?: false
    }

    /**
     * Cancel all pending requests and return their IDs
     */
    fun cancelAllRequests(): List<Int> {
        val cancelledIds = pendingRequests.keys.toList()
        val droppedResult = Result(MessageType.Dropped, null)

        pendingRequests.values.forEach { pendingRequest ->
            pendingRequest.deferred.complete(droppedResult)
        }
        pendingRequests.clear()

        return cancelledIds
    }

    /**
     * Check if there are any stale requests based on timestamp.
     * Stale requests are those that have been pending longer than the timeout threshold.
     * This replaces the broken hasStaleMessages logic with proper time-based detection.
     */
    fun hasStaleRequests(staleTimeoutMs: Long = DEFAULT_STALE_TIMEOUT_MS): Boolean {
        val now = System.currentTimeMillis()
        return pendingRequests.values.any { pendingRequest ->
            now - pendingRequest.timestamp > staleTimeoutMs
        }
    }

    /**
     * Remove and cancel any stale requests that are older than the timeout.
     * Returns the number of stale requests that were removed.
     */
    fun removeStaleRequests(staleTimeoutMs: Long = DEFAULT_STALE_TIMEOUT_MS): Int {
        val now = System.currentTimeMillis()
        val staleIds = mutableListOf<Int>()
        val droppedResult = Result(MessageType.Dropped, null)

        pendingRequests.forEach { (id, pendingRequest) ->
            if (now - pendingRequest.timestamp > staleTimeoutMs) {
                staleIds.add(id)
                pendingRequest.deferred.complete(droppedResult)
            }
        }

        staleIds.forEach { id ->
            pendingRequests.remove(id)
        }

        return staleIds.size
    }
}
