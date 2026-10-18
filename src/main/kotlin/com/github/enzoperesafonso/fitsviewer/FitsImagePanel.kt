package com.github.enzoperesafonso.fitsviewer

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.util.Locale
import javax.swing.AbstractAction
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JViewport
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Neutral backdrop around the image, distinct from both black sky and white saturation. */
internal val IMAGE_BACKGROUND: Color = JBColor(Color(0xD3D5DA), Color(0x2B2D30))

/** Zoom factor for one step of the zoom buttons and keys. */
internal const val ZOOM_STEP = 1.25

/**
 * Draws one [FitsImage] at a given zoom, with optional crosshair, magnifier and measuring overlays.
 * Coordinates called "image" below are in the (possibly downsampled) displayed image, y pointing down.
 */
internal class FitsImagePanel(
    val image: FitsImage,
    private val wcs: Wcs?,
    private val onStatus: (String?) -> Unit,
    private val onZoomChanged: () -> Unit,
) : JPanel() {
    private val buffer = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)

    var zoom = 1.0
        private set

    /** Zoom relative to the original (not downsampled) pixels. */
    val effectiveZoom: Double get() = zoom / image.step

    /** Whether the image has been fitted to the viewport since it was shown. */
    var isFitted = false
        private set

    var showCrosshair = false
        set(value) { field = value; repaint() }

    var showMagnifier = false
        set(value) { field = value; repaint() }

    var measuring = false
        set(value) {
            field = value
            if (!value) {
                measureStart = null
                measureEnd = null
            }
            repaint()
        }

    private var mouse: Point? = null
    private var measureStart: Point2D.Double? = null
    private var measureEnd: Point2D.Double? = null

    /** Screen position where a pan drag started, and the view position at that moment. */
    private var panStart: Point? = null
    private var panOrigin: Point? = null

    init {
        background = IMAGE_BACKGROUND
        isOpaque = true
        isFocusable = true

        val mouseHandler = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) = track(e.point)

            override fun mousePressed(e: MouseEvent) {
                requestFocusInWindow()
                // Drag pans, except that the left button measures while Measure is on
                if (SwingUtilities.isMiddleMouseButton(e) || SwingUtilities.isLeftMouseButton(e) && !measuring) {
                    val viewport = viewport() ?: return
                    panStart = e.locationOnScreen
                    panOrigin = viewport.viewPosition
                    cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    measureStart = toImage(e.point)
                    measureEnd = measureStart
                    repaint()
                }
            }

            override fun mouseDragged(e: MouseEvent) {
                track(e.point)
                val start = panStart
                val origin = panOrigin
                if (start != null && origin != null) {
                    val viewport = viewport() ?: return
                    scrollViewTo(viewport, origin.x - (e.xOnScreen - start.x), origin.y - (e.yOnScreen - start.y))
                } else if (measuring && measureStart != null) {
                    measureEnd = toImage(e.point)
                    repaint()
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                panStart = null
                panOrigin = null
                cursor = Cursor.getDefaultCursor()
            }

            override fun mouseExited(e: MouseEvent) {
                mouse = null
                onStatus(null)
                repaintMagnifier()
            }

            override fun mouseWheelMoved(e: MouseWheelEvent) {
                if (e.isControlDown || e.isMetaDown) {
                    zoomBy(WHEEL_ZOOM_STEP.pow(-e.preciseWheelRotation), e.point)
                } else {
                    // A wheel listener here would otherwise swallow normal scrolling
                    val scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, this@FitsImagePanel) ?: return
                    scrollPane.dispatchEvent(SwingUtilities.convertMouseEvent(this@FitsImagePanel, e, scrollPane))
                }
            }
        }
        addMouseListener(mouseHandler)
        addMouseMotionListener(mouseHandler)
        addMouseWheelListener(mouseHandler)
        installKeyBindings()
    }

    private fun installKeyBindings() {
        fun bind(name: String, vararg strokes: KeyStroke, action: () -> Unit) {
            strokes.forEach { getInputMap(WHEN_FOCUSED).put(it, name) }
            actionMap.put(name, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = action()
            })
        }
        fun key(code: Int, modifiers: Int = 0): KeyStroke = KeyStroke.getKeyStroke(code, modifiers)
        val shift = InputEvent.SHIFT_DOWN_MASK

        bind("zoomIn", key(KeyEvent.VK_EQUALS), key(KeyEvent.VK_EQUALS, shift), key(KeyEvent.VK_PLUS), key(KeyEvent.VK_ADD)) {
            zoomBy(ZOOM_STEP)
        }
        bind("zoomOut", key(KeyEvent.VK_MINUS), key(KeyEvent.VK_SUBTRACT)) { zoomBy(1 / ZOOM_STEP) }
        bind("zoomToFit", key(KeyEvent.VK_0), key(KeyEvent.VK_NUMPAD0)) { fitToViewport() }
        bind("zoomToActualSize", key(KeyEvent.VK_1), key(KeyEvent.VK_NUMPAD1)) { setEffectiveZoom(1.0) }
        for ((code, dx, dy) in listOf(
            Triple(KeyEvent.VK_LEFT, -1, 0),
            Triple(KeyEvent.VK_RIGHT, 1, 0),
            Triple(KeyEvent.VK_UP, 0, -1),
            Triple(KeyEvent.VK_DOWN, 0, 1),
        )) {
            bind("pan$code", key(code)) { panBy(dx * KEY_PAN_STEP, dy * KEY_PAN_STEP) }
            bind("panFar$code", key(code, shift)) { panBy(dx * visibleRect.width / 2, dy * visibleRect.height / 2) }
        }
    }

    fun render(black: Float, white: Float, lut: IntArray) {
        ImageRenderer.render(image, black, white, lut, buffer)
        repaint()
    }

    fun fitToViewport() {
        val viewport = viewport() ?: return
        // Use the whole scroll pane: the scrollbars disappear once the image fits
        val area = (viewport.parent as? JScrollPane)?.let {
            val insets = it.insets
            Dimension(it.width - insets.left - insets.right, it.height - insets.top - insets.bottom)
        } ?: viewport.size
        if (area.width <= 0 || area.height <= 0) return
        setZoom(min(area.width.toDouble() / image.width, area.height.toDouble() / image.height))
        isFitted = true
    }

    /**
     * Zooms by [factor], keeping the image point under [anchor] (in this panel's coordinates) at the same
     * place on screen. Without an anchor, the centre of the visible area stays put.
     */
    fun zoomBy(factor: Double, anchor: Point? = null) {
        val viewport = viewport()
        if (viewport == null) {
            setZoom(zoom * factor)
            return
        }
        val visible = visibleRect
        val point = anchor ?: Point(visible.centerX.toInt(), visible.centerY.toInt())
        val onScreen = SwingUtilities.convertPoint(this, point, viewport)
        val imageX = point.x / zoom
        val imageY = point.y / zoom

        setZoom(zoom * factor)
        // Lay out at the new size right away so the view can be repositioned before the next paint
        viewport.parent?.validate()
        val target = SwingUtilities.convertPoint(this, Point((imageX * zoom).roundToInt(), (imageY * zoom).roundToInt()), viewport.view)
        scrollViewTo(viewport, target.x - onScreen.x, target.y - onScreen.y)
    }

    fun setEffectiveZoom(value: Double) = zoomBy(value * image.step / zoom)

    /** Scrolls the view by the given number of screen pixels. */
    fun panBy(dx: Int, dy: Int) {
        val viewport = viewport() ?: return
        val position = viewport.viewPosition
        scrollViewTo(viewport, position.x + dx, position.y + dy)
    }

    private fun viewport() = SwingUtilities.getAncestorOfClass(JViewport::class.java, this) as? JViewport

    private fun scrollViewTo(viewport: JViewport, x: Int, y: Int) {
        val view = viewport.view ?: return
        val extent = viewport.extentSize
        viewport.viewPosition = Point(
            x.coerceIn(0, max(0, view.width - extent.width)),
            y.coerceIn(0, max(0, view.height - extent.height)),
        )
    }

    private fun setZoom(value: Double) {
        zoom = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
        revalidate()
        repaint()
        onZoomChanged()
    }

    override fun getPreferredSize() = Dimension(ceil(image.width * zoom).toInt(), ceil(image.height * zoom).toInt())

    override fun getMinimumSize() = preferredSize

    private fun toImage(p: Point) = Point2D.Double(p.x / zoom, p.y / zoom)

    private fun track(p: Point) {
        mouse = p
        onStatus(describePixel(p))
        repaintMagnifier()
    }

    private fun describePixel(p: Point): String? {
        val ix = floor(p.x / zoom).toInt()
        val iy = floor(p.y / zoom).toInt()
        if (ix !in 0 until image.width || iy !in 0 until image.height) return null
        val row = image.height - 1 - iy // FITS row 0 is at the bottom
        val value = image.data[row * image.width + ix]
        val x = ix * image.step
        val y = row * image.step
        val text = StringBuilder("x: $x  y: $y  value: ${"%.6g".format(Locale.ROOT, value)}")
        if (wcs != null) {
            text.append("   |   ").append(wcs.format(wcs.pixelToWorld(x.toDouble(), y.toDouble())))
        }
        return text.toString()
    }

    /** Original-pixel coordinates (0-based, y up) of a point in image coordinates. */
    private fun toPixel(p: Point2D.Double) = doubleArrayOf(p.x * image.step, (image.height - p.y) * image.step)

    private fun magnifierBounds(): Rectangle? {
        val visible = visibleRect
        if (visible.width < MAGNIFIER_SIZE + 2 * MARGIN || visible.height < MAGNIFIER_SIZE + 2 * MARGIN) return null
        return Rectangle(visible.x + visible.width - MAGNIFIER_SIZE - MARGIN, visible.y + MARGIN, MAGNIFIER_SIZE, MAGNIFIER_SIZE)
    }

    // Only the magnifier changes when the mouse moves; don't repaint the whole image
    private fun repaintMagnifier() {
        if (!showMagnifier) return
        val r = magnifierBounds() ?: return
        repaint(r.x, r.y, r.width + 1, r.height + 1)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                // Show individual pixels when zoomed in; smooth when zoomed out
                if (zoom >= 1.0) RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR else RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            val dw = ceil(image.width * zoom).toInt()
            val dh = ceil(image.height * zoom).toInt()
            g2.drawImage(buffer, 0, 0, dw, dh, null)

            if (showCrosshair) {
                g2.color = JBColor.RED
                g2.stroke = BasicStroke(1f)
                val cx = dw / 2
                val cy = dh / 2
                g2.drawLine(cx - 20, cy, cx + 20, cy)
                g2.drawLine(cx, cy - 20, cx, cy + 20)
            }
            if (measuring) drawMeasurement(g2)
            if (showMagnifier) drawMagnifier(g2)
        } finally {
            g2.dispose()
        }
    }

    private fun drawMeasurement(g2: Graphics2D) {
        val start = measureStart ?: return
        val end = measureEnd ?: return
        val x1 = (start.x * zoom).toInt()
        val y1 = (start.y * zoom).toInt()
        val x2 = (end.x * zoom).toInt()
        val y2 = (end.y * zoom).toInt()

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = JBColor.YELLOW
        g2.stroke = BasicStroke(2f)
        g2.drawLine(x1, y1, x2, y2)

        var text = "%.1f px".format(Locale.ROOT, hypot(end.x - start.x, end.y - start.y) * image.step)
        if (wcs != null && wcs.isCelestial) {
            val p1 = toPixel(start)
            val p2 = toPixel(end)
            val degrees = Wcs.angularSeparation(wcs.pixelToWorld(p1[0], p1[1]), wcs.pixelToWorld(p2[0], p2[1]))
            text += "  |  %.2f\"".format(Locale.ROOT, degrees * 3600.0)
        }

        g2.font = JBUI.Fonts.label()
        val tw = g2.fontMetrics.stringWidth(text)
        val tx = (x1 + x2) / 2
        val ty = (y1 + y2) / 2
        g2.color = Color(0, 0, 0, 180)
        g2.fillRect(tx, ty - 15, tw + 4, 20)
        g2.color = JBColor.YELLOW
        g2.drawString(text, tx + 2, ty)
    }

    private fun drawMagnifier(g2: Graphics2D) {
        val m = mouse ?: return
        val r = magnifierBounds() ?: return
        val src = MAGNIFIER_SIZE / MAGNIFIER_ZOOM
        val sx = (m.x / zoom).toInt() - src / 2
        val sy = (m.y / zoom).toInt() - src / 2

        g2.color = background
        g2.fillRect(r.x, r.y, r.width, r.height)
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        g2.drawImage(buffer, r.x, r.y, r.x + r.width, r.y + r.height, sx, sy, sx + src, sy + src, null)
        g2.color = JBColor.GRAY
        g2.drawRect(r.x, r.y, r.width, r.height)
        g2.color = JBColor.RED
        g2.drawRect(r.x + r.width / 2 - 1, r.y + r.height / 2 - 1, 2, 2)
    }

    private companion object {
        const val MIN_ZOOM = 0.01
        const val MAX_ZOOM = 32.0
        const val WHEEL_ZOOM_STEP = 1.15
        const val KEY_PAN_STEP = 50
        const val MAGNIFIER_SIZE = 150
        const val MAGNIFIER_ZOOM = 4
        const val MARGIN = 10
    }
}
