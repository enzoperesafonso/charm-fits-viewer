package com.github.enzoperesafonso.fitsviewer

import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.ItemEvent
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/** Everything computed off the EDT for one image plane. */
internal class LoadedImage(val image: FitsImage, val wcs: Wcs?, val stats: ImageStatistics, val histogram: Histogram)

/**
 * The "Image" tab: the scrollable image with a status bar, and display controls on the right.
 * Stretch, colour map and overlay settings are kept when switching HDUs or planes.
 */
internal class FitsImageView : JPanel(BorderLayout()) {
    private var scaleMode = ScaleMode.LINEAR
    private var colorMap = ColorMap.GRAYSCALE
    private var lut = ImageRenderer.buildLut(scaleMode, colorMap)

    private var imagePanel: FitsImagePanel? = null
    private var stats: ImageStatistics? = null
    private var black = 0f
    private var white = 1f

    /** Centres the image when it is smaller than the viewport. */
    private val viewHolder = JPanel(GridBagLayout()).apply { background = IMAGE_BACKGROUND }
    private val scrollPane = JBScrollPane(viewHolder).apply {
        border = JBUI.Borders.empty()
        // The magnifier is pinned to the visible area; blit scrolling would smear it
        viewport.scrollMode = JViewport.SIMPLE_SCROLL_MODE
    }
    private val statusLabel = JBLabel(" ").apply { foreground = JBColor.GRAY }
    private val infoLabel = JBLabel().apply { foreground = JBColor.GRAY }

    private val zoomLabel = JBLabel("", SwingConstants.CENTER)
    private val histogramPanel = HistogramPanel { b, w ->
        black = b
        white = w
        imagePanel?.render(black, white, lut)
    }
    private val crosshairBox = JCheckBox("Crosshair", true)
    private val magnifierBox = JCheckBox("Magnifier", true)
    private val measureBox = JCheckBox("Measure", false)
    private val minLabel = statLabel()
    private val maxLabel = statLabel()
    private val meanLabel = statLabel()
    private val stdDevLabel = statLabel()

    val displayedImage: FitsImage? get() = imagePanel?.image

    /** The component that should get keyboard focus, if an image is shown. */
    val focusTarget: JComponent? get() = imagePanel

    /** Controls that only make sense while an image is shown. */
    private val imageControls = mutableListOf<JComponent>()

    init {
        val statusBar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            add(statusLabel, BorderLayout.CENTER)
            add(infoLabel, BorderLayout.EAST)
        }
        val imageArea = JPanel(BorderLayout()).apply {
            add(scrollPane, BorderLayout.CENTER)
            add(statusBar, BorderLayout.SOUTH)
        }
        val settings = JBScrollPane(createSettingsPanel()).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        add(JBSplitter(false, 0.75f).apply {
            firstComponent = imageArea
            secondComponent = settings
        }, BorderLayout.CENTER)

        // Fit a newly shown image once the viewport has a real size
        scrollPane.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                imagePanel?.takeIf { !it.isFitted }?.fitToViewport()
            }
        })
        showMessage("")
    }

    private fun createSettingsPanel(): JComponent {
        fun button(text: String, action: () -> Unit) = JButton(text).apply {
            addActionListener { action() }
            imageControls += this
        }

        val zoomPanel = JPanel(BorderLayout(5, 0)).apply {
            add(button("−") { imagePanel?.zoomBy(1 / ZOOM_STEP) }, BorderLayout.WEST)
            add(zoomLabel, BorderLayout.CENTER)
            add(button("+") { imagePanel?.zoomBy(ZOOM_STEP) }, BorderLayout.EAST)
        }
        val fitPanel = JPanel(GridLayout(1, 2, 5, 0)).apply {
            add(button("Fit") { imagePanel?.fitToViewport() })
            add(button("1:1") { imagePanel?.setEffectiveZoom(1.0) })
        }

        val scaleCombo = ComboBox(ScaleMode.entries.toTypedArray()).apply {
            selectedItem = scaleMode
            addItemListener {
                if (it.stateChange == ItemEvent.SELECTED) {
                    scaleMode = it.item as ScaleMode
                    rebuildLut()
                }
            }
        }
        val colorCombo = ComboBox(ColorMap.entries.toTypedArray()).apply {
            selectedItem = colorMap
            addItemListener {
                if (it.stateChange == ItemEvent.SELECTED) {
                    colorMap = it.item as ColorMap
                    rebuildLut()
                }
            }
        }
        imageControls += listOf(scaleCombo, colorCombo, histogramPanel, crosshairBox, magnifierBox, measureBox)

        val presets = JPanel(GridLayout(1, 2, 5, 0)).apply {
            add(button("Auto (2–98%)") { stats?.let { setLevels(it.percentile(2.0), it.percentile(98.0)) } })
            add(button("Min / Max") { stats?.let { setLevels(it.min, it.max) } })
        }
        val levels = JPanel(BorderLayout(0, 5)).apply {
            add(histogramPanel, BorderLayout.CENTER)
            add(presets, BorderLayout.SOUTH)
        }

        crosshairBox.addActionListener { imagePanel?.showCrosshair = crosshairBox.isSelected }
        magnifierBox.addActionListener { imagePanel?.showMagnifier = magnifierBox.isSelected }
        measureBox.addActionListener { imagePanel?.measuring = measureBox.isSelected }

        val statsPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Min:", minLabel)
            .addLabeledComponent("Max:", maxLabel)
            .addLabeledComponent("Mean:", meanLabel)
            .addLabeledComponent("Std dev:", stdDevLabel)
            .panel.apply { border = JBUI.Borders.emptyLeft(10) }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Zoom:", zoomPanel)
            .addComponent(fitPanel)
            .addSeparator()
            .addLabeledComponent("Scale:", scaleCombo)
            .addLabeledComponent("Color map:", colorCombo)
            .addSeparator()
            .addComponent(JBLabel("Levels:"))
            .addComponent(levels)
            .addSeparator()
            .addComponent(crosshairBox)
            .addComponent(magnifierBox)
            .addComponent(measureBox)
            .addSeparator()
            .addComponent(JBLabel("Statistics:"))
            .addComponent(statsPanel)
            .addComponentFillVertically(JPanel(), 0)
            .panel.apply { border = JBUI.Borders.empty(10) }
    }

    /** Shows [loaded], replacing (and releasing) whatever was shown before. */
    fun showImage(loaded: LoadedImage) {
        val image = loaded.image
        val panel = FitsImagePanel(
            image,
            loaded.wcs,
            onStatus = { statusLabel.text = it ?: CONTROLS_HINT },
            onZoomChanged = ::updateZoomLabel,
        ).apply {
            showCrosshair = crosshairBox.isSelected
            showMagnifier = magnifierBox.isSelected
            measuring = measureBox.isSelected
        }
        imagePanel = panel
        stats = loaded.stats
        black = loaded.stats.percentile(2.0)
        white = loaded.stats.percentile(98.0)
        histogramPanel.setData(loaded.histogram, black, white)
        panel.render(black, white, lut)

        minLabel.text = format(loaded.stats.min.toDouble())
        maxLabel.text = format(loaded.stats.max.toDouble())
        meanLabel.text = format(loaded.stats.mean)
        stdDevLabel.text = format(loaded.stats.stdDev)
        infoLabel.text = if (image.step > 1) {
            "${image.originalWidth}×${image.originalHeight}, shown downsampled ${image.step}×"
        } else {
            "${image.width}×${image.height}"
        }

        setView(panel)
        statusLabel.text = CONTROLS_HINT
        imageControls.forEach { it.isEnabled = true }
        updateZoomLabel()
        // If the viewport already has its size no resize event will come, so fit now
        SwingUtilities.invokeLater { if (imagePanel === panel && !panel.isFitted) panel.fitToViewport() }
    }

    /** Shows a message instead of an image. Also drops the current image so it can be garbage collected. */
    fun showMessage(text: String) {
        imagePanel = null
        stats = null
        histogramPanel.setData(null, 0f, 1f)
        listOf(minLabel, maxLabel, meanLabel, stdDevLabel).forEach { it.text = "–" }
        statusLabel.text = " "
        infoLabel.text = ""
        zoomLabel.text = ""
        imageControls.forEach { it.isEnabled = false }
        setView(JBLabel(text).apply { foreground = JBColor.GRAY })
    }

    private fun setView(view: JComponent) {
        viewHolder.removeAll()
        viewHolder.add(view)
        viewHolder.revalidate()
        viewHolder.repaint()
    }

    private fun setLevels(black: Float, white: Float) {
        this.black = black
        this.white = white
        histogramPanel.setLevels(black, white)
        imagePanel?.render(black, white, lut)
    }

    private fun rebuildLut() {
        lut = ImageRenderer.buildLut(scaleMode, colorMap)
        imagePanel?.render(black, white, lut)
    }

    private fun updateZoomLabel() {
        zoomLabel.text = imagePanel?.let { "%.0f%%".format(it.effectiveZoom * 100) } ?: ""
    }

    private companion object {
        val CONTROLS_HINT = (if (SystemInfo.isMac) "⌘" else "Ctrl") +
            "+scroll or +/− to zoom · drag or arrow keys to pan · 0 fit · 1 actual size"

        fun statLabel() = JBLabel("–").apply { foreground = JBColor.GRAY }

        fun format(v: Double) = "%.6g".format(v)
    }
}
