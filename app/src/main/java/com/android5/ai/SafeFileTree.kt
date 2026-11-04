package com.android5.ai

import java.io.File

/** Delete a directory entry without recursively following symbolic links. */
internal object SafeFileTree {
    fun delete(file: File, isSymbolicLink: (File) -> Boolean): Boolean {
        if (isSymbolicLink(file)) return file.delete()
        if (!file.isDirectory) return file.delete()
        val children = file.listFiles() ?: return false
        val deleted = children.map { delete(it, isSymbolicLink) }.all { it }
        return deleted && file.delete()
    }
}
