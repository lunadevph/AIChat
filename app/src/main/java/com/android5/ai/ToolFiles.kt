package com.android5.ai

import android.content.Context
import java.io.File

/**
 * The workspace the assistant's file tools read and write, from the UI's side.
 *
 * The tools themselves live in [AiTools]; this is the read-only-ish view of the
 * same folder that the file history screen and the transcript's file links use.
 * Both go through the same containment check so a path shown in the UI can never
 * point outside the sandbox.
 */
object ToolFiles {

    /** Folder under the app's internal storage. Mirrors `AiTools.Scope`'s root. */
    const val DIR = "workspace"

    /** Preview limit, so a huge file cannot exhaust memory in a dialog. */
    const val MAX_PREVIEW_CHARS = 200_000

    /** One file in the workspace, with just what a list row needs. */
    data class Entry(
        val relPath: String,
        val name: String,
        val size: Long,
        val modified: Long
    )

    fun root(context: Context): File = File(context.filesDir, DIR)

    /** Every file below the workspace root, newest first. */
    fun list(context: Context): List<Entry> {
        val root = root(context)
        if (!root.exists()) return emptyList()

        return root.walkTopDown()
            .filter { it.isFile }
            .mapNotNull { file ->
                val relPath = relPath(root, file) ?: return@mapNotNull null
                Entry(
                    relPath = relPath,
                    name = file.name,
                    size = file.length(),
                    modified = file.lastModified()
                )
            }
            .sortedByDescending { it.modified }
            .toList()
    }

    /**
     * The file at [relPath], or null when it is missing or resolves outside the
     * workspace.
     */
    fun resolve(context: Context, relPath: String): File? {
        val cleaned = relPath.trim().replace('\\', '/').trimStart('/')
        if (cleaned.isEmpty()) return null
        val rootPath = root(context).canonicalFile.path
        val target = File(root(context), cleaned).canonicalFile
        val targetPath = target.path
        return when {
            targetPath == rootPath -> null
            targetPath.startsWith(rootPath + File.separator) -> target
            else -> null
        }
    }

    /** File contents for the preview dialog, or null when it cannot be read. */
    fun read(context: Context, relPath: String): String? {
        val file = resolve(context, relPath) ?: return null
        if (!file.isFile) return null
        return try {
            val text = file.readText()
            if (text.length > MAX_PREVIEW_CHARS) {
                text.take(MAX_PREVIEW_CHARS)
            } else {
                text
            }
        } catch (_: Exception) {
            null
        }
    }

    fun delete(context: Context, relPath: String): Boolean {
        val file = resolve(context, relPath) ?: return false
        return try {
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        } catch (_: Exception) {
            false
        }
    }

    /** Wipes the workspace: every file and folder the assistant created. */
    fun clear(context: Context): Boolean {
        val root = root(context)
        if (!root.exists()) return true
        return try {
            root.listFiles()?.forEach { it.deleteRecursively() }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun relPath(root: File, file: File): String? {
        val rootPath = root.canonicalFile.path
        val path = file.canonicalFile.path
        return when {
            path.startsWith(rootPath + File.separator) ->
                path.removePrefix(rootPath + File.separator)
            else -> null
        }
    }
}
