package org.example

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.ui.JBSplitter
import com.intellij.util.ui.FormBuilder
import nom.tam.fits.Fits
import nom.tam.fits.FitsException
import nom.tam.fits.Header
import nom.tam.fits.ImageHDU
import nom.tam.image.compression.hdu.CompressedImageHDU
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.beans.PropertyChangeListener
import java.io.IOException
import javax.swing.*
import javax.swing.table.DefaultTableModel
import kotlin.math.*

enum class ScaleMode {
    LINEAR, LOG, SQRT, ASINH
}

enum class ColorMap(val displayName: String) {
    GRAYSCALE("Grayscale"),
    HEAT("Heat"),
    VIRIDIS("Viridis"),
    MAGMA("Magma")
}

data class WcsInfo(
    val crval: DoubleArray,
    val crpix: DoubleArray,
    val cd: Array<DoubleArray>?,
    val cdelt: DoubleArray?,
    val pc: Array<DoubleArray>?,
    val ctype: Array<String>
)

data class ImageStatistics(
    val min: Float,
    val max: Float,
    val mean: Float,
    val median: Float,
    val stdDev: Float,
    val sum: Double,
    val count: Int,
    val p2: Float,
    val p98: Float
)

data class HistogramData(
    val bins: IntArray,
    val binMin: Float,
    val binMax: Float,
    val binWidth: Float
)

class FitsFileEditor(private val project: Project, private val virtualFile: VirtualFile) : UserDataHolderBase(), FileEditor {

    private val myComponent: JComponent
    private var fits: Fits? = null
    private val hdus = mutableListOf<nom.tam.fits.BasicHDU<*>>()
    private var currentHduIndex = 0
    private val hduNames = mutableListOf<String>()
    
    private val tableModel = DefaultTableModel(arrayOf("Keyword", "Value", "Comment"), 0)
    private val imagePanel = JPanel(BorderLayout())
    private val tabbedPane = JBTabbedPane()

    companion object {
        private val LOG = Logger.getInstance(FitsFileEditor::class.java)
    }

    init {
        // Header Tab
        val headerPanel = JPanel(BorderLayout())
        val table = JBTable(tableModel)
        headerPanel.add(JBScrollPane(table), BorderLayout.CENTER)
        tabbedPane.addTab("Header", headerPanel)

        // Image Tab (initially with a loading indicator)
        imagePanel.add(JLabel("Loading FITS data...", JLabel.CENTER), BorderLayout.CENTER)
        tabbedPane.addTab("Image", imagePanel)

        myComponent = tabbedPane
        
        loadFitsDataInBackground()
    }

    private fun loadFitsDataInBackground() {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading FITS File", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Reading ${virtualFile.name}"

                try {
                    val localFits = Fits(virtualFile.inputStream)
                    fits = localFits
                    
                    hdus.clear()
                    hduNames.clear()
                    
                    var h: nom.tam.fits.BasicHDU<*>?
                    while (localFits.readHDU().also { h = it } != null) {
                        val currentHdu = h!!
                        val index = hdus.size
                        hdus.add(currentHdu)
                        
                        val name = currentHdu.header.getStringValue("EXTNAME") ?: "HDU $index"
                        val type = when (currentHdu) {
                            is ImageHDU -> "Image"
                            is CompressedImageHDU -> "Compressed Image"
                            else -> "Table/Unknown"
                        }
                        hduNames.add("[$index] $name ($type)")
                    }

                    // Start with the first HDU that has image data (usually 0 or 1)
                    var initialIndex = 0
                    for (i in hdus.indices) {
                        val currentHdu = hdus[i]
                        if (currentHdu.header.getIntValue("NAXIS") > 0 && (currentHdu is ImageHDU || currentHdu is CompressedImageHDU)) {
                            initialIndex = i
                            break
                        }
                    }
                    
                    switchToHdu(initialIndex)

                } catch (e: Exception) {
                    LOG.warn("Failed to read FITS file ${virtualFile.name}", e)
                    ApplicationManager.getApplication().invokeLater {
                        imagePanel.removeAll()
                        imagePanel.add(JLabel("Error reading file: ${e.message}", JLabel.CENTER), BorderLayout.CENTER)
                        imagePanel.revalidate()
                        imagePanel.repaint()
                    }
                }
            }
        })
    }

    private fun switchToHdu(index: Int) {
        if (index < 0 || index >= hdus.size) return
        currentHduIndex = index
        
        val hdu = hdus[index]

        // Process Header
        val header = hdu.header
        val headerData = mutableListOf<Array<String>>()
        header.iterator().forEach { card ->
            headerData.add(arrayOf(card.key ?: "", card.value ?: "", card.comment ?: ""))
        }

        var imageData: Triple<FloatArray, Int, Int>? = null
        var stats: ImageStatistics? = null
        var wcs: WcsInfo? = null
        var histogram: HistogramData? = null
        
        val imageHdu = when (hdu) {
            is ImageHDU -> hdu
            is CompressedImageHDU -> hdu.asImageHDU()
            else -> null
        }

        if (imageHdu != null) {
            try {
                val data = imageHdu.data.data
                if (data is Array<*>) {
                    imageData = flattenData(data)
                    if (imageData != null) {
                        stats = calculateStatistics(imageData.first)
                        wcs = extractWcs(header)
                        histogram = calculateHistogram(imageData.first, stats.p2, stats.p98)
                    }
                }
            } catch (e: Exception) {
                LOG.warn("Error extracting data from HDU $index", e)
            }
        }

        // Update UI on the Event Dispatch Thread
        ApplicationManager.getApplication().invokeLater {
            tableModel.rowCount = 0
            headerData.forEach { row -> tableModel.addRow(row) }
            
            buildImageView(imageData, stats, wcs, histogram)
        }
    }

    private fun buildImageView(
        flatData: Triple<FloatArray, Int, Int>?,
        stats: ImageStatistics?,
        wcs: WcsInfo?,
        histogram: HistogramData?
    ) {
        imagePanel.removeAll()

        val statusBar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            background = JBColor.PanelBackground
        }
        val statusLabel = JBLabel("Ready").apply {
            foreground = JBColor.GRAY
        }
        statusBar.add(statusLabel, BorderLayout.WEST)

        var fitsImagePanel: FitsImagePanel? = null
        var histogramPanel: HistogramPanel? = null

        val imageArea = JPanel(BorderLayout())
        
        if (flatData != null && stats != null && histogram != null) {
            val panel = FitsImagePanel(flatData.first, flatData.second, flatData.third, wcs) { status ->
                statusLabel.text = status
            }
            fitsImagePanel = panel
            
            histogramPanel = HistogramPanel(histogram) { b, w ->
                panel.setBlackWhitePoints(b, w)
            }

            panel.setBlackWhitePoints(stats.p2, stats.p98)
            histogramPanel.setPoints(stats.p2, stats.p98)

            val centeringPanel = JPanel(GridBagLayout()).apply {
                background = panel.background
                add(panel)
            }
            
            val scrollPane = JBScrollPane(centeringPanel).apply {
                border = JBUI.Borders.empty()
            }
            imageArea.add(scrollPane, BorderLayout.CENTER)
            
            panel.addComponentListener(object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent?) {
                    if (!panel.isInitialized) {
                        panel.autoFit()
                        panel.isInitialized = true
                    }
                }
            })
        } else {
            imageArea.add(JLabel("No supported image data found in this HDU.", JLabel.CENTER), BorderLayout.CENTER)
        }

        imageArea.add(statusBar, BorderLayout.SOUTH)

        val settingsPanel = createSettingsPanel(fitsImagePanel, histogramPanel, stats)
        val settingsScrollPane = JBScrollPane(settingsPanel).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }

        val splitter = JBSplitter(false, 0.75f).apply {
            firstComponent = imageArea
            secondComponent = settingsScrollPane
        }

        imagePanel.add(splitter, BorderLayout.CENTER)
        imagePanel.revalidate()
        imagePanel.repaint()
    }

    private fun createSettingsPanel(fitsImagePanel: FitsImagePanel?, histogramPanel: HistogramPanel?, stats: ImageStatistics?): JPanel {
        val hduCombo = ComboBox(hduNames.toTypedArray()).apply {
            selectedIndex = currentHduIndex
            addActionListener {
                if (selectedIndex != currentHduIndex) {
                    switchToHdu(selectedIndex)
                }
            }
        }

        val formBuilder = FormBuilder.createFormBuilder()
            .addLabeledComponent("Select HDU:", hduCombo)
            .addSeparator()

        if (fitsImagePanel != null && histogramPanel != null && stats != null) {
            val zoomLabel = JBLabel("${(fitsImagePanel.zoomFactor * 100).toInt()}%").apply {
                horizontalAlignment = SwingConstants.CENTER
            }
            
            val zoomInBtn = JButton("+").apply {
                addActionListener {
                    fitsImagePanel.zoomIn()
                    zoomLabel.text = "${(fitsImagePanel.zoomFactor * 100).toInt()}%"
                }
            }
            val zoomOutBtn = JButton("-").apply {
                addActionListener {
                    fitsImagePanel.zoomOut()
                    zoomLabel.text = "${(fitsImagePanel.zoomFactor * 100).toInt()}%"
                }
            }
            val fitBtn = JButton("Fit").apply {
                addActionListener {
                    fitsImagePanel.autoFit()
                    zoomLabel.text = "${(fitsImagePanel.zoomFactor * 100).toInt()}%"
                }
            }

            val zoomPanel = JPanel(BorderLayout(5, 0)).apply {
                add(zoomOutBtn, BorderLayout.WEST)
                add(zoomLabel, BorderLayout.CENTER)
                add(zoomInBtn, BorderLayout.EAST)
            }

            val scaleCombo = ComboBox(ScaleMode.values())
            scaleCombo.addActionListener { fitsImagePanel.setScaleMode(scaleCombo.selectedItem as ScaleMode) }

            val colorCombo = ComboBox(ColorMap.values())
            colorCombo.renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
                    return super.getListCellRendererComponent(list, (value as? ColorMap)?.displayName ?: value, index, isSelected, cellHasFocus)
                }
            }
            colorCombo.addActionListener { fitsImagePanel.setColorMap(colorCombo.selectedItem as ColorMap) }

            val crosshairBtn = JCheckBox("Crosshair", fitsImagePanel.showCrosshair)
            crosshairBtn.addActionListener {
                fitsImagePanel.showCrosshair = crosshairBtn.isSelected
                fitsImagePanel.repaint()
            }

            val magnifierBtn = JCheckBox("Magnifier", fitsImagePanel.showMagnifier)
            magnifierBtn.addActionListener {
                fitsImagePanel.showMagnifier = magnifierBtn.isSelected
                fitsImagePanel.repaint()
            }

            val measureBtn = JCheckBox("Measure", fitsImagePanel.isMeasuring)
            measureBtn.addActionListener {
                fitsImagePanel.isMeasuring = measureBtn.isSelected
                fitsImagePanel.repaint()
            }

            val autoStretchBtn = JButton("Auto Stretch")
            autoStretchBtn.addActionListener {
                histogramPanel.setPoints(stats.p2, stats.p98)
                fitsImagePanel.setBlackWhitePoints(stats.p2, stats.p98)
            }

            val stretchPanel = JPanel(BorderLayout(0, 5))
            stretchPanel.add(histogramPanel.apply { preferredSize = Dimension(250, 100) }, BorderLayout.CENTER)
            stretchPanel.add(autoStretchBtn, BorderLayout.SOUTH)

            val statsBuilder = FormBuilder.createFormBuilder()
                .addLabeledComponent("Min:", JBLabel(String.format("%.4f", stats.min)).apply { foreground = JBColor.GRAY })
                .addLabeledComponent("Max:", JBLabel(String.format("%.4f", stats.max)).apply { foreground = JBColor.GRAY })
                .addLabeledComponent("Mean:", JBLabel(String.format("%.4f", stats.mean)).apply { foreground = JBColor.GRAY })
                .addLabeledComponent("StdDev:", JBLabel(String.format("%.4f", stats.stdDev)).apply { foreground = JBColor.GRAY })

            formBuilder
                .addLabeledComponent("Zoom:", zoomPanel)
                .addComponent(fitBtn)
                .addSeparator()
                .addLabeledComponent("Scale Mode:", scaleCombo)
                .addLabeledComponent("Color Map:", colorCombo)
                .addSeparator()
                .addComponent(JBLabel("Levels:"))
                .addComponent(stretchPanel)
                .addSeparator()
                .addComponent(crosshairBtn)
                .addComponent(magnifierBtn)
                .addComponent(measureBtn)
                .addSeparator()
                .addComponent(JBLabel("Image Statistics:"))
                .addComponent(statsBuilder.panel.apply { border = JBUI.Borders.emptyLeft(10) })
        } else {
            formBuilder.addComponent(JBLabel("No image data available for this HDU.").apply { 
                foreground = JBColor.GRAY
                font = JBUI.Fonts.smallFont()
            })
        }

        return formBuilder
            .addComponentFillVertically(JPanel(), 0)
            .panel.apply { border = JBUI.Borders.empty(10) }
    }

    private fun calculateStatistics(data: FloatArray): ImageStatistics {
        val finiteData = data.filter { it.isFinite() }.toFloatArray()
        if (finiteData.isEmpty()) return ImageStatistics(0f, 0f, 0f, 0f, 0f, 0.0, 0, 0f, 0f)
        
        val min = finiteData.minOrNull() ?: 0f
        val max = finiteData.maxOrNull() ?: 0f
        val sum = finiteData.fold(0.0) { acc, f -> acc + f }
        val mean = (sum / finiteData.size).toFloat()
        
        var sumSqDiff = 0.0
        for (f in finiteData) {
            val diff = f - mean
            sumSqDiff += diff * diff
        }
        val stdDev = sqrt(sumSqDiff / finiteData.size).toFloat()
        
        finiteData.sort()
        val median = if (finiteData.size % 2 == 0) {
            (finiteData[finiteData.size / 2] + finiteData[finiteData.size / 2 - 1]) / 2f
        } else {
            finiteData[finiteData.size / 2]
        }
        
        val p2 = finiteData[(finiteData.size * 0.02).toInt()]
        val p98 = finiteData[(finiteData.size * 0.98).toInt()]
        
        return ImageStatistics(min, max, mean, median, stdDev, sum, finiteData.size, p2, p98)
    }

    private fun calculateHistogram(data: FloatArray, p2: Float, p98: Float, numBins: Int = 256): HistogramData {
        val finiteData = data.filter { it.isFinite() && it >= p2 && it <= p98 }.toFloatArray()
        if (finiteData.isEmpty()) return HistogramData(IntArray(numBins), p2, p98, (p98 - p2) / numBins)
        
        val binMin = p2
        val binMax = p98
        val binWidth = (binMax - binMin) / numBins
        val bins = IntArray(numBins)
        
        for (f in finiteData) {
            val binIdx = ((f - binMin) / binWidth).toInt().coerceIn(0, numBins - 1)
            bins[binIdx]++
        }
        return HistogramData(bins, binMin, binMax, binWidth)
    }

    private fun flattenData(data: Array<*>): Triple<FloatArray, Int, Int>? {
        if (data.isEmpty()) return null
        val height = data.size
        val firstRow = data[0] ?: return null
        val width = when (firstRow) {
            is FloatArray -> firstRow.size
            is DoubleArray -> firstRow.size
            is IntArray -> firstRow.size
            is ShortArray -> firstRow.size
            is ByteArray -> firstRow.size
            else -> return null
        }
        if (width == 0 || height == 0) return null

        val flat = FloatArray(width * height)
        var index = 0
        for (y in 0 until height) {
            val row = data[y]
            for (x in 0 until width) {
                flat[index++] = when (row) {
                    is FloatArray -> row[x]
                    is DoubleArray -> row[x].toFloat()
                    is IntArray -> row[x].toFloat()
                    is ShortArray -> row[x].toFloat()
                    is ByteArray -> row[x].toFloat()
                    else -> 0f
                }
            }
        }
        return Triple(flat, width, height)
    }

    private fun extractWcs(header: Header): WcsInfo? {
        val crval = doubleArrayOf(header.getDoubleValue("CRVAL1", 0.0), header.getDoubleValue("CRVAL2", 0.0))
        val crpix = doubleArrayOf(header.getDoubleValue("CRPIX1", 0.0), header.getDoubleValue("CRPIX2", 0.0))
        val ctype = arrayOf(header.getStringValue("CTYPE1") ?: "RA", header.getStringValue("CTYPE2") ?: "DEC")

        val cd = if (header.containsKey("CD1_1")) {
            arrayOf(
                doubleArrayOf(header.getDoubleValue("CD1_1", 0.0), header.getDoubleValue("CD1_2", 0.0)),
                doubleArrayOf(header.getDoubleValue("CD2_1", 0.0), header.getDoubleValue("CD2_2", 0.0))
            )
        } else null

        val cdelt = if (header.containsKey("CDELT1")) {
            doubleArrayOf(header.getDoubleValue("CDELT1", 1.0), header.getDoubleValue("CDELT2", 1.0))
        } else null

        val pc = if (header.containsKey("PC1_1")) {
            arrayOf(
                doubleArrayOf(header.getDoubleValue("PC1_1", 1.0), header.getDoubleValue("PC1_2", 0.0)),
                doubleArrayOf(header.getDoubleValue("PC2_1", 0.0), header.getDoubleValue("PC2_2", 1.0))
            )
        } else null

        return WcsInfo(crval, crpix, cd, cdelt, pc, ctype)
    }

    private inner class HistogramPanel(private val data: HistogramData, val onLevelsChanged: (Float, Float) -> Unit) : JPanel() {
        var blackPoint: Float = data.binMin
        var whitePoint: Float = data.binMax
        private var dragMode = 0 // 0: none, 1: black, 2: white

        init {
            preferredSize = Dimension(400, 80)
            background = JBColor.PanelBackground
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            
            val mouseAdapter = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    val w = width
                    val bPos = ((blackPoint - data.binMin) / (data.binMax - data.binMin) * w).toInt()
                    val wPos = ((whitePoint - data.binMin) / (data.binMax - data.binMin) * w).toInt()
                    
                    dragMode = if (abs(e.x - bPos) < 10) 1
                    else if (abs(e.x - wPos) < 10) 2
                    else 0
                }
                
                override fun mouseDragged(e: MouseEvent) {
                    if (dragMode == 0) return
                    val w = width
                    val newVal = data.binMin + (e.x.toFloat() / w) * (data.binMax - data.binMin)
                    if (dragMode == 1) {
                        blackPoint = newVal.coerceIn(data.binMin, whitePoint - 0.0001f)
                    } else {
                        whitePoint = newVal.coerceIn(blackPoint + 0.0001f, data.binMax)
                    }
                    onLevelsChanged(blackPoint, whitePoint)
                    repaint()
                }
                
                override fun mouseReleased(e: MouseEvent) {
                    dragMode = 0
                }
                
                override fun mouseMoved(e: MouseEvent) {
                    val w = width
                    val bPos = ((blackPoint - data.binMin) / (data.binMax - data.binMin) * w).toInt()
                    val wPos = ((whitePoint - data.binMin) / (data.binMax - data.binMin) * w).toInt()
                    
                    if (abs(e.x - bPos) < 10 || abs(e.x - wPos) < 10) {
                        cursor = Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR)
                    } else {
                        cursor = Cursor.getDefaultCursor()
                    }
                }
            }
            addMouseListener(mouseAdapter)
            addMouseMotionListener(mouseAdapter)
        }

        fun setPoints(black: Float, white: Float) {
            this.blackPoint = black
            this.whitePoint = white
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            
            val w = width
            val h = height
            val chartH = h - 25 // Leave room for handles/labels at bottom
            val maxCount = data.bins.maxOrNull() ?: 1
            
            // Draw histogram with subtle gradient
            val gradient = LinearGradientPaint(0f, 0f, 0f, chartH.toFloat(),
                floatArrayOf(0f, 1f),
                arrayOf(JBColor.BLUE.brighter(), JBColor.BLUE))
            g2.paint = gradient
            
            for (i in data.bins.indices) {
                val bx = (i.toFloat() / data.bins.size * w).toInt()
                val bw = (w.toFloat() / data.bins.size).toInt().coerceAtLeast(1)
                val bh = (data.bins[i].toFloat() / maxCount * chartH).toInt()
                g2.fillRect(bx, chartH - bh, bw, bh)
            }

            // Draw Black/White lines and handles
            val bPos = (((blackPoint - data.binMin) / (data.binMax - data.binMin)) * w).toInt().coerceIn(0, w)
            val wPos = (((whitePoint - data.binMin) / (data.binMax - data.binMin)) * w).toInt().coerceIn(0, w)

            // Vertical lines
            g2.stroke = BasicStroke(1f)
            g2.color = JBColor.GRAY
            g2.drawLine(bPos, 0, bPos, chartH)
            g2.drawLine(wPos, 0, wPos, chartH)
            
            // Triangular handles at the bottom
            drawHandle(g2, bPos, chartH, Color.BLACK)
            drawHandle(g2, wPos, chartH, Color.WHITE)
            
            // Value labels
            g2.font = JBUI.Fonts.miniFont()
            g2.color = JBColor.foreground()
            g2.drawString(String.format("%.2f", blackPoint), bPos - 15, chartH + 22)
            g2.drawString(String.format("%.2f", whitePoint), wPos - 15, chartH + 22)
            
            // Range labels
            g2.color = JBColor.GRAY
            g2.drawString(String.format("%.1f", data.binMin), 2, 10)
            g2.drawString(String.format("%.1f", data.binMax), w - 30, 10)
        }

        private fun drawHandle(g2: Graphics2D, x: Int, y: Int, color: Color) {
            val size = 8
            val poly = Polygon(
                intArrayOf(x, x - size, x + size),
                intArrayOf(y, y + size, y + size),
                3
            )
            g2.color = color
            g2.fillPolygon(poly)
            g2.color = JBColor.border()
            g2.drawPolygon(poly)
        }
    }

    private inner class FitsImagePanel(
        private val rawData: FloatArray,
        private val imgWidth: Int,
        private val imgHeight: Int,
        private val wcs: WcsInfo?,
        private val onStatusUpdate: (String) -> Unit
    ) : JPanel() {
        var zoomFactor = 1.0
        var isInitialized = false
        var showCrosshair = true
        var showMagnifier = true
        var isMeasuring = false
        var blackPoint = 0f
        var whitePoint = 1f
        private var mouseX = -1
        private var mouseY = -1
        private var measureStart: Point? = null
        private var measureEnd: Point? = null
        private var scaleMode = ScaleMode.LINEAR
        private var colorMap = ColorMap.GRAYSCALE
        private var displayImage: BufferedImage? = null

        init {
            background = JBColor.lazy { if (JBColor.isBright()) Color.WHITE else Color.BLACK }
            val mouseAdapter = object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    mouseX = e.x
                    mouseY = e.y
                    updateStatus(mouseX, mouseY)
                    if (showMagnifier || isMeasuring) repaint()
                }
                override fun mousePressed(e: MouseEvent) {
                    if (isMeasuring) {
                        measureStart = e.point
                        measureEnd = e.point
                        repaint()
                    }
                }
                override fun mouseDragged(e: MouseEvent) {
                    mouseX = e.x
                    mouseY = e.y
                    if (isMeasuring) {
                        measureEnd = e.point
                        repaint()
                    }
                    updateStatus(mouseX, mouseY)
                }
                override fun mouseReleased(e: MouseEvent) {
                    if (isMeasuring) {
                        measureEnd = e.point
                        repaint()
                    }
                }
                override fun mouseExited(e: MouseEvent) {
                    onStatusUpdate("Ready")
                    mouseX = -1
                    mouseY = -1
                    if (showMagnifier || isMeasuring) repaint()
                }
            }
            addMouseMotionListener(mouseAdapter)
            addMouseListener(mouseAdapter)
        }

        private fun updateStatus(ex: Int, ey: Int) {
            val px = (ex / zoomFactor).toInt()
            val py = (ey / zoomFactor).toInt()
            
            if (px in 0 until imgWidth && py in 0 until imgHeight) {
                val valStr = String.format("%.2f", rawData[py * imgWidth + px])
                var wcsStr = ""
                if (wcs != null) {
                    val world = pixelToWorld(px.toDouble(), py.toDouble())
                    val raStr = formatRa(world[0])
                    val decStr = formatDec(world[1])
                    wcsStr = " | RA: $raStr, Dec: $decStr"
                }
                onStatusUpdate(String.format("Pixel: %d, %d | Val: %s%s", px, py, valStr, wcsStr))
            } else {
                onStatusUpdate("Ready")
            }
        }

        private fun formatRa(degrees: Double): String {
            var h = (degrees % 360.0) / 15.0
            if (h < 0) h += 24.0
            val hours = h.toInt()
            val m = (h - hours) * 60.0
            val minutes = m.toInt()
            val seconds = (m - minutes) * 60.0
            return "%02dh %02dm %05.2fs".format(hours, minutes, seconds)
        }

        private fun formatDec(degrees: Double): String {
            val sign = if (degrees < 0) "-" else "+"
            val absDeg = abs(degrees)
            val d = absDeg.toInt()
            val m = (absDeg - d) * 60.0
            val minutes = m.toInt()
            val seconds = (m - minutes) * 60.0
            return "%s%02d° %02d' %05.2f\"".format(sign, d, minutes, seconds)
        }

        private fun pixelToWorld(px: Double, py: Double): DoubleArray {
            if (wcs == null) return doubleArrayOf(0.0, 0.0)
            val dx = px + 1.0 - wcs.crpix[0]
            val dy = py + 1.0 - wcs.crpix[1]

            val dWorld = if (wcs.cd != null) {
                doubleArrayOf(
                    wcs.cd[0][0] * dx + wcs.cd[0][1] * dy,
                    wcs.cd[1][0] * dx + wcs.cd[1][1] * dy
                )
            } else if (wcs.cdelt != null && wcs.pc != null) {
                doubleArrayOf(
                    (wcs.pc[0][0] * wcs.cdelt[0] * dx + wcs.pc[0][1] * wcs.cdelt[0] * dy),
                    (wcs.pc[1][0] * wcs.cdelt[1] * dx + wcs.pc[1][1] * wcs.cdelt[1] * dy)
                )
            } else if (wcs.cdelt != null) {
                doubleArrayOf(wcs.cdelt[0] * dx, wcs.cdelt[1] * dy)
            } else {
                return doubleArrayOf(px, py)
            }

            // Simple gnomonic projection approximation near reference point
            val dec = wcs.crval[1] + dWorld[1]
            val cosDec = cos(Math.toRadians(wcs.crval[1]))
            val ra = wcs.crval[0] + (if (abs(cosDec) > 1e-6) dWorld[0] / cosDec else dWorld[0])

            return doubleArrayOf(ra, dec)
        }

        private fun getViewport(): JViewport? {
            return SwingUtilities.getAncestorOfClass(JViewport::class.java, this) as? JViewport
        }

        fun autoFit() {
            val vp = getViewport() ?: return
            val vw = vp.width.toDouble()
            val vh = vp.height.toDouble()
            if (vw <= 0 || vh <= 0) return
            
            zoomFactor = min(vw / imgWidth, vh / imgHeight)
            if (zoomFactor <= 0) zoomFactor = 1.0
            revalidate()
            repaint()
        }

        fun setScaleMode(mode: ScaleMode) {
            this.scaleMode = mode
            updateImage()
            repaint()
        }

        fun setColorMap(map: ColorMap) {
            this.colorMap = map
            updateImage()
            repaint()
            parent?.background = background
        }

        fun setBlackWhitePoints(black: Float, white: Float) {
            this.blackPoint = black
            this.whitePoint = white
            updateImage()
            repaint()
        }

        fun zoomIn() {
            zoomFactor = min(10.0, zoomFactor * 1.2)
            revalidate()
            repaint()
            parent?.revalidate()
        }

        fun zoomOut() {
            zoomFactor = max(0.01, zoomFactor / 1.2)
            revalidate()
            repaint()
            parent?.revalidate()
        }

        override fun getPreferredSize(): Dimension {
            return Dimension((imgWidth * zoomFactor).toInt(), (imgHeight * zoomFactor).toInt())
        }

        private fun updateImage() {
            val range = whitePoint - blackPoint
            val img = BufferedImage(imgWidth, imgHeight, BufferedImage.TYPE_INT_RGB)

            for (i in rawData.indices) {
                val x = i % imgWidth
                val y = i / imgWidth
                val rawVal = rawData[i]
                
                var normalized = if (range <= 0f || !rawVal.isFinite()) {
                    if (rawVal >= whitePoint) 1f else 0f
                } else (rawVal - blackPoint) / range
                
                normalized = when (scaleMode) {
                    ScaleMode.LINEAR -> normalized
                    ScaleMode.LOG -> if (normalized <= 0) 0f else ln(1f + normalized * 100f) / ln(101f)
                    ScaleMode.SQRT -> sqrt(max(0f, normalized))
                    ScaleMode.ASINH -> {
                        val beta = 0.05f // Softening parameter
                        asinh(normalized / beta) / asinh(1f / beta)
                    }
                }
                
                val t = max(0f, min(1f, normalized))
                val rgb = applyColorMap(t)
                img.setRGB(x, y, rgb)
            }
            displayImage = img
        }

        private fun applyColorMap(t: Float): Int {
            return when (colorMap) {
                ColorMap.GRAYSCALE -> {
                    val gray = (t * 255).toInt()
                    (gray shl 16) or (gray shl 8) or gray
                }
                ColorMap.HEAT -> {
                    val r = (min(1f, t * 2f) * 255).toInt()
                    val g = (max(0f, t * 2f - 1f) * 255).toInt()
                    val b = (max(0f, t * 4f - 3f) * 255).toInt()
                    (r shl 16) or (g shl 8) or b
                }
                ColorMap.VIRIDIS -> interpolateViridis(t)
                ColorMap.MAGMA -> interpolateMagma(t)
            }
        }

        private fun interpolateViridis(t: Float): Int {
            val colors = arrayOf(
                intArrayOf(68, 1, 84),    // Purple
                intArrayOf(59, 82, 139),  // Blue
                intArrayOf(33, 145, 140), // Teal
                intArrayOf(94, 201, 98),  // Green
                intArrayOf(253, 231, 37)  // Yellow
            )
            return interpolate(t, colors)
        }

        private fun interpolateMagma(t: Float): Int {
            val colors = arrayOf(
                intArrayOf(0, 0, 4),       // Black-ish
                intArrayOf(81, 18, 124),   // Purple
                intArrayOf(182, 54, 121),  // Pink
                intArrayOf(251, 136, 97),  // Orange
                intArrayOf(252, 253, 191)  // Pale Yellow
            )
            return interpolate(t, colors)
        }

        private fun interpolate(t: Float, palette: Array<IntArray>): Int {
            val n = palette.size - 1
            val pos = t * n
            val idx = min(n - 1, pos.toInt())
            val localT = pos - idx
            
            val c1 = palette[idx]
            val c2 = palette[idx + 1]
            
            val r = (c1[0] + (c2[0] - c1[0]) * localT).toInt()
            val g = (c1[1] + (c2[1] - c1[1]) * localT).toInt()
            val b = (c1[2] + (c2[2] - c1[2]) * localT).toInt()
            
            return (r shl 16) or (g shl 8) or b
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            
            val dw = (imgWidth * zoomFactor).toInt()
            val dh = (imgHeight * zoomFactor).toInt()
            
            displayImage?.let {
                g2.drawImage(it, 0, 0, dw, dh, null)
            }

            if (showCrosshair) {
                g2.color = JBColor.RED
                g2.stroke = BasicStroke(1f)
                val cx = dw / 2
                val cy = dh / 2
                g2.drawLine(cx - 20, cy, cx + 20, cy)
                g2.drawLine(cx, cy - 20, cx, cy + 20)
            }

            if (showMagnifier && mouseX != -1 && displayImage != null) {
                drawMagnifier(g2)
            }

            if (isMeasuring) {
                drawMeasurement(g2)
            }
        }

        private fun drawMeasurement(g2: Graphics2D) {
            val start = measureStart ?: return
            val end = measureEnd ?: return
            
            g2.color = JBColor.YELLOW
            g2.stroke = BasicStroke(2f)
            g2.drawLine(start.x, start.y, end.x, end.y)
            
            val dx = (end.x - start.x) / zoomFactor
            val dy = (end.y - start.y) / zoomFactor
            val distPx = sqrt(dx * dx + dy * dy)
            
            var text = String.format("%.1f px", distPx)
            
            if (wcs != null) {
                val p1 = pixelToWorld(start.x / zoomFactor, start.y / zoomFactor)
                val p2 = pixelToWorld(end.x / zoomFactor, end.y / zoomFactor)
                val distDeg = sqrt((p1[0] - p2[0]).pow(2) + (p1[1] - p2[1]).pow(2))
                text += String.format(" | %.2f\"", distDeg * 3600.0)
            }
            
            g2.font = JBUI.Fonts.label()
            val metrics = g2.fontMetrics
            val tw = metrics.stringWidth(text)
            g2.color = Color(0, 0, 0, 180)
            g2.fillRect((start.x + end.x) / 2, (start.y + end.y) / 2 - 15, tw + 4, 20)
            g2.color = JBColor.YELLOW
            g2.drawString(text, (start.x + end.x) / 2 + 2, (start.y + end.y) / 2)
        }

        private fun drawMagnifier(g2: Graphics2D) {
            val magSize = 150
            val magZoom = 4
            
            val vp = getViewport()
            val viewRect = vp?.viewRect ?: Rectangle(0, 0, width, height)
            
            // Calculate visible image bounds in local coordinates
            val localVisibleRight = min(width, viewRect.x + viewRect.width - x)
            val localVisibleTop = max(0, viewRect.y - y)
            
            val magX = localVisibleRight - magSize - 10
            val magY = localVisibleTop + 10
            
            // Only draw if we have some space
            if (magX < 0 || magY + magSize > height) return

            g2.color = background
            g2.fillRect(magX, magY, magSize, magSize)
            g2.color = JBColor.GRAY
            g2.drawRect(magX, magY, magSize, magSize)
            
            val px = (mouseX / zoomFactor).toInt()
            val py = (mouseY / zoomFactor).toInt()
            
            val srcSize = magSize / magZoom
            val sx = px - srcSize / 2
            val sy = py - srcSize / 2
            
            val oldClip = g2.clip
            g2.setClip(magX, magY, magSize, magSize)
            
            displayImage?.let {
                g2.drawImage(it, 
                    magX, magY, magX + magSize, magY + magSize,
                    sx, sy, sx + srcSize, sy + srcSize,
                    null)
            }
            
            g2.color = JBColor.RED
            g2.drawRect(magX + magSize / 2 - 1, magY + magSize / 2 - 1, 2, 2)
            
            g2.clip = oldClip
        }
    }

    override fun getComponent(): JComponent = myComponent

    override fun getPreferredFocusedComponent(): JComponent? = myComponent

    override fun getName(): String = "FITS Viewer"

    override fun setState(state: FileEditorState) {}

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = virtualFile.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun dispose() {
        try {
            fits?.close()
        } catch (e: Exception) {
            LOG.warn("Error closing FITS file ${virtualFile.name}", e)
        }
    }

    override fun getFile(): VirtualFile = virtualFile
}
