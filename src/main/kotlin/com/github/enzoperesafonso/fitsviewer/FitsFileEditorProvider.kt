package com.github.enzoperesafonso.fitsviewer

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class FitsFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        FileTypeRegistry.getInstance().isFileOfType(file, FitsFileType.INSTANCE)

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = FitsFileEditor(project, file)

    override fun getEditorTypeId(): String = "fits-editor"

    // Replaces the default (text) editor for FITS files
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
