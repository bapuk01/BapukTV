package com.bapuk.tv

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {

    private val scope = MainScope()
    private lateinit var options: LinearLayout
    private var channels: List<Channel> = emptyList()

    /** Строки вариантов «При запуске» в порядке StartMode */
    private val rows = HashMap<StartMode, View>()
    private lateinit var bootRow: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        options = findViewById(R.id.startOptions)

        StartMode.entries.forEach { mode ->
            val row = layoutInflater.inflate(R.layout.item_playlist, options, false)
            row.setOnClickListener { onOptionClick(mode) }
            options.addView(row)
            rows[mode] = row
        }
        bootRow = layoutInflater.inflate(R.layout.item_playlist, findViewById(R.id.bootOption), false).also {
            it.setOnClickListener { onBootClick() }
            findViewById<FrameLayout>(R.id.bootOption).addView(it)
        }
        runCatching {
            val v = packageManager.getPackageInfo(packageName, 0).versionName
            findViewById<TextView>(R.id.version).text = "${getString(R.string.app_name)} $v"
        }

        bindRows()
        rows[Repo.startMode]?.requestFocus()

        // Названия каналов нужны для подписей «Сейчас: …»
        scope.launch {
            channels = Repo.activeChannels()
            bindRows()
        }
    }

    override fun onResume() {
        super.onResume()
        // Могли вернуться из системных настроек с выданным разрешением
        bindRows()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------- Автозапуск ----------

    private fun onBootClick() {
        when {
            !Repo.autoStartOnBoot -> {
                Repo.autoStartOnBoot = true
                BootReceiver.setEnabled(this, true)
                bindRows()
                if (BootReceiver.needsOverlayPermission(this)) askOverlayPermission()
            }
            BootReceiver.needsOverlayPermission(this) -> askOverlayPermission()
            else -> {
                Repo.autoStartOnBoot = false
                BootReceiver.setEnabled(this, false)
                bindRows()
            }
        }
    }

    private fun askOverlayPermission() {
        AlertDialog.Builder(this)
            .setTitle(R.string.overlay_title)
            .setMessage(R.string.overlay_message)
            .setPositiveButton(R.string.overlay_open) { _, _ -> openOverlaySettings() }
            .setNegativeButton(R.string.overlay_disable) { _, _ ->
                Repo.autoStartOnBoot = false
                BootReceiver.setEnabled(this, false)
                bindRows()
            }
            .show()
            // По умолчанию на пульте — «Открыть настройки», а не выключение
            .getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
    }

    private fun openOverlaySettings() {
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
        )
        for (i in intents) {
            try {
                startActivity(i)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        // На части ТВ (например, Google TV) экран разрешения открыть напрямую нельзя
        AlertDialog.Builder(this)
            .setTitle(R.string.overlay_title)
            .setMessage(R.string.overlay_manual)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun onOptionClick(mode: StartMode) {
        if (mode == StartMode.CHANNEL) {
            // Выбор канала; режим включится после выбора
            pickChannel()
            return
        }
        Repo.startMode = mode
        bindRows()
    }

    private fun bindRows() {
        val current = Repo.startMode
        val byUrl = { url: String? -> channels.firstOrNull { it.url == url } }
        rows.forEach { (mode, row) ->
            val (title, details) = when (mode) {
                StartMode.LIST -> getString(R.string.start_list) to getString(R.string.start_list_hint)
                StartMode.LAST -> getString(R.string.start_last) to
                    (byUrl(Repo.lastChannelUrl)?.let { getString(R.string.start_last_value, it.label()) }
                        ?: getString(R.string.start_last_hint))
                StartMode.FAVORITE -> {
                    val favs = Repo.favorites()
                    val first = channels.firstOrNull { it.url in favs }
                    getString(R.string.start_fav) to when {
                        first != null -> getString(R.string.start_fav_hint, first.label())
                        channels.isEmpty() && favs.isNotEmpty() -> ""
                        else -> getString(R.string.start_fav_empty)
                    }
                }
                StartMode.CHANNEL -> getString(R.string.start_channel) to
                    (byUrl(Repo.startChannelUrl)?.let { getString(R.string.start_channel_value, it.label()) }
                        ?: getString(R.string.start_channel_none))
            }
            row.findViewById<TextView>(R.id.name).text = title
            row.findViewById<TextView>(R.id.details).text = details
            row.findViewById<ImageView>(R.id.check).visibility =
                if (mode == current) View.VISIBLE else View.INVISIBLE
        }

        val bootOn = Repo.autoStartOnBoot
        bootRow.findViewById<TextView>(R.id.name).setText(R.string.boot_start)
        bootRow.findViewById<TextView>(R.id.details).setText(
            when {
                !bootOn -> R.string.boot_off
                BootReceiver.needsOverlayPermission(this) -> R.string.boot_need_permission
                else -> R.string.boot_on
            }
        )
        bootRow.findViewById<ImageView>(R.id.check).visibility = if (bootOn) View.VISIBLE else View.INVISIBLE
    }

    private fun Channel.label() = "$number. $name"

    // ---------- Выбор канала ----------

    private fun pickChannel() {
        if (channels.isEmpty()) {
            Toast.makeText(this, R.string.no_active_playlist, Toast.LENGTH_SHORT).show()
            return
        }
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_pick_channel, null)
        val search = view.findViewById<EditText>(R.id.search)
        val list = view.findViewById<RecyclerView>(R.id.list)
        list.layoutParams.height = (resources.displayMetrics.heightPixels * 0.55).toInt()

        lateinit var dialog: AlertDialog
        val adapter = ChannelAdapter(
            onClick = { pos -> choose((list.adapter as ChannelAdapter).items[pos]); dialog.dismiss() },
            onLongClick = { pos -> choose((list.adapter as ChannelAdapter).items[pos]); dialog.dismiss() },
            onKey = { pos, code ->
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> if (pos == 0) { search.requestFocus(); true } else false
                    KeyEvent.KEYCODE_DPAD_DOWN -> pos == (list.adapter?.itemCount ?: 0) - 1
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
                    else -> false
                }
            },
        )
        adapter.activeUrl = Repo.startChannelUrl
        adapter.favorites = Repo.favorites()
        adapter.submit(channels)
        list.verticalList()
        list.adapter = adapter

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val q = s.toString().trim()
                adapter.submit(if (q.isEmpty()) channels else channels.filter { it.name.contains(q, ignoreCase = true) })
                list.scrollToPosition(0)
            }
        })
        search.setOnKeyListener { _, code, e ->
            if (e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_DOWN && adapter.itemCount > 0) {
                list.focusPosition(0); true
            } else false
        }
        search.setOnEditorActionListener { _, _, _ ->
            if (adapter.itemCount > 0) list.focusPosition(0)
            true
        }

        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.pick_channel)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .show()

        // Сразу встаём на уже выбранный канал (или первый) — печатать с пульта не обязательно
        val idx = channels.indexOfFirst { it.url == Repo.startChannelUrl }.coerceAtLeast(0)
        list.post { list.focusPosition(idx) }
    }

    private fun choose(c: Channel) {
        Repo.startChannelUrl = c.url
        Repo.startMode = StartMode.CHANNEL
        bindRows()
        rows[StartMode.CHANNEL]?.requestFocus()
    }
}
