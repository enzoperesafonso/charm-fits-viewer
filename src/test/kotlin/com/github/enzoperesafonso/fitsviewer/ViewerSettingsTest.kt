package com.github.enzoperesafonso.fitsviewer

import com.intellij.ide.util.PropertiesComponent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ViewerSettingsTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            val properties = PropertiesComponent.getInstance()
            listOf("scale", "colorMap", "levels", "inverted", "crosshair", "magnifier").forEach { properties.unsetValue("fitsViewer.$it") }
        } finally {
            super.tearDown()
        }
    }

    fun testDefaults() {
        assertEquals(ScaleMode.LINEAR, ViewerSettings.scaleMode)
        assertEquals(ColorMap.GRAYSCALE, ViewerSettings.colorMap)
        assertEquals(LevelPreset.ZSCALE, ViewerSettings.levelPreset)
        assertFalse(ViewerSettings.inverted)
        assertFalse(ViewerSettings.showCrosshair)
        assertTrue(ViewerSettings.showMagnifier)
    }

    fun testValuesAreStored() {
        ViewerSettings.scaleMode = ScaleMode.ASINH
        ViewerSettings.colorMap = ColorMap.INFERNO
        ViewerSettings.levelPreset = LevelPreset.MIN_MAX
        ViewerSettings.inverted = true
        ViewerSettings.showMagnifier = false

        assertEquals(ScaleMode.ASINH, ViewerSettings.scaleMode)
        assertEquals(ColorMap.INFERNO, ViewerSettings.colorMap)
        assertEquals(LevelPreset.MIN_MAX, ViewerSettings.levelPreset)
        assertTrue(ViewerSettings.inverted)
        assertFalse(ViewerSettings.showMagnifier)
    }

    fun testUnknownStoredValueFallsBackToDefault() {
        // e.g. a colour map removed in a later version
        PropertiesComponent.getInstance().setValue("fitsViewer.colorMap", "RAINBOW")
        assertEquals(ColorMap.GRAYSCALE, ViewerSettings.colorMap)
    }
}
