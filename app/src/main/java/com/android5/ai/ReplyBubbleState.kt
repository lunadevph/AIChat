package com.android5.ai

/** One stable transcript entry for the whole reply, including every tool round. */
internal class ReplyBubbleState(initial: Message? = null) {
    var snapshot = (initial ?: Message(role = "assistant", content = "")).copy(isStreaming = true, isError = false)
        private set

    private fun join(first: String?, second: String?): String =
        listOfNotNull(first?.takeIf { it.isNotBlank() }, second?.takeIf { it.isNotBlank() }).joinToString("\n\n")

    fun preview(text: String, reasoning: String?): Message = snapshot.copy(
        content = join(snapshot.content, text),
        reasoning = join(snapshot.reasoning, reasoning).takeIf { it.isNotBlank() }
    )

    fun commit(text: String, reasoning: String?) {
        snapshot = preview(text, reasoning)
    }

    fun queue(tools: List<ToolRun>) {
        snapshot = snapshot.copy(tools = snapshot.tools.orEmpty() + tools)
    }

    fun update(tool: ToolRun) {
        val tools = snapshot.tools.orEmpty()
        val index = tools.indexOfLast { it.callId == tool.callId }
        snapshot = snapshot.copy(tools = tools.mapIndexed { position, previous ->
            if (position == index) tool else previous
        })
    }

    fun finish(displayed: Message): Message = displayed.copy(
        isStreaming = false,
        tools = displayed.tools?.map {
            if (it.state == "running" || it.state == "queued") it.copy(state = "cancelled", ok = false) else it
        }
    )
}
