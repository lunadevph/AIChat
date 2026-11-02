package com.android5.ai

import com.google.gson.JsonParser

/** Provider wire formats are translated into the same scoped executor. */
object NativeTools {
    private val descriptions = mapOf(
        "read" to "Read a text file at path.", "cat" to "Read a text file at path.",
        "write" to "Create or overwrite path with content.", "append" to "Append content to path.",
        "edit" to "Replace exact find text with replace text in path; all=true replaces every match.",
        "replace" to "Replace regex pattern with replace text in path; optional ignorecase and first.",
        "list" to "List a directory; path defaults to workspace.",
        "search" to "Search file contents for query, optionally under path.",
        "find" to "Find filenames matching glob pattern, optionally under path.",
        "stat" to "Inspect file metadata at path.", "tail" to "Read last lines of path; default 50.",
        "delete" to "Delete file or folder at path.", "mkdir" to "Create directory at path.",
        "move" to "Move from to to.", "copy" to "Copy from to to.",
        "download" to "Download url to optional path, maximum 25 MiB.",
        "fetch" to "Fetch url as text.", "upload" to "Copy device path into workspace at optional to.",
        "open" to "Open path in the user's viewer.", "storage" to "Inspect allowed storage and free space.",
        "memory" to "Read saved memory, or set complete memory using content; mode=append appends.",
        "compact" to "Deduplicate and shorten memory to max characters.",
        "websearch" to "Search the web for query.", "time" to "Read current date and time.",
        "shell" to "Run an Android shell command as the app; required command, optional relative cwd and timeout in seconds (1-60). Returns merged output and exit code. The working directory does not restrict filesystem access.",
        "exec" to "Alias for shell: execute command with optional cwd and timeout (1-60 seconds)."
    )
    private val parameters = mapOf(
        "read" to listOf("path"), "cat" to listOf("path"),
        "write" to listOf("path", "content"), "append" to listOf("path", "content"),
        "edit" to listOf("path", "find", "replace", "all"),
        "replace" to listOf("path", "pattern", "replace", "ignorecase", "first"),
        "list" to listOf("path"), "search" to listOf("query", "path"),
        "find" to listOf("pattern", "path"), "stat" to listOf("path"),
        "tail" to listOf("path", "lines"), "delete" to listOf("path"),
        "mkdir" to listOf("path"), "move" to listOf("from", "to"), "copy" to listOf("from", "to"),
        "download" to listOf("url", "path"), "fetch" to listOf("url"),
        "upload" to listOf("path", "to"), "open" to listOf("path"), "storage" to emptyList(),
        "memory" to listOf("content", "mode"), "compact" to listOf("max"),
        "websearch" to listOf("query"), "time" to emptyList(),
        "shell" to listOf("command", "cwd", "timeout"), "exec" to listOf("command", "cwd", "timeout")
    )
    private val optional = setOf("list", "storage", "memory", "compact", "time")
    private fun required(name: String): List<String> = when (name) {
        "write", "append" -> listOf("path", "content")
        "edit" -> listOf("path", "find", "replace")
        "replace" -> listOf("path", "pattern", "replace")
        "move", "copy" -> listOf("from", "to")
        in optional -> emptyList()
        else -> parameters.getValue(name).take(1)
    }
    private fun schema(name: String): Map<String, Any> = mapOf(
        "type" to "object", "properties" to parameters.getValue(name).associateWith {
            mapOf("type" to when (it) { "all", "ignorecase", "first" -> "boolean"; "lines", "max", "timeout" -> "integer"; else -> "string" })
        }, "required" to required(name), "additionalProperties" to false
    )

    fun openAiDefinitions(): List<Map<String, Any>> = descriptions.map { (name, description) ->
        mapOf("type" to "function", "function" to mapOf("name" to name,
            "description" to description, "parameters" to schema(name)))
    }

    fun anthropicDefinitions(): List<Map<String, Any>> = descriptions.map { (name, description) ->
        mapOf("name" to name, "description" to description, "input_schema" to schema(name))
    }

    fun decode(call: StreamToolCall): AiTools.Call {
        val name = requireNotNull(call.function?.name)
        require(name in descriptions) { "Unknown tool: $name" }
        val args = JsonParser.parseString(call.function?.arguments?.takeIf { it.isNotBlank() } ?: "{}").asJsonObject
        require(args.entrySet().all { (_, value) -> value.isJsonPrimitive }) { "Tool arguments must be scalar values" }
        require(args.keySet().all { it in parameters.getValue(name) } && required(name).all { args.has(it) }) {
            "Unknown or missing tool arguments"
        }
        val attrs = args.entrySet().associate { (key, value) -> key to value.asString }
        return AiTools.Call(name, attrs, "", "")
    }

    fun exchange(text: String, reasoning: String?, calls: List<StreamToolCall>, runs: List<AiTools.Run>): List<ApiMessage> {
        require(calls.size == runs.size)
        return listOf(ApiMessage("assistant", text.takeIf { it.isNotBlank() }, toolCalls = calls,
            reasoningContent = reasoning ?: "")) + calls.zip(runs).map { (call, run) ->
            ApiMessage("tool", com.google.gson.Gson().toJson(mapOf("ok" to run.ok, "result" to run.result)), toolCallId = call.id)
        }
    }

    /** Rebuild complete tool exchanges saved in the existing tools JSON column. */
    fun restoreExchange(message: Message): List<ApiMessage> {
        val tools = message.tools.orEmpty()
        if (message.role != "assistant" || tools.isEmpty() || tools.any { it.callId == null || it.arguments == null || it.result == null }) return emptyList()
        val calls = tools.map { StreamToolCall(id = it.callId, type = "function", function = StreamToolFunction(it.name, it.arguments?.ifBlank { "{}" })) }
        return exchange(message.content, message.reasoning, calls, tools.map { AiTools.Run(it.name, it.ok, it.label, it.result!!) })
    }

    /** SSE tool arguments arrive in fragments and can interleave by index. */
    class Accumulator {
        private data class Partial(var id: String = "", var name: String = "", val arguments: StringBuilder = StringBuilder())
        private val partials = sortedMapOf<Int, Partial>()
        fun add(delta: StreamToolCall) {
            val partial = partials.getOrPut(delta.index ?: 0) { Partial() }
            delta.id?.let { partial.id += it }
            delta.function?.name?.let { partial.name += it }
            delta.function?.arguments?.let { partial.arguments.append(it) }
            require(partials.size <= 32 && partial.arguments.length <= 1_000_000) { "Tool call exceeds size limit" }
        }
        fun complete(): List<StreamToolCall> = partials.values.map {
            require(it.id.isNotBlank() && it.name.isNotBlank()) { "Incomplete tool call" }
            StreamToolCall(id = it.id, type = "function", function = StreamToolFunction(it.name, it.arguments.toString().ifBlank { "{}" }))
        }
    }
}
