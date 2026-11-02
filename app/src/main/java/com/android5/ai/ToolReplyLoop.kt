package com.android5.ai

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class ToolReply(
    val raw: String,
    val index: Int,
    val reasoning: String?,
    val calls: List<StreamToolCall> = emptyList()
)

internal class EmptyReplyException : Exception()

/** A tool response is an intermediate turn. Only a text answer completes the reply. */
internal object ToolReplyLoop {
    suspend fun run(
        maxToolRounds: Int,
        request: suspend (allowTools: Boolean, retryEmpty: Boolean) -> ToolReply?,
        apply: suspend (ToolReply) -> Unit
    ) {
        require(maxToolRounds >= 0)
        var toolRounds = 0
        var retryEmpty = false
        while (true) {
            currentCoroutineContext().ensureActive()
            val allowTools = toolRounds < maxToolRounds
            val reply = request(allowTools, retryEmpty)
            currentCoroutineContext().ensureActive()
            if (reply == null || (reply.raw.isBlank() && reply.calls.isEmpty())) {
                if (retryEmpty) throw EmptyReplyException()
                retryEmpty = true
                continue
            }
            if (!allowTools && reply.calls.isNotEmpty()) throw EmptyReplyException()
            apply(reply)
            if (reply.calls.isEmpty()) return
            toolRounds++
            retryEmpty = false
        }
    }
}
