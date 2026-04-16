package org.example

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

class FitsFileType private constructor() : FileType {
    companion object {
        @JvmField
        val INSTANCE = FitsFileType()
    }

    override fun getName(): @NlsSafe String = "FITS"

    override fun getDescription(): @NlsContexts.Label String = "FITS Image File"

    override fun getDefaultExtension(): @NlsSafe String = "fits"

    override fun getIcon(): Icon? = null

    override fun isBinary(): Boolean = true

    override fun isReadOnly(): Boolean = true

    override fun getCharset(file: VirtualFile, content: ByteArray): String? = null
}
