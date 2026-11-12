package io.github.zyrouge.symphony.utils

import java.util.concurrent.ConcurrentHashMap

sealed class SimpleFileSystem(val parent: Folder?, val name: String) {
    val fullPath
        get(): SimplePath {
            val parts = mutableListOf(name)
            var currentParent = parent
            while (currentParent != null) {
                parts.add(0, currentParent.name)
                currentParent = currentParent.parent
            }
            return SimplePath(parts)
        }

    class File(
        parent: Folder? = null,
        name: String,
        var data: Any? = null,
    ) : SimpleFileSystem(parent, name)

    class Folder(
        parent: Folder? = null,
        name: String = "root",
        var children: ConcurrentHashMap<String, SimpleFileSystem> = ConcurrentHashMap(),
    ) : SimpleFileSystem(parent, name) {
        val isEmpty get() = children.isEmpty()
        val childFoldersCount get() = children.values.count { it is Folder }

        fun addChildFolder(name: String): Folder {
            if (children.containsKey(name)) {
                throw Exception("Child '$name' already exists")
            }
            val child = Folder(this, name)
            children[name] = child
            return child
        }

        fun addChildFile(name: String): File {
            if (children.containsKey(name)) {
                throw Exception("Child '$name' already exists")
            }
            val child = File(this, name)
            children[name] = child
            return child
        }

        fun addChildFile(path: SimplePath): File {
            val parts = path.parts.toMutableList()
            var parent = this
            while (parts.size > 1) {
                val x = parts.removeAt(0)
                val found = parent.children[x]
                parent = when (found) {
                    is Folder -> found
                    null -> parent.addChildFolder(x)
                    else -> throw Exception("Child '$x' is not a folder")
                }
            }
            return parent.addChildFile(parts[0])
        }

        /**
         * Adds the file at [path], creating folders on the way, or returns null if it is already there.
         * Unlike [addChildFile] it never throws for an existing file or folder, and it is safe when parallel
         * scans add files to the same new folder at the same time.
         */
        fun addChildFileIfAbsent(path: SimplePath): File? {
            var parent = this
            for (name in path.parts.dropLast(1)) {
                val node = parent.children[name]
                    ?: Folder(parent, name).let { created -> parent.children.putIfAbsent(name, created) ?: created }
                parent = node as? Folder ?: throw Exception("Child '$name' is not a folder")
            }
            val name = path.parts.last()
            val created = File(parent, name)
            return if (parent.children.putIfAbsent(name, created) == null) created else null
        }
    }
}
