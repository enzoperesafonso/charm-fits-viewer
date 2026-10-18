package com.github.enzoperesafonso.fitsviewer

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.Polygon
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.Locale
import javax.swing.JPanel
import kotlin.math.abs
import kotlin.math.ln

/** Histogram with draggable black and white level handles. */
internal class HistogramPanel(private val onLevelsChanged: (Float, Float) -> Unit) : JPanel() {
    private enum class Handle { NONE, BLACK, WHITE }

    private var histogram: Histogram? = null
    private var black = 0f
    private var white = 1f
    private var dragging = Handle.NONE

    init {
        preferredSize = JBUI.size(200, 110)
        // Layouts fall back to minimum sizes when space is short; never collapse the chart
        minimumSize = JBUI.size(120, 110)
        background = JBColor.PanelBackground
        border = JBUI.Borders.customLine(JBColor.border(), 1)

        val mouseHandler = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                dragging = handleAt(e.x)
            }

            override fun mouseDragged(e: MouseEvent) {
                val h = histogram ?: return
                if (dragging == Handle.NONE) return
                val value = valueAt(h, e.x)
                // Keep the handles from crossing; the gap scales with the data range
                val gap = (h.max - h.min) / 1000f
                if (dragging == Handle.BLACK) {
                    black = value.coerceIn(h.min, white - gap)
                } else {
                    white = value.coerceIn(black + gap, h.max)
                }
                onLevelsChanged(black, white)
                repaint()
            }

            override fun mouseReleased(e: MouseEvent) {
                dragging = Handle.NONE
            }

            override fun mouseMoved(e: MouseEvent) {
                cursor = if (handleAt(e.x) != Handle.NONE) {
                    Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR)
                } else {
                    Cursor.getDefaultCursor()
                }
            }
        }
        addMouseListener(mouseHandler)
        addMouseMotionListener(mouseHandler)
    }

    fun setData(histogram: Histogram?, black: Float, white: Float) {
        this.histogram = histogram
        setLevels(black, white)
    }

    fun setLevels(black: Float, white: Float) {
        this.black = black
        this.white = white
        repaint()
    }

    private fun xOf(h: Histogram, v: Float): Int = ((v - h.min) / (h.max - h.min) * width).toInt().coerceIn(0, width)

    private fun valueAt(h: Histogram, x: Int): Float = h.min + x.toFloat() / width * (h.max - h.min)

    private fun handleAt(x: Int): Handle {
        val h = histogram ?: return Handle.NONE
        val dBlack = abs(x - xOf(h, black))
        val dWhite = abs(x - xOf(h, white))
        return when {
            dBlack >= HANDLE_GRAB && dWhite >= HANDLE_GRAB -> Handle.NONE
            dBlack < dWhite -> Handle.BLACK
            dWhite < dBlack -> Handle.WHITE
            // Handles on top of each other: pick by side
            else -> if (x >= xOf(h, white)) Handle.WHITE else Handle.BLACK
        }
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val h = histogram ?: return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val w = width
            val chartH = height - 25 // room for handles and labels
            // Log-scaled counts, so the faint tail is visible next to the sky peak
            val maxLog = ln(1.0 + (h.counts.maxOrNull() ?: 0)).coerceAtLeast(1e-9)

            g2.paint = LinearGradientPaint(
                0f, 0f, 0f, chartH.toFloat().coerceAtLeast(1f),
                floatArrayOf(0f, 1f),
                arrayOf(JBColor.BLUE.brighter(), JBColor.BLUE),
            )
            val bins = h.counts.size
            for (i in 0 until bins) {
                val bx = (i.toFloat() / bins * w).toInt()
                val bw = (w.toFloat() / bins).toInt().coerceAtLeast(1)
                val bh = (ln(1.0 + h.counts[i]) / maxLog * chartH).toInt()
                g2.fillRect(bx, chartH - bh, bw, bh)
            }

            val bPos = xOf(h, black)
            val wPos = xOf(h, white)
            g2.stroke = BasicStroke(1f)
            g2.color = JBColor.GRAY
            g2.drawLine(bPos, 0, bPos, chartH)
            g2.drawLine(wPos, 0, wPos, chartH)
            drawHandle(g2, bPos, chartH, Color.BLACK)
            drawHandle(g2, wPos, chartH, Color.WHITE)

            g2.font = JBUI.Fonts.miniFont()
            g2.color = JBColor.foreground()
            // Black label to the left of its handle, white to the right, so they don't overlap
            val fm = g2.fontMetrics
            val blackLabel = formatLevel(black)
            val whiteLabel = formatLevel(white)
            g2.drawString(blackLabel, (bPos - fm.stringWidth(blackLabel) - 2).coerceAtLeast(0), chartH + 22)
            g2.drawString(whiteLabel, (wPos + 2).coerceAtMost(w - fm.stringWidth(whiteLabel)), chartH + 22)

            g2.color = JBColor.GRAY
            val maxLabel = formatLevel(h.max)
            g2.drawString(formatLevel(h.min), 2, 10)
            g2.drawString(maxLabel, w - g2.fontMetrics.stringWidth(maxLabel) - 2, 10)
        } finally {
            g2.dispose()
        }
    }

    private fun drawHandle(g2: Graphics2D, x: Int, y: Int, color: Color) {
        val size = 8
        val poly = Polygon(intArrayOf(x, x - size, x + size), intArrayOf(y, y + size, y + size), 3)
        g2.color = color
        g2.fillPolygon(poly)
        g2.color = JBColor.border()
        g2.drawPolygon(poly)
    }

    private companion object {
        const val HANDLE_GRAB = 10

        fun formatLevel(v: Float) = "%.4g".format(Locale.ROOT, v)
    }
}
