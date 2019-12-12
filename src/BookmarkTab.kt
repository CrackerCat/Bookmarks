package burp

import java.awt.FlowLayout
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.table.AbstractTableModel

class BookmarkTab(callbacks: IBurpExtenderCallbacks) : ITab {
    val bookmarkTable = BookmarksPanel(callbacks)

    override fun getTabCaption() = "[^]"

    override fun getUiComponent() = bookmarkTable.panel
}

data class Bookmark(
    val requestResponse: IHttpRequestResponse,
    val dateTime: String,
    val host: String,
    val url: URL,
    val method: String,
    val statusCode: String,
    val title: String,
    val mimeType: String,
    val protocol: String,
    val file: String,
    val parameters: String,
    val repeated: Boolean
)

class BookmarksPanel(private val callbacks: IBurpExtenderCallbacks) {
    val model = BookmarksModel()
    val table = JTable(model)

    private val messageEditor = MessageEditor(callbacks)
    private val requestViewer: IMessageEditor? = messageEditor.requestViewer
    private val responseViewer: IMessageEditor? = messageEditor.responseViewer

    val panel = JSplitPane(JSplitPane.VERTICAL_SPLIT)
    val bookmarks = model.bookmarks

    private val repeatInTable = JCheckBox("Add repeated request to table")

    init {
        BookmarkActions(this, bookmarks, callbacks)
        val bookmarkOptons = BookmarkOptions(this, callbacks)
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        table.columnModel.getColumn(0).preferredWidth = 30 // ID
        table.columnModel.getColumn(1).preferredWidth = 145 // date
        table.columnModel.getColumn(2).preferredWidth = 130 // host
        table.columnModel.getColumn(3).preferredWidth = 400 // url
        table.columnModel.getColumn(4).preferredWidth = 110 // title
        table.columnModel.getColumn(5).preferredWidth = 60 // repeated
        table.columnModel.getColumn(6).preferredWidth = 60 // method
        table.columnModel.getColumn(7).preferredWidth = 60 // status
        table.columnModel.getColumn(8).preferredWidth = 130 // parameters
        table.columnModel.getColumn(9).preferredWidth = 50 // mime
        table.columnModel.getColumn(10).preferredWidth = 50 // protocol
        table.columnModel.getColumn(11).preferredWidth = 80 // file

        table.selectionModel.addListSelectionListener {
            val requestResponse = bookmarks[table.selectedRow].requestResponse
            messageEditor.requestResponse = requestResponse
            requestViewer?.setMessage(requestResponse.request, true)
            responseViewer?.setMessage(requestResponse.response ?: ByteArray(0), false)
        }

        val repeatPanel = JPanel(FlowLayout(FlowLayout.LEFT))

        val repeatButton = JButton("Repeat Request")
        repeatButton.addActionListener {
            repeatRequest()
            repeatButton.isFocusPainted = false
        }

        repeatInTable.isSelected = true

        repeatPanel.add(repeatButton)
        repeatPanel.add(repeatInTable)

        val bookmarksTable = JScrollPane(table)
        val reqResSplit =
            JSplitPane(JSplitPane.HORIZONTAL_SPLIT, requestViewer?.component, responseViewer?.component)
        reqResSplit.resizeWeight = 0.5

        val repeatReqSplit =
            JSplitPane(JSplitPane.VERTICAL_SPLIT, repeatPanel, reqResSplit)

        val bookmarksOptSplit =
            JSplitPane(JSplitPane.VERTICAL_SPLIT, bookmarkOptons.panel, bookmarksTable)

        panel.topComponent = bookmarksOptSplit
        panel.bottomComponent = repeatReqSplit
        callbacks.customizeUiComponent(panel)
    }

    fun addBookmark(requestsResponses: Array<IHttpRequestResponse>) {
        for (requestResponse in requestsResponses) {
            createBookmark(requestResponse, false)
        }
    }

    private fun createBookmark(requestResponse: IHttpRequestResponse, repeated: Boolean = false) {
        val savdRequestResponse = callbacks.saveBuffersToTempFiles(requestResponse)
        val now = LocalDateTime.now()
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val dateTime = now.format(dateFormatter) ?: ""
        val requestInfo = callbacks.helpers.analyzeRequest(requestResponse)
        var response: IResponseInfo? = null
        requestResponse.response?.let {
            response = callbacks.helpers.analyzeResponse(requestResponse.response)
        }
        val host = requestInfo.url.host ?: ""
        val url = requestInfo.url
        val method = requestInfo.method ?: ""
        val statusCode = response?.statusCode?.toString() ?: ""
        val title = getTitle(requestResponse.response)
        val mimeType = response?.inferredMimeType ?: ""
        val protocol = requestInfo.url.protocol
        val file = requestInfo.url.file
        val parameters = requestInfo.parameters.joinToString(separator = ", ", limit = 5) { "${it.name}=${it.value}" }
        val bookmark = Bookmark(
            savdRequestResponse,
            dateTime,
            host,
            url,
            method,
            statusCode,
            title,
            mimeType,
            protocol,
            file,
            parameters,
            repeated
        )
        model.addBookmark(bookmark)
        requestResponse.highlight = "magenta"
        requestResponse.comment = "[^]"
    }

    private fun getTitle(response: ByteArray?): String {
        if (response == null) return ""
        val html = callbacks.helpers.bytesToString(response)
        val titleRegex = "<title>(.*?)</title>".toRegex()
        val title = titleRegex.find(html)?.value ?: ""
        return title.removePrefix("<title>").removeSuffix("</title>")
    }


    private fun repeatRequest() {
        Thread {
            val requestResponse = callbacks.makeHttpRequest(messageEditor.httpService, requestViewer?.message)
            responseViewer?.setMessage(requestResponse.response, false)
            if (repeatInTable.isSelected) {
                createBookmark(requestResponse, true)
            }
        }.start()
    }
}

class MessageEditor(callbacks: IBurpExtenderCallbacks) : IMessageEditorController {
    var requestResponse: IHttpRequestResponse? = null

    val requestViewer: IMessageEditor? = callbacks.createMessageEditor(this, true)
    val responseViewer: IMessageEditor? = callbacks.createMessageEditor(this, false)

    override fun getResponse(): ByteArray? = requestResponse?.response ?: ByteArray(0)

    override fun getRequest(): ByteArray? = requestResponse?.request

    override fun getHttpService(): IHttpService? = requestResponse?.httpService
}

class BookmarksModel : AbstractTableModel() {
    private val columns =
        listOf(
            "ID",
            "Added",
            "Host",
            "URL",
            "Title",
            "Repeated",
            "Method",
            "Status",
            "Parameters",
            "MIME",
            "Protocol",
            "File"
        )
    var bookmarks: MutableList<Bookmark> = ArrayList()

    override fun getRowCount(): Int = bookmarks.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String {
        return columns[column]
    }

    override fun getColumnClass(columnIndex: Int): Class<*> {
        return when (columnIndex) {
            0 -> java.lang.Integer::class.java
            1 -> String::class.java
            2 -> String::class.java
            3 -> String::class.java
            4 -> String::class.java
            5 -> java.lang.Boolean::class.java
            6 -> String::class.java
            7 -> String::class.java
            8 -> String::class.java
            9 -> String::class.java
            10 -> String::class.java
            11 -> String::class.java
            else -> throw RuntimeException()
        }
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val bookmark = bookmarks[rowIndex]

        return when (columnIndex) {
            0 -> rowIndex
            1 -> bookmark.dateTime
            2 -> bookmark.host
            3 -> bookmark.url.toString()
            4 -> bookmark.title
            5 -> bookmark.repeated
            6 -> bookmark.method
            7 -> bookmark.statusCode
            8 -> bookmark.parameters
            9 -> bookmark.mimeType
            10 -> bookmark.protocol
            11 -> bookmark.file
            else -> ""
        }
    }

    fun addBookmark(bookmark: Bookmark) {
        bookmarks.add(bookmark)
        fireTableRowsInserted(bookmarks.lastIndex, bookmarks.lastIndex)
    }

    fun removeBookmarks(selectedBookmarks: MutableList<Bookmark>) {
        bookmarks.removeAll(selectedBookmarks)
        fireTableDataChanged()
    }

    fun clearBookmarks() {
        bookmarks.clear()
        fireTableDataChanged()

    }
}


