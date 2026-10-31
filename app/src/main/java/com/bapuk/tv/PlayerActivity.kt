package com.bapuk.tv

import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.provider.Settings
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.roundToInt

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var videoView: PlayerView
    private lateinit var buffering: View
    private lateinit var error: TextView
    private lateinit var errorBox: View
    private lateinit var info: View
    private lateinit var infoLogo: ImageView
    private lateinit var infoNumber: TextView
    private lateinit var infoName: TextView
    private lateinit var infoGroup: TextView
    private lateinit var infoTech: TextView
    private lateinit var digits: TextView
    private lateinit var listPanel: View
    private lateinit var listTitle: TextView
    private lateinit var listView: RecyclerView
    private lateinit var listAdapter: ChannelAdapter
    private lateinit var gestureBadge: View
    private lateinit var gestureIcon: ImageView
    private lateinit var gestureBar: ProgressBar
    private lateinit var gestureValue: TextView
    private lateinit var audio: AudioManager

    private var player: ExoPlayer? = null
    private var channels: List<Channel> = emptyList()
    private var index = 0
    private var previousIndex = -1
    private var tunedIndex = -1
    private var retries = 0
    private var forceHls = false
    private var typed = ""

    // Категории в плеере (второе нажатие «влево»)
    private lateinit var groupPanel: View
    private lateinit var groupListView: RecyclerView
    private lateinit var groupAdapter: GroupAdapter
    /** Категория, каналы которой сейчас показаны в списке (может отличаться от той, что листается ↑/↓) */
    private var browseKey: String? = null
    private var browseTitle: String = ""
    private val previewGroupRunnable = Runnable { previewGroup(groupAdapter.selected) }

    private val hideInfo = Runnable { info.visibility = View.GONE }
    private val hideList = Runnable { hideChannelList() }
    private val tuneRunnable = Runnable { tune() }
    private val retryRunnable = Runnable { tune(retry = true) }
    private val digitsRunnable = Runnable { applyDigits() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        channels = Session.channels
        if (channels.isEmpty()) {
            finish()
            return
        }
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(channels.indices)
        setContentView(R.layout.activity_player)
        enterFullscreen()

        videoView = findViewById(R.id.video)
        buffering = findViewById(R.id.buffering)
        error = findViewById(R.id.error)
        errorBox = findViewById(R.id.errorBox)
        info = findViewById(R.id.info)
        infoLogo = findViewById(R.id.infoLogo)
        infoNumber = findViewById(R.id.infoNumber)
        infoName = findViewById(R.id.infoName)
        infoGroup = findViewById(R.id.infoGroup)
        infoTech = findViewById(R.id.infoTech)
        digits = findViewById(R.id.digits)
        listPanel = findViewById(R.id.listPanel)
        listTitle = findViewById(R.id.listTitle)
        listView = findViewById(R.id.list)
        gestureBadge = findViewById(R.id.gestureBadge)
        gestureIcon = findViewById(R.id.gestureIcon)
        gestureBar = findViewById(R.id.gestureBar)
        gestureValue = findViewById(R.id.gestureValue)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager

        // Яркость, выставленная жестом, действует только в плеере и запоминается
        Repo.playerBrightness.takeIf { it > 0f }?.let { b ->
            window.attributes = window.attributes.apply { screenBrightness = b }
        }

        listAdapter = ChannelAdapter(
            onClick = { pos ->
                val list = listAdapter.items
                hideChannelList()
                if (list !== channels) playFromCategory(list, pos)
                else if (pos != index) switchTo(pos, immediate = true)
                else showInfo()
            },
            onLongClick = { pos ->
                val c = listAdapter.items[pos]
                val added = Repo.toggleFavorite(c.url)
                listAdapter.favorites = Repo.favorites()
                listAdapter.notifyItemChanged(pos)
                Toast.makeText(this, if (added) R.string.added_fav else R.string.removed_fav, Toast.LENGTH_SHORT).show()
            },
            onKey = { pos, code ->
                bumpListTimer()
                val last = listAdapter.itemCount - 1
                when (code) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { hideChannelList(); true }
                    // Второе «влево» — список категорий
                    KeyEvent.KEYCODE_DPAD_LEFT -> { showGroups(); true }
                    // По кругу: с первого вверх — на последний и наоборот
                    KeyEvent.KEYCODE_DPAD_UP -> if (pos == 0) { listView.focusPosition(last); true } else false
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (pos == last) { listView.focusPosition(0); true } else false
                    else -> false
                }
            },
        )
        listView.verticalList()
        listView.adapter = listAdapter
        setListTitle(Session.title)
        listTitle.setOnClickListener {
            // На планшете: нажатие на заголовок открывает / закрывает категории
            if (groupPanel.visibility == View.VISIBLE) hideGroups() else showGroups()
        }

        groupPanel = findViewById(R.id.groupPanel)
        groupListView = findViewById(R.id.groupList)
        groupAdapter = GroupAdapter(
            onFocus = { pos ->
                bumpListTimer()
                // Каналы категории показываются сразу при перемещении по категориям
                if (pos != groupAdapter.selected) {
                    groupAdapter.selected = pos
                    handler.removeCallbacks(previewGroupRunnable)
                    handler.postDelayed(previewGroupRunnable, 120)
                }
            },
            onClick = { pos ->
                groupAdapter.selected = pos
                previewGroup(pos)
                focusBrowsedChannel()
            },
            onKey = { pos, code ->
                bumpListTimer()
                when (code) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (browseKey != groupAdapter.items.getOrNull(pos)?.key) previewGroup(pos)
                        focusBrowsedChannel(); true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> true
                    KeyEvent.KEYCODE_DPAD_UP -> pos == 0
                    KeyEvent.KEYCODE_DPAD_DOWN -> pos == groupAdapter.itemCount - 1
                    else -> false
                }
            },
        )
        groupListView.verticalList()
        groupListView.adapter = groupAdapter
        onBackPressedDispatcher.addCallback(this, backCallback)

        setupTouch()
    }

    override fun onStart() {
        super.onStart()
        if (channels.isEmpty()) return
        player = buildPlayer().also { videoView.player = it }
        tunedIndex = -1
        tune()
        showInfo()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        videoView.player = null
        player?.release()
        player = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFullscreen()
    }

    // ---------- Плеер ----------

    private fun buildPlayer(): ExoPlayer {
        // Короткий стартовый буфер — канал начинает играть быстрее
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_000, 2_500)
            .build()
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        return ExoPlayer.Builder(this, renderers)
            .setLoadControl(loadControl)
            .build().apply {
                playWhenReady = true
                addListener(listener)
            }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            buffering.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (state == Player.STATE_READY) {
                retries = 0
                errorBox.visibility = View.GONE
            }
            if (state == Player.STATE_ENDED) {
                // Прямой эфир «закончился» — переподключаемся
                handler.postDelayed(retryRunnable, 1_000)
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            updateTech(videoSize)
        }

        override fun onPlayerError(e: PlaybackException) {
            val p = player ?: return
            if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            // Ссылка без .m3u8, но внутри HLS — пробуем ещё раз как HLS
            if (!forceHls && (e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                        e.cause is UnrecognizedInputFormatException)
            ) {
                forceHls = true
                tune(retry = true)
                return
            }
            buffering.visibility = View.GONE
            val code = (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            val detail = code?.let { "HTTP $it" }
                ?: e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')
            val title = "«${channels[index].name}» — ${getString(R.string.channel_error).lowercase()}"
            // 4xx (кроме 408/429) — ссылка не работает, повторять бессмысленно
            val hopeless = code != null && code in 400..499 && code != 408 && code != 429
            retries++
            error.text = if (!hopeless && retries <= MAX_RETRIES) {
                handler.postDelayed(retryRunnable, 1_500L * retries)
                "$title\n$detail · повтор $retries/$MAX_RETRIES"
            } else {
                "$title\n$detail"
            }
            errorBox.visibility = View.VISIBLE
        }
    }

    /** Переключение канала: плашка сразу, само подключение — после короткой паузы (быстрое «листание»). */
    private fun switchTo(newIndex: Int, immediate: Boolean = false) {
        if (channels.isEmpty()) return
        val target = ((newIndex % channels.size) + channels.size) % channels.size
        if (target != index) previousIndex = index
        index = target
        showInfo()
        handler.removeCallbacks(tuneRunnable)
        if (immediate) tune() else handler.postDelayed(tuneRunnable, ZAP_DELAY)
    }

    private fun tune(retry: Boolean = false) {
        val p = player ?: return
        handler.removeCallbacks(retryRunnable)
        if (!retry) {
            if (index == tunedIndex && p.playbackState != Player.STATE_IDLE) return
            retries = 0
            forceHls = false
        }
        tunedIndex = index
        val c = channels[index]
        Repo.lastChannelUrl = c.url
        if (!retry) errorBox.visibility = View.GONE

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(c.userAgent ?: USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        c.referrer?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }

        val item = MediaItem.Builder().setUri(c.url).apply {
            if (forceHls) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        val source = try {
            DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)).createMediaSource(item)
        } catch (e: Exception) {
            // Неподдерживаемый формат ссылки (например, rtsp://) — показываем ошибку, а не падаем
            p.stop()
            buffering.visibility = View.GONE
            error.text = "«${c.name}» — ${getString(R.string.channel_error).lowercase()}\nформат не поддерживается"
            errorBox.visibility = View.VISIBLE
            return
        }
        p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
    }

    // ---------- Интерфейс ----------

    private fun showInfo() {
        val c = channels[index]
        infoNumber.text = c.number.toString()
        infoName.text = c.name
        infoGroup.text = c.group
        LogoLoader.load(infoLogo, c.logo)
        infoLogo.visibility = if (c.logo != null) View.VISIBLE else View.GONE
        updateTech(player?.videoSize)
        info.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, INFO_TIMEOUT)
    }

    private fun updateTech(size: VideoSize?) {
        val h = size?.height ?: 0
        infoTech.text = when {
            index != tunedIndex || h == 0 -> ""
            h >= 2000 -> "4K"
            else -> "${h}p"
        }
    }

    private fun showChannelList() {
        browseKey = Session.groupKey
        browseTitle = Session.title
        setListTitle(browseTitle)
        listAdapter.activeUrl = channels[index].url
        listAdapter.favorites = Repo.favorites()
        listAdapter.submit(channels)
        groupPanel.visibility = View.GONE
        listPanel.visibility = View.VISIBLE
        info.visibility = View.GONE
        listView.focusPosition(index)
        bumpListTimer()
    }

    private fun hideChannelList() {
        handler.removeCallbacks(hideList)
        handler.removeCallbacks(previewGroupRunnable)
        groupPanel.visibility = View.GONE
        listPanel.visibility = View.GONE
    }

    private fun bumpListTimer() {
        handler.removeCallbacks(hideList)
        handler.postDelayed(hideList, LIST_TIMEOUT)
    }

    /** Стрелка «‹» подсказывает, что «влево» открывает категории */
    private fun setListTitle(title: String) {
        listTitle.text = "‹  $title"
    }

    // ---------- Категории ----------

    private fun showGroups() {
        val all = Repo.cachedActiveChannels() ?: return
        groupAdapter.submit(Groups.build(this, all, Repo.favorites()))
        val pos = groupAdapter.items.indexOfFirst { it.key == browseKey }
            .takeIf { it >= 0 } ?: groupAdapter.items.indexOfFirst { it.key == Groups.ALL }
        groupAdapter.selected = pos
        groupPanel.visibility = View.VISIBLE
        groupListView.focusPosition(pos)
        bumpListTimer()
    }

    private fun hideGroups() {
        handler.removeCallbacks(previewGroupRunnable)
        groupPanel.visibility = View.GONE
        if (listAdapter.itemCount > 0) focusBrowsedChannel() else showChannelList()
    }

    /** Показывает в списке каналы выбранной категории; видео при этом продолжает идти. */
    private fun previewGroup(pos: Int) {
        handler.removeCallbacks(previewGroupRunnable)
        val g = groupAdapter.items.getOrNull(pos) ?: return
        val all = Repo.cachedActiveChannels() ?: return
        browseKey = g.key
        browseTitle = g.title
        setListTitle(g.title)
        listAdapter.favorites = Repo.favorites()
        val list = if (g.key == Session.groupKey) channels else Groups.channels(g.key, all, listAdapter.favorites)
        listAdapter.submit(list)
        val playing = list.indexOfFirst { it.url == listAdapter.activeUrl }
        listView.scrollToPosition(playing.coerceAtLeast(0))
    }

    /** Фокус на канал в показанной категории: на играющий, если он там есть, иначе на первый. */
    private fun focusBrowsedChannel() {
        if (listAdapter.itemCount == 0) return
        val playing = listAdapter.items.indexOfFirst { it.url == listAdapter.activeUrl }
        listView.focusPosition(playing.coerceAtLeast(0))
    }

    /** Включён канал из другой категории — теперь ↑/↓ листают её. */
    private fun playFromCategory(list: List<Channel>, pos: Int) {
        channels = list
        Session.channels = list
        Session.title = browseTitle
        Session.groupKey = browseKey
        setListTitle(browseTitle)
        previousIndex = -1
        tunedIndex = -1
        index = pos
        tune()
        showInfo()
    }

    private fun onDigit(d: Int) {
        if (typed.length >= 5) typed = ""
        typed += d
        digits.text = typed
        digits.visibility = View.VISIBLE
        handler.removeCallbacks(digitsRunnable)
        handler.postDelayed(digitsRunnable, DIGITS_TIMEOUT)
    }

    private fun applyDigits() {
        val n = typed.toIntOrNull()
        typed = ""
        digits.visibility = View.GONE
        if (n == null) return
        var pos = channels.indexOfFirst { it.number == n }
        if (pos < 0) {
            // Канала нет в текущем списке — ищем во всём плейлисте
            val all = Repo.cachedActiveChannels() ?: return
            val c = all.firstOrNull { it.number == n } ?: return
            channels = all
            Session.channels = all
            Session.title = getString(R.string.all_channels)
            Session.groupKey = Groups.ALL
            setListTitle(Session.title)
            tunedIndex = -1
            previousIndex = -1
            pos = all.indexOf(c)
        }
        switchTo(pos, immediate = true)
    }

    private fun toggleZoom() {
        videoView.resizeMode = if (videoView.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT)
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    // ---------- Пульт ----------

    private fun isOk(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
        code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A

    /** OK, которым открыли список, не должен «нажать» канал в списке при удержании. */
    private var swallowOkUntilUp = false

    @SuppressLint("RestrictedApi") // ложное срабатывание lint для ComponentActivity
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (swallowOkUntilUp && isOk(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_UP) swallowOkUntilUp = false
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (listPanel.visibility == View.VISIBLE) return super.onKeyDown(keyCode, event)
        if (isOk(keyCode) && typed.isEmpty()) swallowOkUntilUp = true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_PAGE_UP -> switchTo(index + 1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_PAGE_DOWN -> switchTo(index - 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE -> {
                if (typed.isNotEmpty()) {
                    handler.removeCallbacks(digitsRunnable); applyDigits()
                } else showChannelList()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> showInfo()
            KeyEvent.KEYCODE_LAST_CHANNEL -> if (previousIndex >= 0) switchTo(previousIndex, immediate = true)
            KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_TV_ZOOM_MODE -> toggleZoom()
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> onDigit(keyCode - KeyEvent.KEYCODE_0)
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> onDigit(keyCode - KeyEvent.KEYCODE_NUMPAD_0)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                // «Назад»: сначала скрываем категории, потом список
                groupPanel.visibility == View.VISIBLE -> hideGroups()
                listPanel.visibility == View.VISIBLE -> hideChannelList()
                typed.isNotEmpty() -> { typed = ""; digits.visibility = View.GONE }
                else -> finish()
            }
        }
    }

    // ---------- Сенсор (планшеты) ----------

    /** Что делает текущее движение пальца */
    private enum class Drag { NONE, BRIGHTNESS, VOLUME, CHANNEL }

    private var drag = Drag.NONE
    private var dragStartValue = 0f
    private val hideBadge = Runnable { gestureBadge.visibility = View.GONE }

    /**
     * Жесты на планшете:
     * касание — список каналов, двойное касание — масштаб,
     * вертикальный свайп слева — яркость, справа — громкость,
     * горизонтальный свайп — следующий / предыдущий канал.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        val root = findViewById<View>(R.id.root)
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                drag = Drag.NONE
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (listPanel.visibility == View.VISIBLE) hideChannelList() else showChannelList()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleZoom()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distX: Float, distY: Float): Boolean {
                val start = e1 ?: return false
                if (listPanel.visibility == View.VISIBLE) return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                if (drag == Drag.NONE) {
                    drag = when {
                        abs(dx) >= abs(dy) -> Drag.CHANNEL
                        // У верхнего и нижнего края свайп открывает системные панели — не мешаем
                        start.y < root.height * 0.08f || start.y > root.height * 0.92f -> return false
                        start.x < root.width / 2f -> Drag.BRIGHTNESS
                        else -> Drag.VOLUME
                    }
                    dragStartValue = when (drag) {
                        Drag.BRIGHTNESS -> currentBrightness()
                        Drag.VOLUME -> currentVolume()
                        else -> 0f
                    }
                }
                // Проход пальцем на 70% высоты экрана = от минимума до максимума
                val delta = -dy / (root.height * 0.7f)
                when (drag) {
                    Drag.BRIGHTNESS -> setBrightness(dragStartValue + delta)
                    Drag.VOLUME -> setVolume(dragStartValue + delta)
                    else -> Unit
                }
                return true
            }
        })
        var downX = 0f
        root.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) downX = e.x
            val handled = detector.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                when (drag) {
                    Drag.CHANNEL -> {
                        // Свайп влево — следующий канал, вправо — предыдущий
                        val dx = e.x - downX
                        if (e.actionMasked == MotionEvent.ACTION_UP && abs(dx) > root.width * 0.15f) {
                            switchTo(if (dx < 0) index + 1 else index - 1, immediate = true)
                        }
                    }
                    Drag.BRIGHTNESS -> Repo.playerBrightness = currentBrightness()
                    else -> Unit
                }
                if (drag == Drag.BRIGHTNESS || drag == Drag.VOLUME) {
                    handler.removeCallbacks(hideBadge)
                    handler.postDelayed(hideBadge, BADGE_TIMEOUT)
                }
                drag = Drag.NONE
            }
            handled
        }
    }

    // ---------- Яркость и громкость ----------

    private fun currentBrightness(): Float {
        val own = window.attributes.screenBrightness
        if (own >= 0f) return own
        // Своя яркость ещё не задана — стартуем от системной
        return Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
    }

    private fun setBrightness(value: Float) {
        val v = value.coerceIn(MIN_BRIGHTNESS, 1f)
        window.attributes = window.attributes.apply { screenBrightness = v }
        showBadge(R.drawable.ic_brightness, v, atStart = false) // свайп слева — ползунок справа
    }

    private fun currentVolume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    private fun setVolume(value: Float) {
        if (audio.isVolumeFixed) {
            // Громкость меняется только на самом устройстве (например, ТВ по HDMI)
            showBadge(R.drawable.ic_volume, currentVolume(), atStart = true)
            return
        }
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val level = (value.coerceIn(0f, 1f) * max).roundToInt()
        if (level != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
        }
        showBadge(if (level == 0) R.drawable.ic_volume_off else R.drawable.ic_volume, level.toFloat() / max, atStart = true) // свайп справа — ползунок слева
    }

    /**
     * Ползунок показывается на противоположной от пальца стороне, чтобы рука его не закрывала.
     * @param atStart true — у левого края (громкость), false — у правого (яркость)
     */
    private fun showBadge(icon: Int, fraction: Float, atStart: Boolean) {
        val percent = (fraction.coerceIn(0f, 1f) * 100).roundToInt()
        val lp = gestureBadge.layoutParams as FrameLayout.LayoutParams
        val gravity = Gravity.CENTER_VERTICAL or (if (atStart) Gravity.START else Gravity.END)
        val margin = (findViewById<View>(R.id.root).width * 0.1f).roundToInt()
        if (lp.gravity != gravity || lp.marginStart != margin) {
            lp.gravity = gravity
            lp.marginStart = margin
            lp.marginEnd = margin
            gestureBadge.layoutParams = lp
        }
        gestureIcon.setImageResource(icon)
        gestureBar.progress = percent
        gestureValue.text = "$percent%"
        gestureBadge.visibility = View.VISIBLE
        info.visibility = View.GONE
        handler.removeCallbacks(hideBadge)
    }

    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        const val EXTRA_INDEX = "index"
        private const val USER_AGENT = "BapukTV/1.0 (Linux; Android) ExoPlayer"
        private const val MAX_RETRIES = 3
        private const val ZAP_DELAY = 350L
        private const val INFO_TIMEOUT = 4_000L
        private const val LIST_TIMEOUT = 15_000L
        private const val DIGITS_TIMEOUT = 1_500L
        private const val BADGE_TIMEOUT = 800L
        private const val MIN_BRIGHTNESS = 0.02f
    }
}
