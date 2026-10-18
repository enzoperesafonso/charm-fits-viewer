package com.github.enzoperesafonso.fitsviewer

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.GridBagLayout
import java.awt.Point
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

class FitsImagePanelTest : BasePlatformTestCase() {
    private lateinit var panel: FitsImagePanel
    private lateinit var scrollPane: JScrollPane

    override fun setUp() {
        super.setUp()
        val image = FitsImage(FloatArray(1000 * 800), 1000, 800, step = 1, originalWidth = 1000, originalHeight = 800)
        panel = FitsImagePanel(image, null, onStatus = {}, onZoomChanged = {})
        // Same structure as FitsImageView: scroll pane -> viewport -> centring panel -> image
        scrollPane = JScrollPane(JPanel(GridBagLayout()).apply { add(panel) })
        scrollPane.setSize(400, 300)
        // Give the off-screen components (dummy) peers, as on screen; validate() is a no-op without them
        scrollPane.addNotify()
        scrollPane.validate()
    }

    private val viewPosition get() = scrollPane.viewport.viewPosition

    /** Where an image point (in panel coordinates) appears inside the viewport. */
    private fun onScreen(p: Point) = SwingUtilities.convertPoint(panel, p, scrollPane.viewport)

    private fun pressKey(code: Int, modifiers: Int = 0) {
        val name = panel.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(code, modifiers))
        assertNotNull("No binding for key $code", name)
        panel.actionMap.get(name).actionPerformed(ActionEvent(panel, ActionEvent.ACTION_PERFORMED, name.toString()))
    }

    fun testZoomKeepsTheAnchorPointInPlace() {
        scrollPane.viewport.viewPosition = Point(100, 100)
        val anchor = Point(300, 250)
        val before = onScreen(anchor)

        panel.zoomBy(2.0, anchor)

        assertEquals(2.0, panel.zoom, 1e-9)
        assertEquals(before, onScreen(Point(600, 500)))
    }

    fun testCtrlScrollZoomsAroundTheCursor() {
        scrollPane.viewport.viewPosition = Point(200, 150)
        val cursor = Point(350, 300)
        val before = onScreen(cursor)

        panel.dispatchEvent(
            MouseWheelEvent(
                panel, MouseWheelEvent.MOUSE_WHEEL, 0, InputEvent.CTRL_DOWN_MASK, cursor.x, cursor.y, 0, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -1,
            ),
        )

        assertTrue(panel.zoom > 1.0)
        val after = onScreen(Point((cursor.x * panel.zoom).toInt(), (cursor.y * panel.zoom).toInt()))
        assertEquals(before.x.toDouble(), after.x.toDouble(), 1.0)
        assertEquals(before.y.toDouble(), after.y.toDouble(), 1.0)
    }

    fun testPanningStopsAtTheEdges() {
        panel.panBy(120, 80)
        assertEquals(Point(120, 80), viewPosition)

        panel.panBy(-1000, 10_000)
        assertEquals(Point(0, 800 - scrollPane.viewport.extentSize.height), viewPosition)
    }

    fun testKeyboardShortcuts() {
        pressKey(KeyEvent.VK_RIGHT)
        pressKey(KeyEvent.VK_DOWN, InputEvent.SHIFT_DOWN_MASK) // half a screen
        assertEquals(Point(50, scrollPane.viewport.extentSize.height / 2), viewPosition)

        pressKey(KeyEvent.VK_EQUALS)
        assertEquals(ZOOM_STEP, panel.zoom, 1e-9)
        pressKey(KeyEvent.VK_MINUS)
        assertEquals(1.0, panel.zoom, 1e-9)

        pressKey(KeyEvent.VK_0)
        // Fits the whole scroll pane (inside its border), not the area left over by scrollbars
        val insets = scrollPane.insets
        assertEquals(minOf((400.0 - insets.left - insets.right) / 1000, (300.0 - insets.top - insets.bottom) / 800), panel.zoom, 1e-9)
        pressKey(KeyEvent.VK_1)
        assertEquals(1.0, panel.zoom, 1e-9)
    }
}
