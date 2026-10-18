package com.github.enzoperesafonso.fitsviewer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.ItemEvent
import java.beans.PropertyChangeListener
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.RowFilter
import javax.swing.SpinnerNumberModel
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter
import org.jetbrains.annotations.TestOnly

/**
 * Editor for FITS files. All reading happens in background tasks; the file is only open while a
 * task reads from it, and only the HDU headers plus the displayed plane are kept in memory.
 */
class FitsFileEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
    private val imageView = FitsImageView()
    private val headerModel = HeaderTableModel()
    private val headerFilter = SearchTextField(false)
    private val hduCombo = ComboBox<HduInfo>()
    private val planeModel = SpinnerNumberModel(1, 1, 1, 1)
    private val planeSpinner = JSpinner(planeModel)
    private val planeLabel = JBLabel("Plane:")
    private val planeCountLabel = JBLabel()
    private val warningLabel = JBLabel().apply { foreground = JBColor.RED }
    private val tabs = JBTabbedPane()
    private val component = JPanel(BorderLayout())

    /** Set while the combo box and spinner are updated programmatically. */
    private var updatingControls = false

    /** Incremented whenever a new load starts; results of older loads are dropped. */
    private val generation = AtomicInteger()

    @Volatile
    private var disposed = false

    /** Local copy of a file that isn't on the local file system (e.g. inside an archive). */
    private var tempCopy: File? = null

    init {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(JBLabel("HDU:"))
            add(hduCombo)
            add(planeLabel)
            add(planeSpinner)
            add(planeCountLabel)
            add(warningLabel)
        }
        setPlaneControlsVisible(false)

        val headerTable = JBTable(headerModel).apply {
            rowSorter = TableRowSorter(headerModel)
            setShowGrid(false)
        }
        headerFilter.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                val text = headerFilter.text.trim()
                @Suppress("UNCHECKED_CAST")
                (headerTable.rowSorter as TableRowSorter<HeaderTableModel>).rowFilter =
                    if (text.isEmpty()) null else RowFilter.regexFilter("(?i)" + Pattern.quote(text))
            }
        })
        val headerPanel = JPanel(BorderLayout()).apply {
            add(headerFilter.apply { border = JBUI.Borders.empty(4) }, BorderLayout.NORTH)
            add(JBScrollPane(headerTable), BorderLayout.CENTER)
        }

        tabs.addTab("Image", imageView)
        tabs.addTab("Header", headerPanel)
        component.add(toolbar, BorderLayout.NORTH)
        component.add(tabs, BorderLayout.CENTER)

        hduCombo.addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED && !updatingControls) selectHdu(e.item as HduInfo, 0)
        }
        planeSpinner.addChangeListener {
            val hdu = hduCombo.selectedItem as? HduInfo
            if (!updatingControls && hdu != null) loadPlane(hdu, planeModel.number.toInt() - 1)
        }

        // Pick up changes when the file is rewritten (e.g. by a script producing it)
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { it is VFileContentChangeEvent && it.file == file }) {
                        ApplicationManager.getApplication().invokeLater { if (!disposed) load(refresh = true) }
                    }
                }
            })

        load(refresh = false)
    }

    private fun load(refresh: Boolean) {
        val previous = hduCombo.selectedItem as? HduInfo
        val previousPlane = planeModel.number.toInt() - 1
        imageView.showMessage("Reading ${file.name}…")
        runInBackground("Reading ${file.name}", { checkCanceled ->
            FitsReader.scan(localFile(refresh), checkCanceled)
        }) { result ->
            warningLabel.text = result.warning.orEmpty()
            val hdus = result.hdus
            // After a reload keep the same HDU and plane if they still exist
            val initial = previous?.let { p -> hdus.getOrNull(p.index)?.takeIf { it.isImage == p.isImage } }
                ?: hdus.firstOrNull { it.isImage }
                ?: hdus.firstOrNull()
            updatingControls = true
            try {
                hduCombo.model = DefaultComboBoxModel(hdus.toTypedArray())
                hduCombo.selectedItem = initial
            } finally {
                updatingControls = false
            }
            if (initial == null) {
                imageView.showMessage("The file contains no HDUs.")
            } else {
                selectHdu(initial, if (initial.index == previous?.index) previousPlane else 0)
            }
            if (hdus.none { it.isImage }) tabs.selectedIndex = HEADER_TAB
        }
    }

    private fun selectHdu(hdu: HduInfo, plane: Int) {
        headerModel.rows = hdu.header
        val planes = hdu.planeCount
        val selectedPlane = plane.coerceIn(0, (planes - 1).coerceAtLeast(0))
        updatingControls = true
        try {
            planeModel.maximum = planes.coerceAtLeast(1)
            planeModel.value = selectedPlane + 1
            planeCountLabel.text = "of $planes"
            setPlaneControlsVisible(planes > 1)
        } finally {
            updatingControls = false
        }
        loadPlane(hdu, selectedPlane)
    }

    private fun loadPlane(hdu: HduInfo, plane: Int) {
        if (!hdu.isImage) {
            generation.incrementAndGet() // cancel any image load still running
            imageView.showMessage("This HDU has no image data. See the Header tab.")
            return
        }
        // Drop the current image before reading the next so both are never in memory at once
        imageView.showMessage("Loading image…")
        runInBackground("Loading FITS image", { checkCanceled ->
            val image = FitsReader.readPlane(localFile(false), hdu, plane, checkCanceled = checkCanceled)
            checkCanceled()
            val stats = ImageStatistics.compute(image.data) ?: return@runInBackground null
            checkCanceled()
            val histogram = Histogram.compute(image.data, stats.percentile(0.1), stats.percentile(99.9))
            LoadedImage(image, hdu.wcs, stats, histogram)
        }) { loaded ->
            if (loaded == null) imageView.showMessage("The image contains no finite pixel values.") else imageView.showImage(loaded)
        }
    }

    /**
     * Runs [compute] on a background thread and passes its result to [onSuccess] on the EDT, unless a newer
     * load was started or the editor was disposed in the meantime. [compute] should call the function it
     * receives regularly; it throws once the result is no longer wanted.
     */
    private fun <T> runInBackground(title: String, compute: (checkCanceled: () -> Unit) -> T, onSuccess: (T) -> Unit) {
        val gen = generation.incrementAndGet()
        val isStale = { disposed || gen != generation.get() }
        object : Task.Backgroundable(project, title, true) {
            private var result: T? = null

            override fun run(indicator: ProgressIndicator) {
                result = compute {
                    indicator.checkCanceled()
                    if (isStale()) throw ProcessCanceledException()
                }
            }

            override fun onSuccess() {
                @Suppress("UNCHECKED_CAST")
                val value = result as T
                result = null
                if (!isStale()) onSuccess(value)
            }

            override fun onThrowable(error: Throwable) {
                if (isStale()) return
                LOG.warn("Failed to read FITS file ${file.path}", error)
                val reason = if (error is OutOfMemoryError) "not enough memory" else error.message ?: error.javaClass.simpleName
                imageView.showMessage("Could not read ${file.name}: $reason")
            }
        }.queue()
    }

    @Synchronized
    private fun localFile(refresh: Boolean): File {
        if (file.isInLocalFileSystem) return file.toNioPath().toFile()
        tempCopy?.let { if (!refresh && it.exists()) return it }
        tempCopy?.delete()
        val copy = FileUtil.createTempFile("fits-viewer-", "." + (file.extension ?: "fits"), true)
        file.inputStream.use { input -> copy.outputStream().use { input.copyTo(it) } }
        tempCopy = copy
        return copy
    }

    @get:TestOnly
    internal val displayedImage: FitsImage? get() = imageView.displayedImage

    private fun setPlaneControlsVisible(visible: Boolean) {
        planeLabel.isVisible = visible
        planeSpinner.isVisible = visible
        planeCountLabel.isVisible = visible
    }

    override fun getComponent(): JComponent = component

    override fun getPreferredFocusedComponent(): JComponent = imageView.focusTarget ?: tabs

    override fun getName(): String = "FITS Viewer"

    override fun setState(state: FileEditorState) {}

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun getFile(): VirtualFile = file

    override fun dispose() {
        disposed = true
        generation.incrementAndGet()
        // Release pixel data and headers right away, even if something still holds on to this editor
        imageView.showMessage("")
        headerModel.rows = emptyList()
        hduCombo.model = DefaultComboBoxModel()
        synchronized(this) {
            tempCopy?.delete()
            tempCopy = null
        }
    }

    private class HeaderTableModel : AbstractTableModel() {
        var rows: List<HeaderRow> = emptyList()
            set(value) {
                field = value
                fireTableDataChanged()
            }

        override fun getRowCount() = rows.size

        override fun getColumnCount() = COLUMNS.size

        override fun getColumnName(column: Int) = COLUMNS[column]

        override fun getValueAt(row: Int, column: Int): Any = with(rows[row]) {
            when (column) {
                0 -> keyword
                1 -> value
                else -> comment
            }
        }
    }

    private companion object {
        val LOG = Logger.getInstance(FitsFileEditor::class.java)
        val COLUMNS = arrayOf("Keyword", "Value", "Comment")
        const val HEADER_TAB = 1
    }
}
