package com.bapuk.tv

import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val scope = MainScope()

    private lateinit var groupsView: RecyclerView
    private lateinit var channelsView: RecyclerView
    private lateinit var title: TextView
    private lateinit var count: TextView
    private lateinit var playlistName: TextView
    private lateinit var searchInput: EditText
    private lateinit var emptyBox: View
    private lateinit var empty: TextView
    private lateinit var emptyAction: View
    private lateinit var progress: ProgressBar
    private lateinit var btnSearch: View

    private lateinit var groupAdapter: GroupAdapter
    private lateinit var channelAdapter: ChannelAdapter

    private var all: List<Channel> = emptyList()
    private var loaded = false
    private var loadedPlaylistId: String? = null
    private var searching = false
    private var autoStartPending = false
    private var loadJob: Job? = null
    private val selectGroupRunnable = Runnable {
        // Применяем группу, только если пользователь всё ещё в списке групп;
        // иначе это был мимолётный программный фокус — возвращаем подсветку текущей группы
        if (groupsView.hasFocus()) applyGroup(groupAdapter.selected)
        else if (currentAppliedGroup >= 0) groupAdapter.selected = currentAppliedGroup
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        groupsView = findViewById(R.id.groups)
        channelsView = findViewById(R.id.channels)
        title = findViewById(R.id.title)
        count = findViewById(R.id.count)
        playlistName = findViewById(R.id.playlistName)
        searchInput = findViewById(R.id.searchInput)
        emptyBox = findViewById(R.id.emptyBox)
        empty = findViewById(R.id.empty)
        emptyAction = findViewById(R.id.emptyAction)
        progress = findViewById(R.id.progress)

        groupAdapter = GroupAdapter(
            onFocus = { pos ->
                // Каналы группы появляются сразу при перемещении по группам
                if (searching) exitSearch(restore = false)
                if (pos != currentAppliedGroup) {
                    groupAdapter.selected = pos
                    groupsView.removeCallbacks(selectGroupRunnable)
                    groupsView.postDelayed(selectGroupRunnable, 120)
                }
            },
            onClick = { pos ->
                groupsView.removeCallbacks(selectGroupRunnable)
                groupAdapter.selected = pos
                applyGroup(pos)
                if (!groupsView.isInTouchMode) focusChannels()
            },
            onKey = { pos, code ->
                when (code) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        groupsView.removeCallbacks(selectGroupRunnable)
                        if (pos != currentAppliedGroup) applyGroup(pos)
                        focusChannels(); true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> pos == groupAdapter.itemCount - 1
                    else -> false
                }
            },
        )
        groupsView.verticalList()
        groupsView.adapter = groupAdapter

        channelAdapter = ChannelAdapter(
            onClick = ::play,
            onLongClick = ::toggleFavorite,
            onKey = { pos, code ->
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (searching) searchInput.requestFocus() else groupsView.focusPosition(groupAdapter.selected)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> when {
                        pos > 0 -> false
                        searching -> { searchInput.requestFocus(); true }
                        else -> true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> pos == channelAdapter.itemCount - 1
                    else -> false
                }
            },
        )
        channelsView.verticalList()
        channelsView.adapter = channelAdapter
        onBackPressedDispatcher.addCallback(this, backCallback)

        btnSearch = findViewById(R.id.btnSearch)
        btnSearch.setOnClickListener { startSearch() }
        findViewById<View>(R.id.btnPlaylists).setOnClickListener { openPlaylists() }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        // Автозапуск канала — только при открытии приложения, не при повороте экрана
        autoStartPending = savedInstanceState == null
        emptyAction.setOnClickListener { openPlaylists() }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) { if (searching) applySearch(s.toString()) }
        })
        searchInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                if (channelAdapter.itemCount > 0) channelsView.focusPosition(0)
                true
            } else false
        }
        searchInput.setOnKeyListener { _, code, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (channelAdapter.itemCount > 0) { hideKeyboard(); channelsView.focusPosition(0) }
                    true
                }
                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val active = Repo.active()
        if (!loaded || active?.id != loadedPlaylistId || (active != null && Repo.cachedActiveChannels() == null)) {
            load()
        } else if (active != null) {
            // Вернулись из плеера: обновим избранное и встанем на последний канал
            playlistName.text = active.name
            refreshFavorites()
            if (!searching) focusLastChannel()
            maybeAutoStart()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Приложение снова открыли с главного экрана ТВ, пока оно было в фоне
        if (intent.action == Intent.ACTION_MAIN) autoStartPending = true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------- Загрузка ----------

    private fun load() {
        loadJob?.cancel()
        exitSearch(restore = false)
        val active = Repo.active()
        loaded = true
        loadedPlaylistId = active?.id
        btnSearch.visibility = if (active == null) View.GONE else View.VISIBLE
        if (active == null) {
            autoStartPending = false
            all = emptyList()
            playlistName.text = ""
            groupAdapter.submit(emptyList())
            channelAdapter.submit(emptyList())
            title.text = ""
            count.text = ""
            // Без встроенных плейлистов — просто «добавьте свой»
            val hasPresets = Presets.all.isNotEmpty()
            (emptyAction as TextView).setText(if (hasPresets) R.string.choose_playlist else R.string.add_playlist)
            showEmpty(getString(if (hasPresets) R.string.no_playlists else R.string.no_playlists_open), withAction = true)
            // После отрисовки: иначе система сама отдаёт первый фокус шестерёнке
            emptyAction.post { emptyAction.requestFocus() }
            return
        }
        playlistName.text = active.name
        progress.visibility = View.VISIBLE
        emptyBox.visibility = View.GONE
        loadJob = scope.launch {
            all = Repo.activeChannels()
            progress.visibility = View.GONE
            buildGroups()
            val groupKey = Repo.lastGroup
            val idx = groupAdapter.items.indexOfFirst { it.key == groupKey }
                .takeIf { it >= 0 } ?: if (Repo.favorites().isNotEmpty()) 0 else 1
            groupAdapter.selected = idx.coerceAtMost(groupAdapter.itemCount - 1)
            applyGroup(groupAdapter.selected)
            focusLastChannel(initial = true)
            maybeAutoStart()

            if (Repo.needsRefresh(active)) backgroundRefresh(active.id)
        }
    }

    /** Тихо обновляет плейлист по ссылке, не сбивая пользователю фокус. */
    private fun backgroundRefresh(id: String) {
        scope.launch {
            runCatching { Repo.refresh(id) }.onSuccess {
                if (Repo.activeId != id || searching) return@onSuccess
                val focusedUrl = (channelsView.focusedChild?.let { channelsView.getChildAdapterPosition(it) })
                    ?.takeIf { it >= 0 }?.let { channelAdapter.items.getOrNull(it)?.url }
                all = Repo.activeChannels()
                val key = groupAdapter.items.getOrNull(groupAdapter.selected)?.key
                buildGroups()
                groupAdapter.selected = groupAdapter.items.indexOfFirst { it.key == key }.coerceAtLeast(0)
                applyGroup(groupAdapter.selected)
                focusedUrl?.let { url ->
                    channelAdapter.items.indexOfFirst { it.url == url }.takeIf { it >= 0 }
                        ?.let { channelsView.focusPosition(it) }
                }
            }
        }
    }

    private fun buildGroups() {
        groupAdapter.submit(Groups.build(this, all, Repo.favorites()))
    }

    private var currentAppliedGroup = -1

    private fun applyGroup(pos: Int) {
        val g = groupAdapter.items.getOrNull(pos) ?: return
        groupsView.removeCallbacks(selectGroupRunnable)
        currentAppliedGroup = pos
        val favs = Repo.favorites()
        val list = Groups.channels(g.key, all, favs)
        Repo.lastGroup = g.key
        title.text = g.title
        count.text = list.size.toString()
        channelAdapter.favorites = favs
        channelAdapter.submit(list)
        channelsView.scrollToPosition(0)
        if (list.isEmpty() && g.key == KEY_FAV) showEmpty(getString(R.string.empty_favorites))
        else emptyBox.visibility = View.GONE
    }

    private fun showEmpty(text: String, withAction: Boolean = false) {
        empty.text = text
        emptyAction.visibility = if (withAction) View.VISIBLE else View.GONE
        emptyBox.visibility = View.VISIBLE
    }

    private fun focusChannels() {
        if (channelAdapter.itemCount == 0) return
        val lastUrl = Repo.lastChannelUrl
        val idx = channelAdapter.items.indexOfFirst { it.url == lastUrl }
        channelsView.focusPosition(if (idx >= 0) idx else 0)
    }

    /** @param initial первый показ после загрузки: если канала нет в группе — фокус на первый. */
    private fun focusLastChannel(initial: Boolean = false) {
        val lastUrl = Repo.lastChannelUrl
        var idx = channelAdapter.items.indexOfFirst { it.url == lastUrl }
        if (idx < 0 && !initial && !searching && lastUrl != null && all.any { it.url == lastUrl }) {
            // В плеере выбрали канал из другой категории — показываем её (или «Все каналы»)
            val fromPlayer = groupAdapter.items.indexOfFirst { it.key == Session.groupKey }
            val target = if (fromPlayer >= 0 &&
                Groups.channels(Session.groupKey!!, all, Repo.favorites()).any { it.url == lastUrl }
            ) fromPlayer else groupAdapter.items.indexOfFirst { it.key == KEY_ALL }
            groupAdapter.selected = target
            applyGroup(target)
            idx = channelAdapter.items.indexOfFirst { it.url == lastUrl }
        }
        when {
            idx >= 0 -> channelsView.focusPosition(idx)
            initial && channelAdapter.itemCount > 0 -> channelsView.focusPosition(0)
            initial -> groupsView.focusPosition(groupAdapter.selected)
        }
    }

    // ---------- Автозапуск ----------

    /** Включает канал при запуске согласно настройке «При запуске». */
    private fun maybeAutoStart() {
        if (!autoStartPending) return
        autoStartPending = false
        if (all.isEmpty() || searching) return
        when (Repo.startMode) {
            StartMode.LIST -> Unit
            StartMode.LAST -> Repo.lastChannelUrl?.let(::playByUrl)
            StartMode.FAVORITE -> {
                // Переключение ↑/↓ в плеере пойдёт по избранному
                val favs = Repo.favorites()
                if (all.any { it.url in favs }) {
                    selectGroup(groupAdapter.items.indexOfFirst { it.key == KEY_FAV })
                    channelsView.focusPosition(0)
                    play(0)
                }
            }
            StartMode.CHANNEL -> Repo.startChannelUrl?.let(::playByUrl)
        }
    }

    /** Играет канал из текущей группы, а если его там нет — из «Всех каналов». */
    private fun playByUrl(url: String) {
        var idx = channelAdapter.items.indexOfFirst { it.url == url }
        if (idx < 0) {
            if (all.none { it.url == url }) return
            selectGroup(groupAdapter.items.indexOfFirst { it.key == KEY_ALL })
            idx = channelAdapter.items.indexOfFirst { it.url == url }
        }
        channelsView.focusPosition(idx)
        play(idx)
    }

    private fun selectGroup(pos: Int) {
        if (pos < 0) return
        groupAdapter.selected = pos
        applyGroup(pos)
    }

    // ---------- Поиск ----------

    private fun startSearch() {
        if (all.isEmpty()) return
        searching = true
        searchInput.visibility = View.VISIBLE
        title.text = getString(R.string.search_results)
        applySearch(searchInput.text.toString())
        searchInput.requestFocus()
        searchInput.post {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun applySearch(q: String) {
        val query = q.trim()
        val list = if (query.isEmpty()) emptyList() else all.filter { it.name.contains(query, ignoreCase = true) }
        count.text = if (query.isEmpty()) "" else list.size.toString()
        channelAdapter.favorites = Repo.favorites()
        channelAdapter.submit(list)
        if (query.isNotEmpty() && list.isEmpty()) showEmpty(getString(R.string.empty_search))
        else emptyBox.visibility = View.GONE
    }

    private fun exitSearch(restore: Boolean = true) {
        if (!searching) return
        searching = false
        hideKeyboard()
        searchInput.setText("")
        searchInput.visibility = View.GONE
        currentAppliedGroup = -1 // в списке сейчас результаты поиска
        if (restore) {
            applyGroup(groupAdapter.selected)
            groupsView.focusPosition(groupAdapter.selected)
        }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    // ---------- Действия ----------

    private fun play(pos: Int) {
        val list = channelAdapter.items
        if (pos !in list.indices) return
        Session.channels = list
        Session.title = title.text.toString()
        Session.groupKey = if (searching) null else groupAdapter.items.getOrNull(currentAppliedGroup)?.key
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_INDEX, pos))
    }

    private fun toggleFavorite(pos: Int) {
        val c = channelAdapter.items.getOrNull(pos) ?: return
        val added = Repo.toggleFavorite(c.url)
        Toast.makeText(this, if (added) R.string.added_fav else R.string.removed_fav, Toast.LENGTH_SHORT).show()
        refreshFavorites()
    }

    private fun refreshFavorites() {
        val favs = Repo.favorites()
        val favCount = all.count { it.url in favs }
        if (groupAdapter.items.firstOrNull()?.count != favCount) {
            groupAdapter.submit(groupAdapter.items.mapIndexed { i, g -> if (i == 0) g.copy(count = favCount) else g })
        }
        val onFavGroup = !searching && groupAdapter.items.getOrNull(currentAppliedGroup)?.key == KEY_FAV
        if (onFavGroup) {
            // В «Избранном» список меняется — сохраняем позицию фокуса
            val focusedPos = channelsView.focusedChild?.let { channelsView.getChildAdapterPosition(it) } ?: -1
            applyGroup(currentAppliedGroup)
            when {
                channelAdapter.itemCount > 0 && focusedPos >= 0 -> channelsView.focusPosition(focusedPos)
                channelAdapter.itemCount == 0 -> groupsView.focusPosition(currentAppliedGroup)
            }
        } else {
            channelAdapter.favorites = favs
            channelAdapter.notifyItemRangeChanged(0, channelAdapter.itemCount)
        }
    }

    private fun openPlaylists() {
        startActivity(Intent(this, PlaylistsActivity::class.java))
    }

    /** Если фокуса нет (например, после касания экрана), первое нажатие пульта ставит его куда нужно. */
    @SuppressLint("RestrictedApi") // ложное срабатывание lint для ComponentActivity
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && currentFocus == null && isNavKey(event.keyCode)) {
            when {
                emptyAction.isShown -> emptyAction.requestFocus()
                channelAdapter.itemCount > 0 -> focusLastChannel(initial = true)
                groupAdapter.itemCount > 0 -> groupsView.focusPosition(groupAdapter.selected)
                else -> return super.dispatchKeyEvent(event)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isNavKey(code: Int) = when (code) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> true
        else -> false
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                searching -> exitSearch()
                channelsView.hasFocus() -> groupsView.focusPosition(groupAdapter.selected)
                else -> {
                    // Обычное поведение системы (выход)
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }
    }

    companion object {
        private const val KEY_FAV = Groups.FAV
        private const val KEY_ALL = Groups.ALL
    }
}
