package com.github.enzoperesafonso.fitsviewer

import com.intellij.ide.util.PropertiesComponent

internal enum class LevelPreset(val label: String, val tooltip: String) {
    ZSCALE("ZScale", "IRAF/astropy ZScale: brings out faint structure around the sky level"),
    P99_5("99.5%", "Clip the faintest and brightest 0.25% of pixels"),
    MIN_MAX("Min/Max", "Full data range"),
}

/** Display settings, remembered across files and IDE restarts. */
internal object ViewerSettings {
    private const val PREFIX = "fitsViewer."
    private val properties get() = PropertiesComponent.getInstance()

    var scaleMode: ScaleMode
        get() = enumValue("scale", ScaleMode.LINEAR)
        set(value) = properties.setValue(PREFIX + "scale", value.name)

    var colorMap: ColorMap
        get() = enumValue("colorMap", ColorMap.GRAYSCALE)
        set(value) = properties.setValue(PREFIX + "colorMap", value.name)

    var levelPreset: LevelPreset
        get() = enumValue("levels", LevelPreset.ZSCALE)
        set(value) = properties.setValue(PREFIX + "levels", value.name)

    var inverted: Boolean
        get() = properties.getBoolean(PREFIX + "inverted", false)
        set(value) = properties.setValue(PREFIX + "inverted", value, false)

    var showCrosshair: Boolean
        get() = properties.getBoolean(PREFIX + "crosshair", false)
        set(value) = properties.setValue(PREFIX + "crosshair", value, false)

    var showMagnifier: Boolean
        get() = properties.getBoolean(PREFIX + "magnifier", true)
        set(value) = properties.setValue(PREFIX + "magnifier", value, true)

    private inline fun <reified T : Enum<T>> enumValue(key: String, default: T): T =
        properties.getValue(PREFIX + key)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default
}
