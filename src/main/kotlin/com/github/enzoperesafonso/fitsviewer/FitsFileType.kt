package com.github.enzoperesafonso.fitsviewer

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

class FitsFileType private constructor() : FileType {
    companion object {
        @JvmField
        val INSTANCE = FitsFileType()
    }

    override fun getName(): String = "FITS"

    override fun getDescription(): String = "FITS image"

    override fun getDefaultExtension(): String = "fits"

    override fun getIcon(): Icon = AllIcons.FileTypes.Image

    override fun isBinary(): Boolean = true

    override fun isReadOnly(): Boolean = true

    override fun getCharset(file: VirtualFile, content: ByteArray): String? = null
}
