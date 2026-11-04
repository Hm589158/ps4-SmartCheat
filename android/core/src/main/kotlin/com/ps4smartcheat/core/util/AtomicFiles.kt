package com.ps4smartcheat.core.util

import java.io.File
import java.io.IOException

object AtomicFiles {
    /** Writes [text] to a temp file next to [file] and renames it over [file], so readers never see half a file. */
    fun writeText(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Could not replace ${file.name}")
        }
    }
}
