package com.github.enzoperesafonso.fitsviewer

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.ItemEvent
import java.util.Locale
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

/** Everything computed off the EDT for one image plane. */
internal class LoadedImage(val image: FitsImage, val wcs: Wcs?, val stats: ImageStatistics, val exportName: String)

/**
 * The "Image" tab: the scrollable image with a status bar, and levels and statistics on the right.
 * Its display controls live in [toolbar], which the editor shows in its top bar. Display settings
 * are kept when switching HDUs or planes and remembered across files and restarts ([ViewerSettings]).
 */
internal class FitsImageView(private val project: Project) : JPanel(BorderLayout()) {
    private var scaleMode = ViewerSettings.scaleMode
    private var colorMap = ViewerSettings.colorMap
    private var inverted = ViewerSettings.inverted
    private var lut = ImageRenderer.buildLut(scaleMode, colorMap, inverted)
    private var levelPreset = ViewerSettings.levelPreset
    private var showCrosshair = ViewerSettings.showCrosshair
    private var showMagnifier = ViewerSettings.showMagnifier
    private var measuring = false
    private var exportName = "image"

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
    private val zoomLabel = JBLabel().apply { border = JBUI.Borders.empty(0, 2, 0, 6) }
    private val histogramPanel = HistogramPanel { b, w ->
        black = b
        white = w
        imagePanel?.render(black, white, lut)
    }
    private val minLabel = statLabel()
    private val maxLabel = statLabel()
    private val meanLabel = statLabel()
    private val stdDevLabel = statLabel()
    private val medianLabel = statLabel()
    private val noiseLabel = statLabel()

    /** Controls that only make sense while an image is shown. */
    private val imageControls = mutableListOf<JComponent>()

    /** Display controls for the editor's top bar. */
    val toolbar: JComponent = createToolbar()

    val displayedImage: FitsImage? get() = imagePanel?.image

    /** The component that should get keyboard focus, if an image is shown. */
    val focusTarget: JComponent? get() = imagePanel

    init {
        val statusBar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), JBUI.Borders.empty(3, 8))
            add(statusLabel, BorderLayout.CENTER)
            add(infoLabel, BorderLayout.EAST)
        }
        val imageArea = JPanel(BorderLayout()).apply {
            add(scrollPane, BorderLayout.CENTER)
            add(statusBar, BorderLayout.SOUTH)
        }
        val side = JBScrollPane(createSidePanel()).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        add(JBSplitter(false, 0.75f).apply {
            firstComponent = imageArea
            secondComponent = side
        }, BorderLayout.CENTER)

        // Fit a newly shown image once the viewport has a real size
        scrollPane.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                imagePanel?.takeIf { !it.isFitted }?.fitToViewport()
            }
        })
        showMessage("")
    }

    private fun createToolbar(): JComponent {
        val scaleCombo = ComboBox(ScaleMode.entries.toTypedArray()).apply {
            selectedItem = scaleMode
            toolTipText = "Stretch"
            addItemListener {
                if (it.stateChange == ItemEvent.SELECTED) {
                    scaleMode = it.item as ScaleMode
                    ViewerSettings.scaleMode = scaleMode
                    rebuildLut()
                }
            }
        }
        val colorCombo = ComboBox(ColorMap.entries.toTypedArray()).apply {
            selectedItem = colorMap
            toolTipText = "Colour map"
            addItemListener {
                if (it.stateChange == ItemEvent.SELECTED) {
                    colorMap = it.item as ColorMap
                    ViewerSettings.colorMap = colorMap
                    rebuildLut()
                }
            }
        }
        imageControls += listOf(scaleCombo, colorCombo)
        val invertAction = actionToolbar(
            "FitsViewer.ColorMap",
            overlayToggle("Invert Colour Map", INVERT_ICON, { inverted }) {
                inverted = it
                ViewerSettings.inverted = it
                rebuildLut()
            },
        )

        val zoomActions = actionToolbar(
            "FitsViewer.Zoom",
            viewAction("Zoom Out (−)", AllIcons.General.ZoomOut) { it.zoomBy(1 / ZOOM_STEP) },
            viewAction("Zoom In (+)", AllIcons.General.ZoomIn) { it.zoomBy(ZOOM_STEP) },
            viewAction("Actual Size (1)", AllIcons.General.ActualZoom) { it.setEffectiveZoom(1.0) },
            viewAction("Fit to Window (0)", AllIcons.General.FitContent) { it.fitToViewport() },
        )
        val overlayActions = actionToolbar(
            "FitsViewer.Overlays",
            overlayToggle("Crosshair at Image Centre", AllIcons.General.Locate, { showCrosshair }) {
                showCrosshair = it
                ViewerSettings.showCrosshair = it
                imagePanel?.showCrosshair = it
            },
            overlayToggle("Magnifier", AllIcons.Actions.Find, { showMagnifier }) {
                showMagnifier = it
                ViewerSettings.showMagnifier = it
                imagePanel?.showMagnifier = it
            },
            overlayToggle("Measure Distance (drag on the image)", MEASURE_ICON, { measuring }) {
                measuring = it
                imagePanel?.measuring = it
            },
            Separator.getInstance(),
            viewAction("Export as PNG…", AllIcons.ToolbarDecorator.Export) { exportPng(it) },
        )

        return JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineLeft(JBColor.border()), JBUI.Borders.emptyLeft(8))
            add(scaleCombo)
            add(colorCombo)
            add(invertAction)
            add(zoomActions)
            add(zoomLabel)
            add(overlayActions)
        }
    }

    private fun createSidePanel(): JComponent {
        val presets = JPanel(GridLayout(1, LevelPreset.entries.size, JBUI.scale(4), 0))
        for (preset in LevelPreset.entries) {
            presets.add(JButton(preset.label).apply {
                toolTipText = preset.tooltip
                addActionListener {
                    levelPreset = preset
                    ViewerSettings.levelPreset = preset
                    applyLevelPreset()
                }
                imageControls += this
            })
        }
        imageControls += histogramPanel
        val levels = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            add(histogramPanel, BorderLayout.CENTER)
            add(presets, BorderLayout.SOUTH)
        }

        val statsPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Min:", minLabel)
            .addLabeledComponent("Max:", maxLabel)
            .addLabeledComponent("Mean:", meanLabel)
            .addLabeledComponent("Median:", medianLabel)
            .addLabeledComponent("Std dev:", stdDevLabel)
            .addLabeledComponent(
                JBLabel("Noise σ:").apply { toolTipText = NOISE_TOOLTIP },
                noiseLabel.apply { toolTipText = NOISE_TOOLTIP },
            )
            .panel

        return FormBuilder.createFormBuilder()
            .addComponent(JBLabel("Levels"))
            .addComponent(levels)
            .addSeparator(JBUI.scale(12))
            .addComponent(JBLabel("Statistics"))
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
            showCrosshair = this@FitsImageView.showCrosshair
            showMagnifier = this@FitsImageView.showMagnifier
            measuring = this@FitsImageView.measuring
        }
        imagePanel = panel
        stats = loaded.stats
        applyLevelPreset()

        minLabel.text = format(loaded.stats.min.toDouble())
        maxLabel.text = format(loaded.stats.max.toDouble())
        meanLabel.text = format(loaded.stats.mean)
        stdDevLabel.text = format(loaded.stats.stdDev)
        medianLabel.text = format(loaded.stats.median.toDouble())
        noiseLabel.text = format(loaded.stats.robustSigma)
        exportName = loaded.exportName
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
        listOf(minLabel, maxLabel, meanLabel, medianLabel, stdDevLabel, noiseLabel).forEach { it.text = "–" }
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

    private fun applyLevelPreset() {
        val s = stats ?: return
        val (b, w) = when (levelPreset) {
            LevelPreset.ZSCALE -> s.zscale
            LevelPreset.P99_5 -> s.percentile(0.25) to s.percentile(99.75)
            LevelPreset.MIN_MAX -> s.min to s.max
        }
        black = b
        white = w
        // Show the histogram around the chosen levels (half their span either side), so the handles
        // aren't squeezed into one edge by bright outliers
        val margin = (w - b) * 0.5f
        val lo = if (levelPreset == LevelPreset.MIN_MAX) s.min else maxOf(s.min, b - margin)
        val hi = if (levelPreset == LevelPreset.MIN_MAX) s.max else minOf(s.max, w + margin)
        histogramPanel.setData(s.histogram(lo, hi), b, w)
        imagePanel?.render(b, w, lut)
    }

    private fun rebuildLut() {
        lut = ImageRenderer.buildLut(scaleMode, colorMap, inverted)
        imagePanel?.render(black, white, lut)
    }

    /** Saves the image as currently stretched and coloured, at its displayed resolution, without overlays. */
    private fun exportPng(panel: FitsImagePanel) {
        // Deprecated (not scheduled for removal) in 2025+ in favour of a single-extension constructor that
        // 2024.3 doesn't have; switch once pluginSinceBuild is raised past 243.
        val descriptor = FileSaverDescriptor(
            "Export Image as PNG",
            "Saves the image with the current stretch and colour map, without overlays",
            "png",
        )
        val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save("$exportName.png")?.file ?: return
        var error: Exception? = null
        // Modal, so the image can't be re-rendered while it is being written
        ProgressManager.getInstance().run(object : Task.Modal(project, "Exporting PNG", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    ImageIO.write(panel.renderedImage, "png", target)
                } catch (e: Exception) {
                    error = e
                }
            }
        })
        error?.let { Messages.showErrorDialog(project, "Could not save ${target.name}: ${it.message}", "Export Failed") }
            ?: run { statusLabel.text = "Exported ${target.name}" }
    }

    private fun updateZoomLabel() {
        zoomLabel.text = imagePanel?.let { "%.0f%%".format(Locale.ROOT, it.effectiveZoom * 100) } ?: ""
    }

    private fun actionToolbar(place: String, vararg actions: AnAction): JComponent =
        ActionManager.getInstance().createActionToolbar(place, DefaultActionGroup(*actions), true).run {
            targetComponent = this@FitsImageView
            component.apply {
                isOpaque = false
                border = JBUI.Borders.empty()
            }
        }

    private fun viewAction(text: String, icon: Icon, run: (FitsImagePanel) -> Unit) = object : DumbAwareAction(text, null, icon) {
        override fun actionPerformed(e: AnActionEvent) {
            imagePanel?.let(run)
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = imagePanel != null
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private fun overlayToggle(text: String, icon: Icon, get: () -> Boolean, set: (Boolean) -> Unit) =
        object : DumbAwareToggleAction(text, null, icon) {
            override fun isSelected(e: AnActionEvent) = get()

            override fun setSelected(e: AnActionEvent, state: Boolean) = set(state)

            override fun update(e: AnActionEvent) {
                super.update(e)
                e.presentation.isEnabled = imagePanel != null
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    private companion object {
        val MEASURE_ICON: Icon = IconLoader.getIcon("/icons/measure.svg", FitsImageView::class.java)
        val INVERT_ICON: Icon = IconLoader.getIcon("/icons/invert.svg", FitsImageView::class.java)

        const val NOISE_TOOLTIP = "Robust standard deviation, 1.4826 × median absolute deviation. Unlike Std dev, sources barely affect it."

        val CONTROLS_HINT = (if (SystemInfo.isMac) "⌘" else "Ctrl") +
            "+scroll or +/− to zoom · drag or arrow keys to pan · 0 fit · 1 actual size"

        fun statLabel() = JBLabel("–").apply { foreground = JBColor.GRAY }

        fun format(v: Double) = "%.6g".format(Locale.ROOT, v)
    }
}
