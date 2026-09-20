package com.android5.ai

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class ConversationStore(context: Context) {

    private val context = context.applicationContext
    private val chatsDir = File(context.filesDir, "chats").apply { mkdirs() }
    private val indexDb = File(chatsDir, "index.db")
    private val gson = Gson()

    init {
        migrateLegacyJson()
    }

    fun listConversations(): List<Conversation> {
        val result = mutableListOf<Conversation>()
        val db = SQLiteDatabase.openOrCreateDatabase(indexDb, null)
        try {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS conversations (" +
                    "id TEXT PRIMARY KEY, title TEXT, last_updated INTEGER NOT NULL)"
            )
            val cursor = db.rawQuery(
                "SELECT id, title, last_updated FROM conversations ORDER BY last_updated DESC",
                null
            )
            cursor.use {
                val idIdx = it.getColumnIndexOrThrow("id")
                val titleIdx = it.getColumnIndexOrThrow("title")
                val updatedIdx = it.getColumnIndexOrThrow("last_updated")
                while (it.moveToNext()) {
                    result.add(
                        Conversation(
                            id = it.getString(idIdx),
                            title = it.getString(titleIdx),
                            lastUpdated = it.getLong(updatedIdx)
                        )
                    )
                }
            }
        } catch (_: SQLiteException) {
        } finally {
            db.close()
        }
        return result
    }

    fun loadConversation(id: String): Conversation? {
        val index = SQLiteDatabase.openOrCreateDatabase(indexDb, null)
        var meta: Conversation? = null
        try {
            index.execSQL(
                "CREATE TABLE IF NOT EXISTS conversations (" +
                    "id TEXT PRIMARY KEY, title TEXT, last_updated INTEGER NOT NULL)"
            )
            val cursor = index.rawQuery(
                "SELECT id, title, last_updated FROM conversations WHERE id = ?",
                arrayOf(id)
            )
            cursor.use {
                if (it.moveToFirst()) {
                    meta = Conversation(
                        id = it.getString(it.getColumnIndexOrThrow("id")),
                        title = it.getString(it.getColumnIndexOrThrow("title")),
                        lastUpdated = it.getLong(it.getColumnIndexOrThrow("last_updated"))
                    )
                }
            }
        } finally {
            index.close()
        }
        if (meta == null) return null

        val dbFile = chatDbFile(id)
        if (!dbFile.exists()) return meta

        val messages = mutableListOf<Message>()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS messages (" +
                    "id TEXT PRIMARY KEY, role TEXT NOT NULL, content TEXT NOT NULL, " +
                    "reasoning TEXT, local_image_url TEXT, is_streaming INTEGER NOT NULL DEFAULT 0, " +
                    "timestamp INTEGER NOT NULL)"
            )
            val cursor = db.rawQuery(
                "SELECT id, role, content, reasoning, local_image_url, is_streaming, timestamp " +
                    "FROM messages ORDER BY timestamp ASC",
                null
            )
            cursor.use {
                val idIdx = it.getColumnIndexOrThrow("id")
                val roleIdx = it.getColumnIndexOrThrow("role")
                val contentIdx = it.getColumnIndexOrThrow("content")
                val reasoningIdx = it.getColumnIndexOrThrow("reasoning")
                val imageIdx = it.getColumnIndexOrThrow("local_image_url")
                val streamingIdx = it.getColumnIndexOrThrow("is_streaming")
                val tsIdx = it.getColumnIndexOrThrow("timestamp")
                while (it.moveToNext()) {
                    messages.add(
                        Message(
                            id = it.getString(idIdx),
                            role = it.getString(roleIdx),
                            content = it.getString(contentIdx),
                            reasoning = it.getString(reasoningIdx)?.takeIf { r -> !r.isBlank() && r != "null" },
                            localImageUrl = it.getString(imageIdx)?.takeIf { v -> !v.isBlank() && v != "null" },
                            isStreaming = it.getInt(streamingIdx) == 1,
                            timestamp = it.getLong(tsIdx)
                        )
                    )
                }
            }
        } catch (_: SQLiteException) {
            return meta
        } finally {
            db.close()
        }
        return meta?.copy(messages = messages)
    }

    fun saveConversation(conversation: Conversation) {
        // Index row
        val index = SQLiteDatabase.openOrCreateDatabase(indexDb, null)
        try {
            index.execSQL(
                "CREATE TABLE IF NOT EXISTS conversations (" +
                    "id TEXT PRIMARY KEY, title TEXT, last_updated INTEGER NOT NULL)"
            )
            index.execSQL(
                "INSERT OR REPLACE INTO conversations (id, title, last_updated) VALUES (?, ?, ?)",
                arrayOf<Any?>(conversation.id, conversation.title, conversation.lastUpdated)
            )
        } finally {
            index.close()
        }

        // Messages in per-chat db
        val db = SQLiteDatabase.openOrCreateDatabase(chatDbFile(conversation.id), null)
        try {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS messages (" +
                    "id TEXT PRIMARY KEY, role TEXT NOT NULL, content TEXT NOT NULL, " +
                    "reasoning TEXT, local_image_url TEXT, is_streaming INTEGER NOT NULL DEFAULT 0, " +
                    "timestamp INTEGER NOT NULL)"
            )
            db.execSQL("DELETE FROM messages")
            db.beginTransaction()
            try {
                conversation.messages.forEach { msg ->
                    db.execSQL(
                        "INSERT INTO messages (id, role, content, reasoning, local_image_url, is_streaming, timestamp) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?)",
                        arrayOf<Any?>(
                            msg.id,
                            msg.role,
                            msg.content,
                            msg.reasoning,
                            msg.localImageUrl,
                            if (msg.isStreaming) 1 else 0,
                            msg.timestamp
                        )
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            db.close()
        }
    }

    fun deleteConversation(id: String) {
        val index = SQLiteDatabase.openOrCreateDatabase(indexDb, null)
        try {
            index.execSQL("DELETE FROM conversations WHERE id = ?", arrayOf(id))
        } finally {
            index.close()
        }
        SQLiteDatabase.deleteDatabase(chatDbFile(id))
    }

    private fun chatDbFile(id: String): File = File(chatsDir, "$id.db")

    private fun migrateLegacyJson() {
        val prefs = context.getSharedPreferences("ai_chat_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("conversations_list", null) ?: return
        // If index already has rows, mark migration done without importing duplicates
        if (!listConversations().isEmpty()) {
            prefs.edit().remove("conversations_list").remove("conversations_migrated").apply()
            return
        }
        val type = object : TypeToken<List<Conversation>>() {}.type
        val old = try {
            gson.fromJson<List<Conversation>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        old.forEach { conv ->
            saveConversation(conv.copy(messages = conv.messages.map { it.copy(isStreaming = false) }))
        }
        prefs.edit().remove("conversations_list").apply()
    }
}