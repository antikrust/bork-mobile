package online.borkbrowser.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.BasicSelectionActionDelegate
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ContentDelegate
import org.mozilla.geckoview.GeckoSession.HistoryDelegate
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.ProgressDelegate
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse

/**
 * The browser: one GeckoView, many sessions, grouped into workspaces.
 *
 * A tab owns a GeckoSession, created the first time the tab is shown (so a
 * restored set of forty tabs costs nothing until one is opened). The single
 * GeckoView is handed whichever session is current; the others are set
 * inactive, which is how Gecko throttles a background tab.
 */
class BrowserActivity : AppCompatActivity(), Addons.Host {

    private val app get() = BorkApplication.instance
    private val runtime get() = app.runtime
    private val settings get() = app.settings
    private val store get() = app.store

    private lateinit var tabStore: TabStore
    private val workspaces = mutableListOf<Workspace>()
    private val tabs = mutableListOf<Tab>()
    private var current: Tab? = null
    private lateinit var workspace: Workspace

    private lateinit var gecko: GeckoView
    private lateinit var urlField: EditText
    private lateinit var urlIcon: ImageView
    private lateinit var urlClear: ImageButton
    private lateinit var urlBox: View
    private lateinit var reload: ImageButton
    private lateinit var progress: LinearProgressIndicator
    private lateinit var wsBadge: TextView
    private lateinit var toolbar: View
    private lateinit var topBar: FrameLayout
    private lateinit var bottomBar: View
    private lateinit var bottomToolbarSlot: FrameLayout
    private lateinit var start: NestedScrollView
    private lateinit var tiles: RecyclerView
    private lateinit var startHint: View
    private lateinit var suggestions: RecyclerView
    private lateinit var tabCount: TextView
    private lateinit var navBack: ImageButton
    private lateinit var navForward: ImageButton
    private lateinit var findBar: View
    private lateinit var findText: EditText
    private lateinit var findCount: TextView
    private lateinit var zenExit: View

    private lateinit var prompts: Prompts
    private lateinit var downloads: Downloads
    private val main = Handler(Looper.getMainLooper())

    private var fullscreen = false
    private var zen = false

    private var pendingFiles: ((List<Uri>) -> Unit)? = null
    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val done = pendingFiles ?: return@registerForActivityResult
        pendingFiles = null
        val data = result.data
        val uris = mutableListOf<Uri>()
        if (result.resultCode == RESULT_OK && data != null) {
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri
            } else {
                data.data?.let { uris += it }
            }
        }
        done(uris)
    }

    private var pendingPermission: PermissionDelegate.Callback? = null
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val callback = pendingPermission ?: return@registerForActivityResult
            pendingPermission = null
            if (results.values.all { it }) callback.grant() else callback.reject()
        }

    /* =============================================================== lifecycle */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_browser)
        tabStore = TabStore(this)
        downloads = Downloads(this)
        prompts = Prompts(this) { mimes, multiple, done -> pickFiles(mimes, multiple, done) }

        bindViews()
        applyInsets()

        app.addons.host = this
        if (!addonsStarted) {
            addonsStarted = true
            app.addons.start(runtime)
        }
        seedBookmarks()
        restoreTabs()
        zen = settings.zen
        applyChrome()

        if (savedInstanceState == null) handleIntent(intent)
        onBackPressedDispatcher.addCallback(this, backHandler)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        app.addons.host = this
        refreshStart()
        updateUi()
    }

    override fun onPause() {
        super.onPause()
        current?.session?.flushSessionState()
        saveNow()
    }

    override fun onDestroy() {
        if (app.addons.host === this) app.addons.host = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        runtime.configurationChanged(newConfig)
    }

    /* =================================================================== views */

    private fun bindViews() {
        gecko = findViewById(R.id.gecko)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        bottomToolbarSlot = findViewById(R.id.bottomToolbarSlot)
        start = findViewById(R.id.start)
        tiles = findViewById(R.id.tiles)
        startHint = findViewById(R.id.startHint)
        suggestions = findViewById(R.id.suggestions)
        tabCount = findViewById(R.id.tabCount)
        navBack = findViewById(R.id.navBack)
        navForward = findViewById(R.id.navForward)
        findBar = findViewById(R.id.findBar)
        findText = findViewById(R.id.findText)
        findCount = findViewById(R.id.findCount)
        zenExit = findViewById(R.id.zenExit)

        toolbar = LayoutInflater.from(this).inflate(R.layout.toolbar, topBar, false)
        urlField = toolbar.findViewById(R.id.url)
        urlIcon = toolbar.findViewById(R.id.urlIcon)
        urlClear = toolbar.findViewById(R.id.urlClear)
        urlBox = toolbar.findViewById(R.id.urlBox)
        reload = toolbar.findViewById(R.id.reload)
        progress = toolbar.findViewById(R.id.progress)
        wsBadge = toolbar.findViewById(R.id.wsBadge)
        placeToolbar()

        gecko.coverUntilFirstPaint(ContextCompat.getColor(this, R.color.bork_bg))

        navBack.setOnClickListener { goBack() }
        navForward.setOnClickListener { current?.let { it.home = false; it.session?.goForward(); show(it) } }
        navHome.setOnClickListener { goHome() }
        findViewById<View>(R.id.navTabs).setOnClickListener { openTabTray() }
        findViewById<View>(R.id.navTabs).setOnLongClickListener { newTab(select = true); true }
        findViewById<View>(R.id.navMenu).setOnClickListener { openMenu() }
        wsBadge.setOnClickListener { openTabTray() }
        wsBadge.setOnLongClickListener { pickWorkspace(); true }
        zenExit.setOnClickListener { setZen(false) }

        reload.setOnClickListener {
            val tab = current ?: return@setOnClickListener
            if (tab.loading) tab.session?.stop() else tab.session?.reload()
        }

        findViewById<View>(R.id.startSearch).setOnClickListener { focusUrl() }
        tiles.layoutManager = GridLayoutManager(this, 4)

        setupUrlField()
        setupFind()
    }

    private val navHome: View get() = findViewById(R.id.navHome)

    /** The address bar at the top, or above the buttons at the bottom. */
    private fun placeToolbar() {
        (toolbar.parent as? ViewGroup)?.removeView(toolbar)
        if (settings.toolbarAtBottom) {
            bottomToolbarSlot.addView(toolbar)
            topBar.visibility = View.GONE
        } else {
            topBar.addView(toolbar)
            topBar.visibility = View.VISIBLE
        }
    }

    private fun applyInsets() {
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            if (fullscreen) {
                v.setPadding(0, 0, 0, 0)
            } else {
                v.setPadding(bars.left, if (zen) 0 else bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            }
            WindowInsetsCompat.CONSUMED
        }
    }

    /* ============================================================== url field */

    private val suggestionAdapter = SuggestionAdapter()

    private fun setupUrlField() {
        suggestions.layoutManager = LinearLayoutManager(this)
        suggestions.adapter = suggestionAdapter

        urlField.setOnFocusChangeListener { _, focused ->
            if (focused) {
                val tab = current
                urlField.setText(if (tab == null || tab.home) "" else tab.url)
                urlField.selectAll()
                suggestions.visibility = View.VISIBLE
                updateSuggestions("")
            } else {
                suggestions.visibility = View.GONE
                updateUi()
            }
            urlClear.visibility = if (focused) View.VISIBLE else View.GONE
        }
        urlField.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                submitUrl(urlField.text.toString())
                true
            } else {
                false
            }
        }
        urlField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (urlField.hasFocus()) updateSuggestions(s?.toString().orEmpty())
            }
        })
        urlClear.setOnClickListener { urlField.setText("") }
    }

    private fun focusUrl() {
        if (zen) setZen(false)
        urlField.requestFocus()
        val imm = getSystemService(InputMethodManager::class.java)
        imm.showSoftInput(urlField, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun clearUrlFocus() {
        urlField.clearFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(urlField.windowToken, 0)
    }

    private fun submitUrl(text: String) {
        val url = UrlInput.toUrl(text, settings.searchEngine)
        clearUrlFocus()
        if (url.isEmpty()) return
        val tab = current ?: newTab(select = true)
        load(tab, url)
    }

    private fun updateSuggestions(query: String) {
        val rows = mutableListOf<Suggestion>()
        val q = query.trim()
        if (q.isNotEmpty()) {
            val url = UrlInput.toUrl(q, settings.searchEngine)
            val engine = settings.searchEngine
            if (url != engine.urlFor(q)) rows += Suggestion(url, UrlInput.display(url), "Go to", R.drawable.ic_forward)
            rows += Suggestion(engine.urlFor(q), q, "Search ${engine.name}", R.drawable.ic_search)
            store.suggest(q).forEach { rows += Suggestion(it.url, it.title.ifBlank { UrlInput.display(it.url) }, UrlInput.display(it.url), R.drawable.ic_history) }
        } else {
            val tab = current
            if (tab != null && !tab.home && tab.url.isNotEmpty()) {
                rows += Suggestion(tab.url, "Copy this page's link", UrlInput.display(tab.url), R.drawable.ic_share, copy = true)
            }
            store.history(limit = 12).forEach { rows += Suggestion(it.url, it.title.ifBlank { UrlInput.display(it.url) }, UrlInput.display(it.url), R.drawable.ic_history) }
        }
        suggestionAdapter.submit(rows)
    }

    private data class Suggestion(
        val url: String,
        val title: String,
        val detail: String,
        val icon: Int,
        val copy: Boolean = false,
    )

    private inner class SuggestionAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Suggestion> = emptyList()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(next: List<Suggestion>) {
            rows = next
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val s = rows[position]
            val frame = holder.itemView as FrameLayout
            frame.removeAllViews()
            frame.addView(Ui.row(this@BrowserActivity, s.title, icon = s.icon, detail = s.detail) {
                if (s.copy) {
                    copyText(s.url)
                    clearUrlFocus()
                } else {
                    clearUrlFocus()
                    load(current ?: newTab(select = true), s.url)
                }
            })
        }
    }

    /* ==================================================================== find */

    private fun setupFind() {
        val run = { forward: Boolean ->
            val text = findText.text.toString()
            val session = current?.session
            if (session != null && text.isNotEmpty()) {
                val flags = if (forward) 0 else GeckoSession.FINDER_FIND_BACKWARDS
                session.finder.find(text, flags).accept { r ->
                    findCount.text = if (r == null || !r.found) "0/0" else "${r.current}/${r.total}"
                }
            }
        }
        findText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (s.isNullOrEmpty()) {
                    current?.session?.finder?.clear()
                    findCount.text = ""
                } else {
                    run(true)
                }
            }
        })
        findText.setOnEditorActionListener { _, _, _ -> run(true); true }
        findViewById<View>(R.id.findNext).setOnClickListener { run(true) }
        findViewById<View>(R.id.findPrev).setOnClickListener { run(false) }
        findViewById<View>(R.id.findClose).setOnClickListener { closeFind() }
    }

    private fun openFind() {
        if (current?.session == null || current?.home == true) return
        findBar.visibility = View.VISIBLE
        findText.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(findText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeFind() {
        current?.session?.finder?.clear()
        findText.setText("")
        findBar.visibility = View.GONE
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(findText.windowToken, 0)
    }

    /* =================================================================== tabs */

    private fun restoreTabs() {
        val snapshot = if (settings.restoreTabs) tabStore.load() else null
        if (snapshot != null && snapshot.workspaces.isNotEmpty()) {
            workspaces += snapshot.workspaces
            tabs += snapshot.tabs.filter { t -> workspaces.any { it.id == t.workspaceId } }
            workspace = workspaces.firstOrNull { it.id == snapshot.currentWorkspace } ?: workspaces.first()
        } else {
            workspace = Workspace(newId(), "Home")
            workspaces += workspace
        }
        val tab = tabsIn(workspace).firstOrNull { it.id == workspace.lastTabId }
            ?: tabsIn(workspace).maxByOrNull { it.lastUsed }
            ?: newTab(select = false)
        show(tab)
    }

    private fun newId() = java.util.UUID.randomUUID().toString()

    private fun tabsIn(ws: Workspace) = tabs.filter { it.workspaceId == ws.id }

    private fun workspaceOf(tab: Tab) = workspaces.firstOrNull { it.id == tab.workspaceId } ?: workspace

    private fun newTab(
        url: String? = null,
        select: Boolean = true,
        parent: Tab? = null,
        privateMode: Boolean = false,
    ): Tab {
        val tab = Tab(workspaceId = workspace.id, private = privateMode).apply {
            desktop = settings.desktopByDefault
            parentId = parent?.id
        }
        val index = parent?.let { tabs.indexOf(it) + 1 }?.takeIf { it > 0 } ?: tabs.size
        tabs.add(index, tab)
        if (url != null) load(tab, url, select = false)
        if (select) show(tab)
        updateUi()
        scheduleSave()
        return tab
    }

    private fun load(tab: Tab, url: String, select: Boolean = true) {
        val session = ensureSession(tab)
        tab.home = false
        tab.url = url
        session.loadUri(url)
        if (select && tab === current) show(tab)
        else updateUi()
    }

    private fun goHome() {
        val tab = current ?: return
        clearUrlFocus()
        closeFind()
        tab.home = true
        refreshStart()
        show(tab)
    }

    private fun goBack(): Boolean {
        val tab = current ?: return false
        if (tab.home && tab.session != null && tab.url.isNotEmpty()) {
            tab.home = false
            show(tab)
            return true
        }
        if (tab.canGoBack && !tab.home) {
            tab.session?.goBack()
            return true
        }
        // A tab a link opened goes back to where the link was.
        val parent = tab.parentId?.let { id -> tabs.firstOrNull { it.id == id } }
        if (parent != null) {
            closeTab(tab, selectInstead = parent)
            return true
        }
        return false
    }

    private fun ensureSession(tab: Tab): GeckoSession {
        tab.session?.let { return it }
        val session = newSession(tab)
        tab.session = session
        session.open(runtime)
        val state = tab.sessionState?.let { runCatching { GeckoSession.SessionState.fromString(it) }.getOrNull() }
        if (state != null) {
            session.restoreState(state)
        } else if (tab.url.isNotEmpty()) {
            session.loadUri(tab.url)
        }
        return session
    }

    private fun show(tab: Tab) {
        val previous = current
        if (previous != null && previous !== tab) {
            captureThumbnail(previous)
            previous.session?.let {
                it.setActive(false)
                // Add-ons ask "which tab is active?" (uBlock's popup does,
                // to know which site it is about); GeckoView only knows
                // what the app tells it.
                runtime.webExtensionController.setTabActive(it, false)
            }
            closeFind()
        }
        current = tab
        tab.lastUsed = System.currentTimeMillis()
        if (tab.workspaceId != workspace.id) {
            workspace = workspaceOf(tab)
        }
        workspace.lastTabId = tab.id

        if (!tab.home || tab.session != null) {
            val session = ensureSession(tab)
            attach(session)
            session.setActive(true)
            markActiveForAddons(session)
        } else {
            if (gecko.session != null) gecko.releaseSession()
        }
        refreshStart()
        updateUi()
        scheduleSave()
    }

    private fun markActiveForAddons(session: GeckoSession, tries: Int = 0) {
        if (!session.isOpen) {
            if (tries < 120) main.postDelayed({ if (current?.session === session) markActiveForAddons(session, tries + 1) }, 16)
            return
        }
        runtime.webExtensionController.setTabActive(session, true)
    }

    /**
     * A session GeckoView opened for us (a link's new window, an add-on's new
     * tab) is not open yet at the moment it is handed over; attaching it to
     * the view before then fails. Wait the few frames it takes.
     */
    private fun attach(session: GeckoSession, tries: Int = 0) {
        if (gecko.session === session) return
        if (!session.isOpen) {
            if (tries < 120) main.postDelayed({ if (current?.session === session) attach(session, tries + 1) }, 16)
            return
        }
        if (gecko.session != null) gecko.releaseSession()
        gecko.setSession(session)
    }

    private fun closeTab(tab: Tab, selectInstead: Tab? = null) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        if (gecko.session === tab.session) gecko.releaseSession()
        tab.session?.close()
        tab.session = null
        tabs.removeAt(index)
        if (current === tab) {
            current = null
            val next = selectInstead?.takeIf { it in tabs }
                ?: tabsIn(workspace).maxByOrNull { it.lastUsed }
                ?: newTab(select = false)
            show(next)
        }
        updateUi()
        scheduleSave()
    }

    private fun closeTabs(which: List<Tab>) {
        which.toList().forEach { t ->
            if (gecko.session === t.session) gecko.releaseSession()
            t.session?.close()
            t.session = null
            tabs.remove(t)
        }
        if (current !in tabs) {
            current = null
            show(tabsIn(workspace).maxByOrNull { it.lastUsed } ?: newTab(select = false))
        }
        updateUi()
        scheduleSave()
    }

    private fun tabFor(session: GeckoSession): Tab? = tabs.firstOrNull { it.session === session }

    private fun captureThumbnail(tab: Tab) {
        val session = tab.session ?: return
        if (tab.home || gecko.session !== session || gecko.width == 0) return
        gecko.capturePixels().accept({ bitmap ->
            if (bitmap == null) return@accept
            val w = 360
            val h = (bitmap.height * w.toFloat() / bitmap.width).toInt().coerceAtLeast(1)
            tab.thumbnail = Bitmap.createScaledBitmap(bitmap, w, h, true)
            if (bitmap != tab.thumbnail) bitmap.recycle()
        }, { })
    }

    /* ============================================================ workspaces */

    fun switchWorkspace(ws: Workspace) {
        if (ws === workspace) return
        workspace = ws
        val tab = tabsIn(ws).firstOrNull { it.id == ws.lastTabId }
            ?: tabsIn(ws).maxByOrNull { it.lastUsed }
            ?: newTab(select = false)
        show(tab)
        Toast.makeText(this, ws.name, Toast.LENGTH_SHORT).show()
    }

    private fun pickWorkspace() {
        val names = workspaces.map { it.name } + "New workspace…"
        MaterialAlertDialogBuilder(this)
            .setTitle("Workspaces")
            .setItems(names.toTypedArray()) { _, which ->
                if (which < workspaces.size) switchWorkspace(workspaces[which]) else createWorkspace()
            }
            .show()
    }

    fun createWorkspace(after: ((Workspace) -> Unit)? = null) {
        val input = EditText(this).apply { hint = "Name" }
        MaterialAlertDialogBuilder(this)
            .setTitle("New workspace")
            .setView(LinearLayout(this).apply {
                setPadding(Ui.dp(context, 20), Ui.dp(context, 8), Ui.dp(context, 20), 0)
                addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            })
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Workspace ${workspaces.size + 1}" }
                val ws = Workspace(newId(), name)
                workspaces += ws
                switchWorkspace(ws)
                after?.invoke(ws)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun renameWorkspace(ws: Workspace, after: () -> Unit) {
        val input = EditText(this).apply { setText(ws.name); selectAll() }
        MaterialAlertDialogBuilder(this)
            .setTitle("Rename workspace")
            .setView(LinearLayout(this).apply {
                setPadding(Ui.dp(context, 20), Ui.dp(context, 8), Ui.dp(context, 20), 0)
                addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            })
            .setPositiveButton("Rename") { _, _ ->
                input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { ws.name = it }
                updateUi()
                scheduleSave()
                after()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun deleteWorkspace(ws: Workspace, after: () -> Unit) {
        if (workspaces.size <= 1) {
            Toast.makeText(this, "The last workspace stays", Toast.LENGTH_SHORT).show()
            return
        }
        val count = tabsIn(ws).size
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${ws.name}?")
            .setMessage(if (count == 0) "It has no tabs." else "Its $count tab${if (count == 1) "" else "s"} will close.")
            .setPositiveButton("Delete") { _, _ ->
                val wasCurrent = ws === workspace
                closeTabsSilently(tabsIn(ws))
                workspaces.remove(ws)
                if (wasCurrent) {
                    workspace = workspaces.first()
                    current = null
                    show(tabsIn(workspace).maxByOrNull { it.lastUsed } ?: newTab(select = false))
                }
                updateUi()
                scheduleSave()
                after()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun closeTabsSilently(which: List<Tab>) {
        which.forEach { t ->
            if (gecko.session === t.session) gecko.releaseSession()
            t.session?.close()
            t.session = null
            tabs.remove(t)
        }
    }

    fun moveTab(tab: Tab, to: Workspace) {
        if (tab.workspaceId == to.id) return
        val wasCurrent = tab === current
        tab.workspaceId = to.id
        if (wasCurrent) {
            current = null
            show(tabsIn(workspace).maxByOrNull { it.lastUsed } ?: newTab(select = false))
        }
        updateUi()
        scheduleSave()
        Toast.makeText(this, "Moved to ${to.name}", Toast.LENGTH_SHORT).show()
    }

    private fun openTabTray() {
        clearUrlFocus()
        current?.let(::captureThumbnail)
        // Thumbnails arrive asynchronously; a frame's grace lets the current
        // tab's land before the grid is drawn.
        main.postDelayed({
            TabTray(this, object : TabTray.Callbacks {
                override val workspaces get() = this@BrowserActivity.workspaces
                override val workspace get() = this@BrowserActivity.workspace
                override val current get() = this@BrowserActivity.current
                override fun tabsIn(ws: Workspace) = this@BrowserActivity.tabsIn(ws)
                override fun select(tab: Tab) = show(tab)
                override fun close(tab: Tab) = closeTab(tab)
                override fun closeAll(ws: Workspace) = closeTabs(tabsIn(ws))
                override fun newTab(private: Boolean) {
                    newTab(select = true, privateMode = private)
                    focusUrl()
                }
                override fun switchWorkspace(ws: Workspace) = this@BrowserActivity.switchWorkspace(ws)
                override fun createWorkspace(after: (Workspace) -> Unit) = this@BrowserActivity.createWorkspace(after)
                override fun renameWorkspace(ws: Workspace, after: () -> Unit) = this@BrowserActivity.renameWorkspace(ws, after)
                override fun deleteWorkspace(ws: Workspace, after: () -> Unit) = this@BrowserActivity.deleteWorkspace(ws, after)
                override fun moveTab(tab: Tab, to: Workspace) = this@BrowserActivity.moveTab(tab, to)
            }).show()
        }, 60)
    }

    /* ================================================================ sessions */

    private fun newSession(tab: Tab): GeckoSession {
        val sessionSettings = GeckoSessionSettings.Builder()
            .usePrivateMode(tab.private)
            .useTrackingProtection(true)
            .suspendMediaWhenInactive(false)
            .allowJavascript(settings.javascript)
            .userAgentMode(if (tab.desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(if (tab.desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .build()
        return GeckoSession(sessionSettings).apply {
            navigationDelegate = Navigation(tab)
            progressDelegate = Progress(tab)
            contentDelegate = Content(tab)
            historyDelegate = History(tab)
            permissionDelegate = permissions
            promptDelegate = prompts
            selectionActionDelegate = BasicSelectionActionDelegate(this@BrowserActivity)
            app.addons.attachTo(this)
        }
    }

    private inner class Navigation(private val tab: Tab) : NavigationDelegate {
        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: MutableList<PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            tab.url = url.orEmpty()
            if (tab === current && !urlField.hasFocus()) updateUi()
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            tab.canGoBack = canGoBack
            if (tab === current) updateUi()
        }

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            tab.canGoForward = canGoForward
            if (tab === current) updateUi()
        }

        override fun onLoadRequest(session: GeckoSession, request: NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
            val uri = Uri.parse(request.uri)
            val scheme = uri.scheme?.lowercase() ?: return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            if (scheme in UrlInput.GECKO_SCHEMES) return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            openExternally(request.uri, session)
            return GeckoResult.fromValue(AllowOrDeny.DENY)
        }

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
            val child = Tab(workspaceId = tab.workspaceId, private = tab.private).apply {
                parentId = tab.id
                desktop = tab.desktop
                home = false
                url = uri
            }
            val childSession = newSession(child)
            child.session = childSession
            tabs.add((tabs.indexOf(tab) + 1).coerceAtLeast(0), child)
            main.post { show(child) }
            return GeckoResult.fromValue(childSession)
        }

        override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String> =
            GeckoResult.fromValue(ErrorPage.dataUri(uri.orEmpty(), error))
    }

    private inner class Progress(private val tab: Tab) : ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            tab.loading = true
            tab.progress = 5
            if (tab === current) updateUi()
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            tab.loading = false
            tab.progress = 100
            if (tab === current) {
                updateUi()
                main.postDelayed({ if (tab === current) captureThumbnail(tab) }, 400)
            }
            scheduleSave()
        }

        override fun onProgressChange(session: GeckoSession, progress: Int) {
            tab.progress = progress
            if (tab === current) updateProgress()
        }

        override fun onSecurityChange(session: GeckoSession, securityInfo: ProgressDelegate.SecurityInformation) {
            tab.secure = securityInfo.isSecure
            if (tab === current) updateUi()
        }

        override fun onSessionStateChange(session: GeckoSession, sessionState: GeckoSession.SessionState) {
            if (!tab.private) tab.sessionState = sessionState.toString()
            scheduleSave()
        }
    }

    private inner class Content(private val tab: Tab) : ContentDelegate {
        override fun onTitleChange(session: GeckoSession, title: String?) {
            tab.title = title.orEmpty()
            if (!tab.private) store.setTitle(tab.url, tab.title)
            if (tab === current) updateUi()
        }

        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
            if (tab === current) setFullscreen(fullScreen)
        }

        override fun onCloseRequest(session: GeckoSession) {
            closeTab(tab)
        }

        override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: ContentDelegate.ContextElement) {
            showContextMenu(tab, element)
        }

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            handleDownload(tab, response)
        }

        override fun onCrash(session: GeckoSession) = recover(tab, session)

        override fun onKill(session: GeckoSession) = recover(tab, session)
    }

    /** A crashed or killed content process: start the tab over from its saved state. */
    private fun recover(tab: Tab, session: GeckoSession) {
        if (gecko.session === session) gecko.releaseSession()
        runCatching { session.close() }
        tab.session = null
        tab.loading = false
        if (tab === current) show(tab)
    }

    private inner class History(private val tab: Tab) : HistoryDelegate {
        override fun onVisited(session: GeckoSession, url: String, lastVisitedURL: String?, flags: Int): GeckoResult<Boolean> {
            val topLevel = flags and HistoryDelegate.VISIT_TOP_LEVEL != 0
            val redirect = flags and (HistoryDelegate.VISIT_REDIRECT_SOURCE or HistoryDelegate.VISIT_REDIRECT_SOURCE_PERMANENT) != 0
            val error = flags and HistoryDelegate.VISIT_UNRECOVERABLE_ERROR != 0
            if (tab.private || !topLevel || redirect || error) return GeckoResult.fromValue(false)
            store.recordVisit(url)
            return GeckoResult.fromValue(true)
        }

        override fun getVisited(session: GeckoSession, urls: Array<String>): GeckoResult<BooleanArray> =
            GeckoResult.fromValue(store.visited(urls))
    }

    private val permissions = object : PermissionDelegate {
        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<String>?,
            callback: PermissionDelegate.Callback,
        ) {
            val needed = permissions.orEmpty().filter {
                ContextCompat.checkSelfPermission(this@BrowserActivity, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isEmpty()) return callback.grant()
            pendingPermission?.reject()
            pendingPermission = callback
            permissionLauncher.launch(needed.toTypedArray())
        }

        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: PermissionDelegate.ContentPermission,
        ): GeckoResult<Int> {
            val allow = PermissionDelegate.ContentPermission.VALUE_ALLOW
            val deny = PermissionDelegate.ContentPermission.VALUE_DENY
            val what = when (perm.permission) {
                PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
                PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> return GeckoResult.fromValue(allow)
                // Firefox for Android's default: video may start silent,
                // never with sound until the user asks for it.
                PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> return GeckoResult.fromValue(deny)
                PermissionDelegate.PERMISSION_TRACKING -> return GeckoResult.fromValue(deny)
                PermissionDelegate.PERMISSION_GEOLOCATION -> "know your location"
                PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> "send notifications"
                PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> "play DRM-protected media"
                PermissionDelegate.PERMISSION_STORAGE_ACCESS -> "use its cookies on ${UrlInput.host(perm.thirdPartyOrigin ?: "")}"
                PermissionDelegate.PERMISSION_XR -> "use virtual reality"
                PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS,
                PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS -> "reach devices on your network"
                else -> "use a device feature"
            }
            val result = GeckoResult<Int>()
            MaterialAlertDialogBuilder(this@BrowserActivity)
                .setTitle(UrlInput.host(perm.uri).ifEmpty { perm.uri })
                .setMessage("Allow this site to $what?")
                .setPositiveButton("Allow") { _, _ -> result.complete(allow) }
                .setNegativeButton("Block") { _, _ -> result.complete(deny) }
                .setOnCancelListener { result.complete(deny) }
                .show()
            return result
        }

        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            video: Array<PermissionDelegate.MediaSource>?,
            audio: Array<PermissionDelegate.MediaSource>?,
            callback: PermissionDelegate.MediaCallback,
        ) {
            val wants = listOfNotNull(
                video?.takeIf { it.isNotEmpty() }?.let { "camera" },
                audio?.takeIf { it.isNotEmpty() }?.let { "microphone" },
            ).joinToString(" and ")
            MaterialAlertDialogBuilder(this@BrowserActivity)
                .setTitle(UrlInput.host(uri).ifEmpty { uri })
                .setMessage("Allow this site to use your $wants?")
                .setPositiveButton("Allow") { _, _ -> callback.grant(video?.firstOrNull(), audio?.firstOrNull()) }
                .setNegativeButton("Block") { _, _ -> callback.reject() }
                .setOnCancelListener { callback.reject() }
                .show()
        }
    }

    private fun openExternally(uri: String, session: GeckoSession) {
        try {
            val intent = if (uri.startsWith("intent:")) {
                Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE)
            }
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                val fallback = intent.getStringExtra("browser_fallback_url")
                if (fallback != null && (fallback.startsWith("https://") || fallback.startsWith("http://"))) {
                    session.loadUri(fallback)
                } else {
                    Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not open $uri", e)
        }
    }

    /* ============================================================== downloads */

    private fun handleDownload(tab: Tab, response: WebResponse) {
        val mime = downloads.mimeOf(response)
        if (mime == "application/x-xpinstall" || response.uri.substringBefore('?').endsWith(".xpi")) {
            runCatching { response.body?.close() }
            installAddon(response.uri)
            return
        }
        val name = downloads.fileName(response)
        MaterialAlertDialogBuilder(this)
            .setTitle("Download file?")
            .setMessage("$name\n${UrlInput.host(response.uri)}")
            .setPositiveButton("Download") { _, _ ->
                Toast.makeText(this, "Downloading $name", Toast.LENGTH_SHORT).show()
                downloads.save(response) { done ->
                    if (done == null) {
                        Toast.makeText(this, "Download failed: $name", Toast.LENGTH_LONG).show()
                    } else {
                        com.google.android.material.snackbar.Snackbar
                            .make(findViewById(R.id.content), "Downloaded ${done.name}", 6000)
                            .setAction("Open") { openDownloaded(done) }
                            .show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> runCatching { response.body?.close() } }
            .setOnCancelListener { runCatching { response.body?.close() } }
            .show()
        if (tab.url.isEmpty() && tab.title.isEmpty() && tab.parentId != null) {
            // A tab opened only to start a download has nothing left to show.
            closeTab(tab)
        }
    }

    private fun openDownloaded(done: Downloads.Done) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(done.uri, done.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app opens ${done.mime}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun installAddon(url: String) {
        Toast.makeText(this, "Fetching add-on…", Toast.LENGTH_SHORT).show()
        app.addons.install(url).accept({ ext ->
            if (ext != null) Toast.makeText(this, "${ext.metaData.name} installed", Toast.LENGTH_SHORT).show()
        }, { e ->
            Log.w(TAG, "add-on install failed", e)
            Toast.makeText(this, "Add-on not installed", Toast.LENGTH_SHORT).show()
        })
    }

    /* ========================================================== context menu */

    private fun showContextMenu(tab: Tab, element: ContentDelegate.ContextElement) {
        val link = element.linkUri
        val src = element.srcUri
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (link != null) {
            items += "Open in new tab" to { newTab(link, select = false, parent = tab, privateMode = tab.private); toastTab() }
            items += "Open in private tab" to { newTab(link, select = true, privateMode = true) }
            items += "Copy link" to { copyText(link) }
            items += "Share link" to { share(link, element.linkText) }
            items += "Download link" to { tab.session?.let { downloadUrl(link, it) } }
        }
        if (src != null && element.type != ContentDelegate.ContextElement.TYPE_NONE) {
            val kind = when (element.type) {
                ContentDelegate.ContextElement.TYPE_IMAGE -> "image"
                ContentDelegate.ContextElement.TYPE_VIDEO -> "video"
                else -> "audio"
            }
            items += "Open $kind in new tab" to { newTab(src, select = true, parent = tab, privateMode = tab.private) }
            items += "Copy $kind address" to { copyText(src) }
            items += "Download $kind" to { tab.session?.let { downloadUrl(src, it) } }
        }
        if (items.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(link ?: src)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun toastTab() = Toast.makeText(this, "Opened in a new tab", Toast.LENGTH_SHORT).show()

    /**
     * Downloads go through the page's own session so they carry its cookies:
     * a hidden link with the download attribute, clicked in the page.
     */
    private fun downloadUrl(url: String, session: GeckoSession) {
        val escaped = url.replace("\\", "\\\\").replace("'", "\\'")
        session.loadUri(
            "javascript:(function(){var a=document.createElement('a');a.href='$escaped';" +
                "a.download='';a.rel='noopener';document.body.appendChild(a);a.click();a.remove();})()",
        )
    }

    private fun copyText(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("link", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun share(url: String, title: String? = null) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            if (!title.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
        }
        startActivity(Intent.createChooser(send, null))
    }

    /* =================================================================== menu */

    private fun openMenu() {
        clearUrlFocus()
        val tab = current
        val dialog = BottomSheetDialog(this)
        val list = Ui.column(this)
        val onPage = tab != null && !tab.home && tab.url.isNotEmpty()

        // Quick actions across the top.
        val quick = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Ui.dp(context, 12), Ui.dp(context, 8), Ui.dp(context, 12), Ui.dp(context, 4))
        }
        fun quickButton(icon: Int, label: String, enabled: Boolean = true, action: () -> Unit) {
            quick.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, Ui.dp(context, 64), 1f)
                alpha = if (enabled) 1f else 0.35f
                isEnabled = enabled
                background = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background)
                addView(ImageView(context).apply {
                    setImageResource(icon)
                    setColorFilter(Ui.fg(context))
                    layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 22), Ui.dp(context, 22))
                })
                addView(TextView(context).apply {
                    text = label
                    gravity = android.view.Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    textSize = 12f
                    setTextColor(Ui.dim(context))
                    setPadding(0, Ui.dp(context, 4), 0, 0)
                })
                setOnClickListener {
                    if (enabled) {
                        dialog.dismiss()
                        action()
                    }
                }
            })
        }
        val starred = onPage && store.isBookmarked(tab!!.url)
        quickButton(if (starred) R.drawable.ic_star_filled else R.drawable.ic_star, if (starred) "Pinned" else "Pin", onPage) {
            toggleBookmark(tab!!)
        }
        quickButton(R.drawable.ic_share, "Share", onPage) { share(tab!!.url, tab.title) }
        quickButton(R.drawable.ic_find, "Find", onPage) { openFind() }
        quickButton(R.drawable.ic_reload, "Reload", onPage) { tab!!.session?.reload() }
        list.addView(quick)
        list.addView(Ui.divider(this))

        list.addView(Ui.row(this, "New tab", R.drawable.ic_add) { dialog.dismiss(); newTab(select = true); focusUrl() })
        list.addView(Ui.row(this, "New private tab", R.drawable.ic_private) {
            dialog.dismiss(); newTab(select = true, privateMode = true); focusUrl()
        })
        list.addView(Ui.switchRow(this, "Zen mode", "Only the page. Tap the dot to come back.", zen) {
            dialog.dismiss(); setZen(it)
        })
        if (tab != null) {
            list.addView(Ui.switchRow(this, "Desktop site", null, tab.desktop) {
                dialog.dismiss(); setDesktop(tab, it)
            })
        }
        list.addView(Ui.divider(this))

        // Every add-on with a toolbar button: uBlock Origin's popup, Dark
        // Reader's switch, YoutubeDaddy's quick settings.
        val actions = app.addons.withActions
        if (actions.isNotEmpty()) {
            list.addView(Ui.header(this, "Add-ons"))
            for (ext in actions) {
                val action = app.addons.action(ext.id)
                val title = action?.title ?: ext.metaData.name ?: ext.id
                val row = Ui.row(this, title, R.drawable.ic_extension, detail = action?.badgeText?.takeIf { it.isNotBlank() }) {
                    dialog.dismiss()
                    app.addons.click(ext)
                }
                list.addView(row)
                action?.icon?.getBitmap(Ui.dp(this, 22))?.accept { bmp ->
                    if (bmp != null) ((row.getChildAt(0) as? ImageView))?.apply { clearColorFilter(); setImageBitmap(bmp) }
                }
            }
            list.addView(Ui.divider(this))
        }

        list.addView(Ui.row(this, "Bookmarks & history", R.drawable.ic_history) {
            dialog.dismiss(); startActivityForResult(Intent(this, LibraryActivity::class.java), REQ_LIBRARY)
        })
        list.addView(Ui.row(this, "Add-ons", R.drawable.ic_extension) {
            dialog.dismiss(); startActivity(Intent(this, AddonsActivity::class.java))
        })
        list.addView(Ui.row(this, "Settings", R.drawable.ic_settings) {
            dialog.dismiss(); startActivityForResult(Intent(this, SettingsActivity::class.java), REQ_SETTINGS)
        })

        dialog.setContentView(NestedScrollView(this).apply { addView(list) })
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }

    @Deprecated("Activity results for the two in-app screens")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_LIBRARY -> data?.getStringExtra(LibraryActivity.EXTRA_URL)?.let { url ->
                val tab = current
                if (tab != null && tab.home && tab.session == null) load(tab, url) else newTab(url)
            }
            REQ_SETTINGS -> {
                placeToolbar()
                if (data?.getBooleanExtra(SettingsActivity.EXTRA_CLEARED, false) == true) {
                    // Everything stored is gone, so are the tabs that showed it.
                    closeTabsSilently(tabs.toList())
                    current = null
                    show(newTab(select = false))
                }
                refreshStart()
                updateUi()
            }
        }
    }

    private fun toggleBookmark(tab: Tab) {
        if (store.isBookmarked(tab.url)) {
            store.removeBookmark(tab.url)
            Toast.makeText(this, "Unpinned from the start page", Toast.LENGTH_SHORT).show()
        } else {
            store.addBookmark(tab.url, tab.title.ifBlank { UrlInput.host(tab.url) })
            Toast.makeText(this, "Pinned to the start page", Toast.LENGTH_SHORT).show()
        }
        refreshStart()
    }

    private fun setDesktop(tab: Tab, desktop: Boolean) {
        tab.desktop = desktop
        tab.session?.settings?.apply {
            userAgentMode = if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            viewportMode = if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        }
        // m.youtube.com and friends redirect by user agent, so a plain reload
        // of the mobile URL would just come back mobile. Reload the address
        // without the m. host for desktop.
        val url = tab.url
        val swapped = when {
            desktop && Regex("^https?://m\\.").containsMatchIn(url) -> url.replaceFirst("://m.", "://www.")
            else -> null
        }
        if (swapped != null) tab.session?.loadUri(swapped) else tab.session?.reload()
        scheduleSave()
    }

    /* ========================================================== zen/fullscreen */

    private fun setZen(on: Boolean) {
        zen = on
        settings.zen = on
        applyChrome()
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        applyChrome()
    }

    private fun applyChrome() {
        val hideBars = zen || fullscreen
        topBar.visibility = if (hideBars || settings.toolbarAtBottom) View.GONE else View.VISIBLE
        bottomBar.visibility = if (hideBars) View.GONE else View.VISIBLE
        zenExit.visibility = if (zen && !fullscreen) View.VISIBLE else View.GONE
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (fullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else if (zen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.show(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        ViewCompat.requestApplyInsets(findViewById(R.id.root))
    }

    /* ============================================================ start page */

    private fun refreshStart() {
        val tab = current
        val showStart = tab == null || tab.home || (tab.session == null && tab.url.isEmpty())
        start.visibility = if (showStart) View.VISIBLE else View.GONE
        gecko.visibility = if (showStart) View.INVISIBLE else View.VISIBLE
        if (!showStart) return
        val sites = store.bookmarks()
        startHint.visibility = if (sites.isEmpty()) View.VISIBLE else View.GONE
        tiles.adapter = TileAdapter(sites)
    }

    private inner class TileAdapter(private val sites: List<Site>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = sites.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val cell = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 8))
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            return object : RecyclerView.ViewHolder(cell) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val site = sites[position]
            val cell = holder.itemView as LinearLayout
            cell.removeAllViews()
            val host = UrlInput.host(site.url)
            cell.addView(Ui.letterTile(cell.context, site.title.ifBlank { host }, host, 52))
            cell.addView(TextView(cell.context).apply {
                text = site.title.ifBlank { host }
                textSize = 12f
                setTextColor(Ui.fg(context))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = android.view.Gravity.CENTER
                setPadding(Ui.dp(context, 4), Ui.dp(context, 6), Ui.dp(context, 4), 0)
            })
            cell.setOnClickListener { current?.let { load(it, site.url) } }
            cell.setOnLongClickListener { editTile(site); true }
        }
    }

    private fun editTile(site: Site) {
        val actions = listOf("Open in new tab", "Rename", "Move left", "Move right", "Unpin")
        MaterialAlertDialogBuilder(this)
            .setTitle(site.title.ifBlank { site.url })
            .setItems(actions.toTypedArray()) { _, which ->
                when (which) {
                    0 -> newTab(site.url)
                    1 -> {
                        val input = EditText(this).apply { setText(site.title) }
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Rename")
                            .setView(LinearLayout(this).apply {
                                setPadding(Ui.dp(context, 20), Ui.dp(context, 8), Ui.dp(context, 20), 0)
                                addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                            })
                            .setPositiveButton("Save") { _, _ ->
                                store.renameBookmark(site.url, input.text.toString().trim())
                                refreshStart()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                    2 -> { store.moveBookmark(site.url, -1); refreshStart() }
                    3 -> { store.moveBookmark(site.url, 1); refreshStart() }
                    4 -> { store.removeBookmark(site.url); refreshStart() }
                }
            }
            .show()
    }

    private fun seedBookmarks() {
        if (settings.seeded) return
        settings.seeded = true
        if (store.bookmarks().isNotEmpty()) return
        listOf(
            "https://m.youtube.com/" to "YouTube",
            "https://en.m.wikipedia.org/" to "Wikipedia",
            "https://duckduckgo.com/" to "DuckDuckGo",
            "https://www.openstreetmap.org/" to "OpenStreetMap",
            "https://borkbrowser.online/" to "bork",
        ).forEach { (url, title) -> store.addBookmark(url, title) }
    }

    /* ==================================================================== ui */

    private fun updateUi() {
        val tab = current ?: return
        if (!urlField.hasFocus()) {
            urlField.setText(if (tab.home) "" else displayUrl(tab))
        }
        val icon = when {
            tab.home || tab.url.isEmpty() -> R.drawable.ic_search
            tab.private -> R.drawable.ic_private
            tab.url.startsWith("moz-extension://") -> R.drawable.ic_extension
            tab.secure -> R.drawable.ic_lock
            else -> R.drawable.ic_search
        }
        urlIcon.setImageResource(icon)
        urlBox.setBackgroundResource(if (tab.private) R.drawable.bg_field_private else R.drawable.bg_field)
        reload.setImageResource(if (tab.loading && !tab.home) R.drawable.ic_close else R.drawable.ic_reload)
        reload.isEnabled = !tab.home && tab.session != null
        reload.alpha = if (reload.isEnabled) 1f else 0.35f

        val back = (tab.canGoBack && !tab.home) || (tab.home && tab.session != null && tab.url.isNotEmpty()) || tab.parentId != null
        navBack.isEnabled = back
        navBack.alpha = if (back) 1f else 0.35f
        val forward = tab.canGoForward && !tab.home
        navForward.isEnabled = forward
        navForward.alpha = if (forward) 1f else 0.35f

        val count = tabsIn(workspace).size
        tabCount.text = if (count > 99) ":D" else count.toString()
        wsBadge.text = workspace.name.trim().firstOrNull()?.uppercase() ?: "•"
        wsBadge.visibility = if (workspaces.size > 1) View.VISIBLE else View.GONE
        updateProgress()
        refreshStart()
    }

    /**
     * An add-on's own pages (settings, uBlock's dashboard) show as the
     * add-on's name, not as moz-extension:// and a random UUID.
     */
    private fun displayUrl(tab: Tab): String {
        if (tab.url.startsWith("moz-extension://")) {
            val ext = app.addons.all.firstOrNull { e ->
                e.metaData.baseUrl?.let { tab.url.startsWith(it) } == true
            }
            if (ext != null) {
                val name = ext.metaData.name ?: ext.id
                return if (tab.title.isNotBlank() && !tab.title.startsWith(name)) "$name · ${tab.title}" else tab.title.ifBlank { name }
            }
        }
        return UrlInput.display(tab.url)
    }

    private fun updateProgress() {
        val tab = current ?: return
        if (tab.loading && !tab.home && tab.progress < 100) {
            progress.visibility = View.VISIBLE
            progress.setProgressCompat(tab.progress.coerceAtLeast(5), true)
        } else {
            progress.visibility = View.INVISIBLE
        }
    }

    /* ================================================================ saving */

    private val saveRunnable = Runnable { saveNow() }

    private fun scheduleSave() {
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, 1500)
    }

    private fun saveNow() {
        main.removeCallbacks(saveRunnable)
        if (!::workspace.isInitialized) return
        if (settings.restoreTabs) tabStore.save(workspaces, tabs, workspace.id) else tabStore.clear()
    }

    /* =============================================================== intents */

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val url: String? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                Regex("https?://\\S+").find(text)?.value ?: UrlInput.toUrl(text, settings.searchEngine)
            }
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra("query")?.let { settings.searchEngine.urlFor(it) }
            else -> null
        }
        if (url.isNullOrBlank()) return
        val tab = current
        if (tab != null && tab.home && tab.session == null) load(tab, url) else newTab(url)
    }

    private fun pickFiles(mimes: Array<String>, multiple: Boolean, done: (List<Uri>) -> Unit) {
        pendingFiles?.invoke(emptyList())
        pendingFiles = done
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (mimes.size == 1) mimes[0] else "*/*"
            if (mimes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimes)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        }
        try {
            filePicker.launch(Intent.createChooser(intent, null))
        } catch (e: ActivityNotFoundException) {
            pendingFiles = null
            done(emptyList())
        }
    }

    /* ================================================================== back */

    private val backHandler = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                urlField.hasFocus() -> clearUrlFocus()
                findBar.visibility == View.VISIBLE -> closeFind()
                fullscreen -> current?.session?.exitFullScreen()
                goBack() -> Unit
                else -> moveTaskToBack(true)
            }
        }
    }

    /* =========================================================== Addons.Host */

    override fun openPopup(extension: WebExtension, title: String): GeckoSession? {
        val session = GeckoSession(GeckoSessionSettings.Builder().build())
        val view = GeckoView(this)
        val dialog = BottomSheetDialog(this)
        session.promptDelegate = prompts
        session.contentDelegate = object : ContentDelegate {
            override fun onCloseRequest(session: GeckoSession) = dialog.dismiss()
        }
        session.navigationDelegate = object : NavigationDelegate {
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
                dialog.dismiss()
                val tab = newTab(select = false)
                val s = newSession(tab).also { tab.session = it; tab.home = false; tab.url = uri }
                main.post { show(tab) }
                return GeckoResult.fromValue(s)
            }
        }
        session.open(runtime)
        view.setSession(session)

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(Ui.fg(context))
            setPadding(Ui.dp(context, 20), Ui.dp(context, 14), Ui.dp(context, 20), Ui.dp(context, 8))
        })
        val height = (resources.displayMetrics.heightPixels * 0.72f).toInt()
        box.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
        dialog.setContentView(box)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        // The popup is a page; dragging it must scroll the page, not the sheet.
        dialog.behavior.isDraggable = false
        dialog.setOnDismissListener {
            view.releaseSession()
            session.close()
        }
        dialog.show()
        return session
    }

    override fun newTabFromAddon(url: String?, active: Boolean): GeckoSession? {
        val tab = Tab(workspaceId = workspace.id).apply {
            home = false
            this.url = url.orEmpty()
        }
        val session = newSession(tab)
        tab.session = session
        tabs.add(tab)
        if (active) main.post { show(tab) } else updateUi()
        scheduleSave()
        return session
    }

    override fun openUrlInNewTab(url: String) {
        newTab(url, select = true)
    }

    override fun confirmInstall(extension: WebExtension, permissions: List<String>): GeckoResult<Boolean> {
        val result = GeckoResult<Boolean>()
        val name = extension.metaData.name ?: extension.id
        val readable = permissions.filter { it.isNotBlank() }.joinToString("\n• ", prefix = "• ")
        MaterialAlertDialogBuilder(this)
            .setTitle("Add $name?")
            .setMessage(if (permissions.isEmpty()) "It asks for no special permissions." else "It will be able to:\n$readable")
            .setPositiveButton("Add") { _, _ -> result.complete(true) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> result.complete(false) }
            .setOnCancelListener { result.complete(false) }
            .show()
        return result
    }

    override fun addonsChanged() = Unit

    companion object {
        private const val TAG = "Bork"
        private const val REQ_LIBRARY = 10
        private const val REQ_SETTINGS = 11

        /** Add-ons are per process, not per activity. */
        private var addonsStarted = false
    }
}
