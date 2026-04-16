package org.example

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class FitsFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean {
        val ext = file.extension?.lowercase()
        return ext == "fits" || ext == "fit" || ext == "fz"
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return FitsFileEditor(project, file)
    }

    override fun getEditorTypeId(): String {
        return "fits-editor"
    }

    override fun getPolicy(): FileEditorPolicy {
        // Suppresses the default text editor for FITS files
        return FileEditorPolicy.HIDE_DEFAULT_EDITOR
    }
}
