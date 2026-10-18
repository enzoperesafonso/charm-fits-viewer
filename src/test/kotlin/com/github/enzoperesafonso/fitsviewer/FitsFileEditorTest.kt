package com.github.enzoperesafonso.fitsviewer

import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import nom.tam.fits.Fits
import nom.tam.fits.NullDataHDU
import java.io.File

class FitsFileEditorTest : BasePlatformTestCase() {

    private fun writeFits(file: File, width: Int, height: Int, planes: Int) {
        val cube = Array(planes) { Array(height) { y -> FloatArray(width) { x -> (x + y).toFloat() } } }
        Fits().use { fits ->
            fits.addHDU(NullDataHDU())
            fits.addHDU(Fits.makeHDU(cube))
            fits.write(file)
        }
    }

    fun testFileTypeIsRegistered() {
        for (name in listOf("a.fits", "b.FIT", "c.fts", "d.fz")) {
            assertSame(name, FitsFileType.INSTANCE, FileTypeRegistry.getInstance().getFileTypeByFileName(name))
        }
    }

    fun testLoadsInBackgroundReloadsOnChangeAndReleasesOnDispose() {
        val io = File(FileUtil.createTempDirectory("fits", null), "cube.fits")
        writeFits(io, width = 50, height = 40, planes = 3)
        val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(io)!!

        val provider = FitsFileEditorProvider()
        assertTrue(provider.accept(project, file))
        val editor = provider.createEditor(project, file) as FitsFileEditor
        try {
            PlatformTestUtil.waitWithEventsDispatching("Image was not loaded", { editor.displayedImage != null }, 30)
            assertEquals(50, editor.displayedImage!!.width)
            assertEquals(40, editor.displayedImage!!.height)

            writeFits(io, width = 20, height = 10, planes = 1)
            file.refresh(false, false)
            PlatformTestUtil.waitWithEventsDispatching("Image was not reloaded", { editor.displayedImage?.width == 20 }, 30)
        } finally {
            Disposer.dispose(editor)
        }
        assertNull(editor.displayedImage)
    }
}
