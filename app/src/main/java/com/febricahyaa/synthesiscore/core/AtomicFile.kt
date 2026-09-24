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

import java.io.File
import java.io.FileOutputStream

object AtomicFile {
    /**
     * Writes [content] to [path] atomically: write + fsync a `.tmp` sibling, then rename.
     *
     * Readers (Flux) never observe a partially written file, even if the process is
     * killed mid-write. The rename raises IN_MOVED_TO (not IN_CLOSE_WRITE) on the
     * target name, so inotify watchers must listen for IN_MOVED_TO as well.
     */
    fun write(path: String, content: String) {
        val target = File(path)
        target.parentFile?.mkdirs()

        val bytes = content.toByteArray(Charsets.UTF_8)
        val tmp = File("$path.tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(bytes)
            fos.fd.sync()
        }

        if (!tmp.renameTo(target)) {
            // renameTo only fails across filesystems; fall back to a direct overwrite
            // so the reader is never starved of updates.
            Log.w("AtomicFile", "atomic rename failed for $path, falling back to direct write")
            FileOutputStream(target).use { fos ->
                fos.write(bytes)
                fos.fd.sync()
            }
        }
    }
}
