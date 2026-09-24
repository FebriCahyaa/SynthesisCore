/*
 * Copyright (C) 2026 FebriCahyaa
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.febricahyaa.synthesiscore.core

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

object AtomicFile {
    /**
     * Writes [content] to [path] atomically: write + fsync a `.tmp` sibling, then rename.
     *
     * - Readers (Flux) never observe a partially written file, even if the process is
     *   killed mid-write. The rename raises IN_MOVED_TO (not IN_CLOSE_WRITE) on the
     *   target name, so inotify watchers must listen for IN_MOVED_TO as well.
     * - The temp file is created with O_CREAT|O_EXCL|O_NOFOLLOW after removing any
     *   leftover entry, so a symlink planted at `<path>.tmp` can never redirect this
     *   root process into overwriting another file. rename(2) replaces a symlink at
     *   the target itself rather than following it.
     */
    fun write(path: String, content: String) {
        val target = Paths.get(path)
        target.parent?.let { Files.createDirectories(it) }

        val tmp = target.resolveSibling("${target.fileName}.tmp")
        Files.deleteIfExists(tmp) // does not follow symlinks
        writeNew(tmp, content.toByteArray(Charsets.UTF_8))

        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            // Only possible across filesystems; still replace the file so the reader
            // is never starved of updates.
            Log.w("AtomicFile", "atomic rename unsupported for $path, falling back to replace")
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun writeNew(file: Path, bytes: ByteArray) {
        FileChannel.open(
            file,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }
}
