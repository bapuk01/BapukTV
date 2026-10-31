package com.bapuk.tv

import android.Manifest
import androidx.activity.ComponentActivity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class PlaylistsActivity : ComponentActivity() {

    private val scope = MainScope()
    private lateinit var listView: RecyclerView
    private lateinit var busy: View
    private lateinit var hint: View
    private val adapter = PlaylistAdapter()
    /** Открыто из файлового менеджера: после добавления сразу к каналам */
    private var fromViewIntent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlists)
        listView = findViewById(R.id.list)
        busy = findViewById(R.id.busy)
        hint = findViewById(R.id.hint)
        listView.verticalList()
        listView.adapter = adapter

        findViewById<View>(R.id.addUrl).setOnClickListener { askUrl() }
        findViewById<View>(R.id.addFile).setOnClickListener { pickFile() }

        val presetsBox = findViewById<LinearLayout>(R.id.presets)
        Presets.all.forEachIndexed { i, preset ->
            val card = layoutInflater.inflate(R.layout.item_preset, presetsBox, false)
            if (i > 0) (card.layoutParams as LinearLayout.LayoutParams).marginStart = (12 * resources.displayMetrics.density).toInt()
            card.setOnClickListener { onPresetClick(preset) }
            presetsBox.addView(card)
            presetCards += card
        }

        if (Presets.all.isEmpty()) {
            // Сборка без встроенных плейлистов — раздел не показываем
            presetsBox.visibility = View.GONE
            findViewById<View>(R.id.presetsHeader).visibility = View.GONE
            findViewById<View>(R.id.ownHeader).visibility = View.GONE
        }

        refreshList()
        if (adapter.itemCount > 0) listView.post { listView.focusPosition(adapter.activeIndex()) }
        else (presetCards.firstOrNull() ?: findViewById(R.id.addUrl)).requestFocus() // первый запуск

        if (savedInstanceState == null && intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                fromViewIntent = true
                execute { Repo.addFromUri(this, uri, null) }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshList() {
        adapter.submit(Repo.playlists())
        hint.visibility = if (adapter.itemCount > 0) View.VISIBLE else View.GONE
        bindPresets()
    }

    // ---------- Готовые плейлисты ----------

    private val presetCards = mutableListOf<View>()

    private fun addedPreset(preset: Preset) = Repo.playlists().firstOrNull { it.source == preset.url }

    private fun bindPresets() {
        val activeId = Repo.activeId
        Presets.all.forEachIndexed { i, preset ->
            val card = presetCards.getOrNull(i) ?: return@forEachIndexed
            val added = addedPreset(preset)
            card.findViewById<TextView>(R.id.name).text = preset.name
            card.findViewById<TextView>(R.id.details).text = when {
                added == null -> getString(R.string.preset_add)
                added.id == activeId -> resources.getQuantityString(R.plurals.channels, added.count, added.count) +
                    " · " + getString(R.string.preset_active)
                else -> resources.getQuantityString(R.plurals.channels, added.count, added.count)
            }
            // Выбранный плейлист подсвечивается цветом названия
            card.isActivated = added != null && added.id == activeId
        }
    }

    private fun onPresetClick(preset: Preset) {
        val added = addedPreset(preset)
        if (added != null) {
            // Уже скачан — просто переключаемся на него
            Repo.activeId = added.id
            if (isTaskRoot) startActivity(Intent(this, MainActivity::class.java))
            finish()
        } else {
            execute { Repo.addFromUrl(preset.url, preset.name) }
        }
    }

    // ---------- Добавление ----------

    private fun askUrl() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_url, null)
        val url = view.findViewById<EditText>(R.id.url)
        val name = view.findViewById<EditText>(R.id.name)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.add_by_url)
            .setView(view)
            .setPositiveButton(R.string.add) { _, _ ->
                var u = url.text.toString().trim()
                if (u.isEmpty()) return@setPositiveButton
                if (!u.contains("://")) u = "http://$u"
                val n = name.text.toString().trim()
                execute { Repo.addFromUrl(u, n) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        // «Готово» на клавиатуре — сразу добавить, без поиска кнопки пультом
        name.setOnEditorActionListener { _, _, _ ->
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            true
        }
        name.nextFocusDownId = android.R.id.button1
        url.requestFocus()
    }

    private fun pickFile() {
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        val get = Intent(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        // resolveActivity() на Android 11+ без <queries> всегда null — просто пробуем запустить
        for (i in listOf(open, get)) {
            try {
                startActivityForResult(i, REQ_FILE)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        // На многих ТВ нет системного проводника — используем встроенный
        Toast.makeText(this, R.string.no_file_picker, Toast.LENGTH_LONG).show()
        browseFiles()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE && resultCode == RESULT_OK) {
            data?.data?.let { uri -> execute { Repo.addFromUri(this, uri, null) } }
        }
    }

    /** Выполняет добавление/обновление с индикатором и понятной ошибкой. */
    private fun execute(block: suspend () -> Playlist) {
        busy.visibility = View.VISIBLE
        busy.requestFocus()
        scope.launch {
            val result = runCatching { block() }
            busy.visibility = View.GONE
            result.onSuccess { p ->
                Toast.makeText(
                    this@PlaylistsActivity,
                    "${p.name}: ${resources.getQuantityString(R.plurals.channels, p.count, p.count)}",
                    Toast.LENGTH_SHORT,
                ).show()
                Repo.activeId = p.id
                if (fromViewIntent || isTaskRoot) {
                    startActivity(Intent(this@PlaylistsActivity, MainActivity::class.java))
                }
                finish()
            }.onFailure { e ->
                refreshList()
                AlertDialog.Builder(this@PlaylistsActivity)
                    .setTitle("Не получилось")
                    .setMessage(errorText(e))
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun errorText(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "Сервер не найден. Проверьте ссылку и интернет."
        is java.net.SocketTimeoutException -> "Сервер не отвечает. Попробуйте ещё раз."
        is java.net.MalformedURLException -> "Неверная ссылка."
        is SecurityException -> "Нет доступа к файлу."
        else -> e.message ?: e.javaClass.simpleName
    }

    // ---------- Действия с плейлистом ----------

    private fun showActions(p: Playlist) {
        val actions = buildList {
            if (p.isUrl) add(getString(R.string.refresh) to { refresh(p) })
            add(getString(R.string.rename) to { rename(p) })
            add(getString(R.string.delete) to { confirmDelete(p) })
        }
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun refresh(p: Playlist) {
        busy.visibility = View.VISIBLE
        scope.launch {
            val r = runCatching { Repo.refresh(p.id) }
            busy.visibility = View.GONE
            refreshList()
            r.onSuccess {
                Toast.makeText(this@PlaylistsActivity, resources.getQuantityString(R.plurals.channels, it.count, it.count), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@PlaylistsActivity, errorText(it), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun rename(p: Playlist) {
        val input = LayoutInflater.from(this).inflate(R.layout.dialog_add_url, null)
        input.findViewById<View>(R.id.url).visibility = View.GONE
        val name = input.findViewById<EditText>(R.id.name).apply { setText(p.name); selectAll() }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isNotEmpty()) Repo.rename(p.id, n)
                refreshList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        name.requestFocus()
    }

    private fun confirmDelete(p: Playlist) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_confirm, p.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                Repo.delete(p.id)
                refreshList()
                if (adapter.itemCount > 0) listView.focusPosition(0)
                else findViewById<View>(R.id.addUrl).requestFocus()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- Встроенный выбор файла (запасной вариант для ТВ) ----------

    private fun browseFiles() {
        if (Build.VERSION.SDK_INT >= 33) {
            Toast.makeText(this, R.string.no_files_access, Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_PERM)
            return
        }
        showDir(null)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) showDir(null)
            else Toast.makeText(this, R.string.no_files_access, Toast.LENGTH_LONG).show()
        }
    }

    private class Entry(val file: File, val label: String, val icon: Int)

    /** dir == null — список накопителей. */
    private fun showDir(dir: File?) {
        val entries = ArrayList<Entry>()
        if (dir == null) {
            val internal = Environment.getExternalStorageDirectory()
            entries += Entry(internal, "Внутренняя память", R.drawable.ic_folder)
            File("/storage").listFiles()
                ?.filter { it.isDirectory && it.canRead() && it.name != "emulated" && it.name != "self" }
                ?.forEach { entries += Entry(it, "USB / SD: ${it.name}", R.drawable.ic_folder) }
        } else {
            val parent = dir.parentFile
            entries += Entry(parent ?: dir, "..", R.drawable.ic_up)
            val children = dir.listFiles().orEmpty().filter { !it.name.startsWith(".") }
            children.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
                .forEach { entries += Entry(it, it.name, R.drawable.ic_folder) }
            children.filter { it.isFile && it.extension.lowercase() in PLAYLIST_EXT }.sortedBy { it.name.lowercase() }
                .forEach { entries += Entry(it, it.name, R.drawable.ic_file) }
        }
        val roots = if (dir == null) emptyList() else listOf(Environment.getExternalStorageDirectory().path, "/storage")
        val adapter = object : ArrayAdapter<Entry>(this, R.layout.item_file, entries) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = (convertView ?: layoutInflater.inflate(R.layout.item_file, parent, false)) as TextView
                val e = entries[position]
                v.text = e.label
                v.setCompoundDrawablesRelativeWithIntrinsicBounds(e.icon, 0, 0, 0)
                return v
            }
        }
        AlertDialog.Builder(this)
            .setTitle(dir?.path ?: getString(R.string.add_from_file))
            .setAdapter(adapter) { _, which ->
                val e = entries[which]
                when {
                    e.label == ".." && dir?.path in roots -> showDir(null)
                    e.file.isDirectory -> showDir(e.file)
                    else -> execute { Repo.addFromFile(e.file, null) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- Список ----------

    private inner class PlaylistAdapter : RecyclerView.Adapter<PlaylistAdapter.VH>() {
        private var items: List<Playlist> = emptyList()
        private var activeId: String? = null

        fun submit(list: List<Playlist>) {
            items = list
            activeId = Repo.activeId
            notifyDataSetChanged()
        }

        fun activeIndex() = items.indexOfFirst { it.id == activeId }.coerceAtLeast(0)

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false))

        override fun onBindViewHolder(h: VH, position: Int) {
            val p = items[position]
            h.name.text = p.name
            val parts = mutableListOf(resources.getQuantityString(R.plurals.channels, p.count, p.count))
            if (p.isUrl) parts += getString(
                R.string.updated,
                DateUtils.getRelativeTimeSpanString(p.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            )
            parts += p.source
            h.details.text = parts.joinToString("  ·  ")
            h.check.visibility = if (p.id == activeId) View.VISIBLE else View.INVISIBLE
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.name)
            val details: TextView = v.findViewById(R.id.details)
            val check: ImageView = v.findViewById(R.id.check)

            init {
                v.setOnClickListener {
                    val p = items.getOrNull(bindingAdapterPosition) ?: return@setOnClickListener
                    Repo.activeId = p.id
                    finish()
                }
                v.setOnLongClickListener {
                    items.getOrNull(bindingAdapterPosition)?.let(::showActions)
                    true
                }
            }
        }
    }

    companion object {
        private const val REQ_FILE = 1
        private const val REQ_PERM = 2
        private val PLAYLIST_EXT = setOf("m3u", "m3u8", "txt")
    }
}
