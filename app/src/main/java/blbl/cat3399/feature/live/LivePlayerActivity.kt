@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package blbl.cat3399.feature.live

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.KeyEvent
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.MarginLayoutParams
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.ui.AspectRatioFrameLayout
import blbl.cat3399.BuildConfig
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.Danmaku
import blbl.cat3399.core.model.LiveSuperChat
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.prefs.PlayerCustomShortcutAction
import blbl.cat3399.core.prefs.PlayerCustomShortcutTrigger
import blbl.cat3399.core.prefs.PlayerCustomShortcutsStore
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.DoubleBackToExitHandler
import blbl.cat3399.core.ui.FocusReturn
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.popup.AppPopup
import blbl.cat3399.core.ui.popup.PopupHost
import blbl.cat3399.databinding.ActivityPlayerBinding
import blbl.cat3399.databinding.ViewLiveSuperChatHistoryPanelBinding
import blbl.cat3399.databinding.ViewLiveSuperChatOverlayBinding
import blbl.cat3399.feature.player.AudioBalanceLevel
import blbl.cat3399.feature.player.PlayerBufferingOverlayController
import blbl.cat3399.feature.player.PlayerCustomShortcutBindings
import blbl.cat3399.feature.player.PlayerCustomShortcutDispatchResult
import blbl.cat3399.feature.player.PlayerCustomShortcutInputPolicy
import blbl.cat3399.feature.player.PlayerCustomShortcutPressController
import blbl.cat3399.feature.player.PlayerDefaultShortPressDispatcher
import blbl.cat3399.feature.player.PlayerDebugMetrics
import blbl.cat3399.feature.player.PlayerOsdSizing
import blbl.cat3399.feature.player.PlayerSettingsAdapter
import blbl.cat3399.feature.player.PlayerTouchController
import blbl.cat3399.feature.player.PlayerTouchGestureHost
import blbl.cat3399.feature.player.PlayerUpQuickCardController
import blbl.cat3399.feature.player.PlayerUiMode
import blbl.cat3399.feature.player.areaText
import blbl.cat3399.feature.player.danmaku.DanmakuSessionSettings
import blbl.cat3399.feature.player.danmaku.DanmakuFontWeight
import blbl.cat3399.feature.player.danmaku.DanmakuLaneDensity
import blbl.cat3399.feature.player.engine.BlblPlayerEngine
import blbl.cat3399.feature.player.engine.ExoPlayerEngine
import blbl.cat3399.feature.player.engine.IjkPlayerPlugin
import blbl.cat3399.feature.player.engine.IjkPlayerPluginUi
import blbl.cat3399.feature.player.engine.IjkPlayerEngine
import blbl.cat3399.feature.player.engine.LiveHlsDebugInfo
import blbl.cat3399.feature.player.engine.PlayerEngineKind
import blbl.cat3399.feature.player.engine.PlaybackSource
import blbl.cat3399.feature.player.requirePlayerTouchOverlayBinding
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private object LivePlayerSettingKeys {
    const val QUALITY = "quality"
    const val LINE = "line"
    const val HIGH_BITRATE = "high_bitrate"
    const val AUDIO_BALANCE = "audio_balance"
    const val PLAYER_ENGINE = "player_engine"
    const val DEBUG_INFO = "debug_info"
}

class LivePlayerActivity : BaseActivity() {
    override fun shouldRecreateOnUiScaleChange(): Boolean = true

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var upQuickCard: PlayerUpQuickCardController
    private lateinit var superChatOverlay: LiveSuperChatOverlayController
    private val superChatHistory = LiveSuperChatHistory()
    private val superChatHistoryReturnFocus = FocusReturn()
    private var superChatHistoryPanel: LiveSuperChatHistoryPanelController? = null

    private var player: BlblPlayerEngine? = null
    private var ijkRenderView: View? = null
    private var ijkTextureSurface: Surface? = null
    private val settingsPanelReturnFocus = FocusReturn()
    private val osdFocusReturn = FocusReturn()
    private var autoHideJob: Job? = null
    private var seekHintJob: Job? = null
    private var superChatLoadJob: Job? = null
    private var touchController: PlayerTouchController? = null
    private val shortcutPrevDanmakuOpacityByKey = HashMap<Int, Float>()
    private val shortcutPrevDanmakuTextSizeByKey = HashMap<Int, Float>()
    private val shortcutPrevDanmakuSpeedLevelByKey = HashMap<Int, Int>()
    private val shortcutPrevDanmakuAreaByKey = HashMap<Int, Float>()
    private val shortcutPressController by lazy {
        PlayerCustomShortcutPressController<PlayerCustomShortcutAction, KeyEvent>(
            scope = lifecycleScope,
            longPressTimeoutMillis = ViewConfiguration.getLongPressTimeout().toLong(),
            isEligibleKey = { keyCode ->
                keyCode > 0 &&
                    keyCode != KeyEvent.KEYCODE_UNKNOWN &&
                    !PlayerCustomShortcutsStore.isForbiddenKeyCode(keyCode)
            },
            bindingsForKey = { keyCode ->
                val bindings = BiliClient.prefs.playerCustomShortcuts.filter { it.keyCode == keyCode }
                PlayerCustomShortcutBindings(
                    shortAction =
                        bindings.firstOrNull { it.trigger == PlayerCustomShortcutTrigger.SHORT_PRESS }
                            ?.action
                            ?.takeIf(::isLiveCustomShortcutActionSupported),
                    longAction =
                        bindings.firstOrNull { it.trigger == PlayerCustomShortcutTrigger.LONG_PRESS }
                            ?.action
                            ?.takeIf(::isLiveCustomShortcutActionSupported),
                )
            },
            canDispatch = ::canDispatchLiveCustomShortcut,
            copyEventToken = { source -> KeyEvent(source) },
            executeAction = { keyCode, trigger, action ->
                applyLiveCustomShortcut(
                    keyCode = shortcutMemoryKey(keyCode, trigger),
                    action = action,
                )
            },
        )
    }
    private var debugJob: Job? = null
    private var autoFailoverJob: Job? = null
    private var liveEntryReportedRoomId: Long = 0L
    private var finishOnBackKeyUp: Boolean = false
    private var controlsVisible: Boolean = false
    private var lastInteractionAtMs: Long = 0L
    private var autoFailoverWindowStartAtMs: Long = 0L
    private var autoFailoverSwitchCount: Int = 0
    private var autoFailoverLastSwitchAtMs: Long = 0L
    private var autoFailoverInFlight: Boolean = false
    private var behindLiveWindowWindowStartAtMs: Long = 0L
    private var behindLiveWindowRecoverCount: Int = 0
    private var behindLiveWindowLastRecoverAtMs: Long = 0L
    private var exitRequested: Boolean = false
    private val bufferingOverlayController: PlayerBufferingOverlayController by lazy {
        PlayerBufferingOverlayController(
            context = this,
            bindingProvider = { if (::binding.isInitialized) binding else null },
            scope = lifecycleScope,
            playbackStateProvider = { player?.playbackState },
        )
    }

    private val doubleBackToExit by lazy {
        DoubleBackToExitHandler(context = this, windowMs = BACK_DOUBLE_PRESS_WINDOW_MS) {
            if (controlsVisible) setControlsVisible(false)
        }
    }

    private var roomId: Long = 0L
    private var realRoomId: Long = 0L
    private var roomUid: Long = 0L
    private var roomTitle: String = ""
    private var roomUname: String = ""
    private var roomFace: String? = null

    private var session: LiveSession = LiveSession()
    private val debug = PlayerDebugMetrics()
    @Volatile private var liveHlsDebugInfo: LiveHlsDebugInfo? = null

    private var lastPlay: BiliApi.LivePlayUrl? = null
    private var lastLiveStatus: Int = 0
    private var transientPlaybackResumeRequested: Boolean? = null

    private var messageClient: LiveMessageClient? = null
    private var liveDanmakuBaseUptimeMs: Long = 0L
    private var liveDanmakuLastAppendMs: Int = Int.MIN_VALUE
    private var temporarySuperChatPreviewStarted = false
    private var superChatHistoryConsumedKeyUp: Int = KeyEvent.KEYCODE_UNKNOWN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlayerOsdSizing.applyTheme(this)
        val prefs = BiliClient.prefs
        val playerInflater = PlayerOsdSizing.cloneInflater(this, layoutInflater)
        val root =
            playerInflater.inflate(
                if (prefs.playerRenderViewType == AppPrefs.PLAYER_RENDER_VIEW_TEXTURE_VIEW) blbl.cat3399.R.layout.activity_player_texture else blbl.cat3399.R.layout.activity_player,
                null,
            )
        binding = ActivityPlayerBinding.bind(root)
        upQuickCard =
            PlayerUpQuickCardController(
                activity = this,
                binding = binding,
                isCardVisible = { controlsVisible },
                keepControlsVisible = { setControlsVisible(true) },
                beforeOpenUpDetail = { prepareTransientPlaybackExit() },
            )
        setContentView(binding.root)
        Immersive.apply(this, prefs.fullscreenEnabled)
        PlayerUiMode.applyLive(this, binding)
        resetBufferingOverlayState()

        roomId = intent.getLongExtra(EXTRA_ROOM_ID, 0L)
        roomTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        roomUname = intent.getStringExtra(EXTRA_UNAME).orEmpty()
        if (roomId <= 0L) {
            AppToast.show(this, "缺少 room_id")
            finish()
            return
        }

        val sessionOverrideJson =
            intent.getStringExtra(EXTRA_ENGINE_SWITCH_SESSION_JSON)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        session =
            LiveSession(
                engineKind = PlayerEngineKind.fromPrefValue(prefs.playerEngineKind),
                highBitrateEnabled = prefs.liveHighBitrateEnabled,
                danmaku = DanmakuSessionSettings(
                    enabled = prefs.danmakuEnabled,
                    opacity = prefs.danmakuOpacity,
                    textSizeSp = prefs.danmakuTextSizeSp,
                    fontWeight = DanmakuFontWeight.fromPrefValue(prefs.danmakuFontWeight),
                    strokeWidthPx = prefs.danmakuStrokeWidthPx,
                    speedLevel = prefs.danmakuSpeed,
                    area = prefs.danmakuArea,
                    laneDensity = DanmakuLaneDensity.fromPrefValue(prefs.dan@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package blbl.cat3399.feature.live

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.KeyEvent
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.MarginLayoutParams
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.ui.AspectRatioFrameLayout
import blbl.cat3399.BuildConfig
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.Danmaku
import blbl.cat3399.core.model.LiveSuperChat
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.prefs.PlayerCustomShortcutAction
import blbl.cat3399.core.prefs.PlayerCustomShortcutTrigger
import blbl.cat3399.core.prefs.PlayerCustomShortcutsStore
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.DoubleBackToExitHandler
import blbl.cat3399.core.ui.FocusReturn
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.popup.AppPopup
import blbl.cat3399.core.ui.popup.PopupHost
import blbl.cat3399.databinding.ActivityPlayerBinding
import blbl.cat3399.databinding.ViewLiveSuperChatHistoryPanelBinding
import blbl.cat3399.databinding.ViewLiveSuperChatOverlayBinding
import blbl.cat3399.feature.player.AudioBalanceLevel
import blbl.cat3399.feature.player.PlayerBufferingOverlayController
import blbl.cat3399.feature.player.PlayerCustomShortcutBindings
import blbl.cat3399.feature.player.PlayerCustomShortcutDispatchResult
import blbl.cat3399.feature.player.PlayerCustomShortcutInputPolicy
import blbl.cat3399.feature.player.PlayerCustomShortcutPressController
import blbl.cat3399.feature.player.PlayerDefaultShortPressDispatcher
import blbl.cat3399.feature.player.PlayerDebugMetrics
import blbl.cat3399.feature.player.PlayerOsdSizing
import blbl.cat3399.feature.player.PlayerSettingsAdapter
import blbl.cat3399.feature.player.PlayerTouchController
import blbl.cat3399.feature.player.PlayerTouchGestureHost
import blbl.cat3399.feature.player.PlayerUpQuickCardController
import blbl.cat3399.feature.player.PlayerUiMode
import blbl.cat3399.feature.player.areaText
import blbl.cat3399.feature.player.danmaku.DanmakuSessionSettings
import blbl.cat3399.feature.player.danmaku.DanmakuFontWeight
import blbl.cat3399.feature.player.danmaku.DanmakuLaneDensity
import blbl.cat3399.feature.player.engine.BlblPlayerEngine
import blbl.cat3399.feature.player.engine.ExoPlayerEngine
import blbl.cat3399.feature.player.engine.IjkPlayerPlugin
import blbl.cat3399.feature.player.engine.IjkPlayerPluginUi
import blbl.cat3399.feature.player.engine.IjkPlayerEngine
import blbl.cat3399.feature.player.engine.LiveHlsDebugInfo
import blbl.cat3399.feature.player.engine.PlayerEngineKind
import blbl.cat3399.feature.player.engine.PlaybackSource
import blbl.cat3399.feature.player.requirePlayerTouchOverlayBinding
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private object LivePlayerSettingKeys {
    const val QUALITY = "quality"
    const val LINE = "line"
    const val HIGH_BITRATE = "high_bitrate"
    const val AUDIO_BALANCE = "audio_balance"
    const val PLAYER_ENGINE = "player_engine"
    const val DEBUG_INFO = "debug_info"
}

class LivePlayerActivity : BaseActivity() {
    override fun shouldRecreateOnUiScaleChange(): Boolean = true

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var upQuickCard: PlayerUpQuickCardController
    private lateinit var superChatOverlay: LiveSuperChatOverlayController
    private val superChatHistory = LiveSuperChatHistory()
    private val superChatHistoryReturnFocus = FocusReturn()
    private var superChatHistoryPanel: LiveSuperChatHistoryPanelController? = null

    private var player: BlblPlayerEngine? = null
    private var ijkRenderView: View? = null
    private var ijkTextureSurface: Surface? = null
    private val settingsPanelReturnFocus = FocusReturn()
    private val osdFocusReturn = FocusReturn()
    private var autoHideJob: Job? = null
    private var seekHintJob: Job? = null
    private var superChatLoadJob: Job? = null
    private var touchController: PlayerTouchController? = null
    private val shortcutPrevDanmakuOpacityByKey = HashMap<Int, Float>()
    private val shortcutPrevDanmakuTextSizeByKey = HashMap<Int, Float>()
    private val shortcutPrevDanmakuSpeedLevelByKey = HashMap<Int, Int>()
    private val shortcutPrevDanmakuAreaByKey = HashMap<Int, Float>()
    private val shortcutPressController by lazy {
        PlayerCustomShortcutPressController<PlayerCustomShortcutAction, KeyEvent>(
            scope = lifecycleScope,
            longPressTimeoutMillis = ViewConfiguration.getLongPressTimeout().toLong(),
            isEligibleKey = { keyCode ->
                keyCode > 0 &&
                    keyCode != KeyEvent.KEYCODE_UNKNOWN &&
                    !PlayerCustomShortcutsStore.isForbiddenKeyCode(keyCode)
            },
            bindingsForKey = { keyCode ->
                val bindings = BiliClient.prefs.playerCustomShortcuts.filter { it.keyCode == keyCode }
                PlayerCustomShortcutBindings(
                    shortAction =
                        bindings.firstOrNull { it.trigger == PlayerCustomShortcutTrigger.SHORT_PRESS }
                            ?.action
                            ?.takeIf(::isLiveCustomShortcutActionSupported),
                    longAction =
                        bindings.firstOrNull { it.trigger == PlayerCustomShortcutTrigger.LONG_PRESS }
                            ?.action
                            ?.takeIf(::isLiveCustomShortcutActionSupported),
                )
            },
            canDispatch = ::canDispatchLiveCustomShortcut,
            copyEventToken = { source -> KeyEvent(source) },
            executeAction = { keyCode, trigger, action ->
                applyLiveCustomShortcut(
                    keyCode = shortcutMemoryKey(keyCode, trigger),
                    action = action,
                )
            },
        )
    }
    private var debugJob: Job? = null
    private var autoFailoverJob: Job? = null
    private var liveEntryReportedRoomId: Long = 0L
    private var finishOnBackKeyUp: Boolean = false
    private var controlsVisible: Boolean = false
    private var lastInteractionAtMs: Long = 0L
    private var autoFailoverWindowStartAtMs: Long = 0L
    private var autoFailoverSwitchCount: Int = 0
    private var autoFailoverLastSwitchAtMs: Long = 0L
    private var autoFailoverInFlight: Boolean = false
    private var behindLiveWindowWindowStartAtMs: Long = 0L
    private var behindLiveWindowRecoverCount: Int = 0
    private var behindLiveWindowLastRecoverAtMs: Long = 0L
    private var exitRequested: Boolean = false
    private val bufferingOverlayController: PlayerBufferingOverlayController by lazy {
        PlayerBufferingOverlayController(
            context = this,
            bindingProvider = { if (::binding.isInitialized) binding else null },
            scope = lifecycleScope,
            playbackStateProvider = { player?.playbackState },
        )
    }

    private val doubleBackToExit by lazy {
        DoubleBackToExitHandler(context = this, windowMs = BACK_DOUBLE_PRESS_WINDOW_MS) {
            if (controlsVisible) setControlsVisible(false)
        }
    }

    private var roomId: Long = 0L
    private var realRoomId: Long = 0L
    private var roomUid: Long = 0L
    private var roomTitle: String = ""
    private var roomUname: String = ""
    private var roomFace: String? = null

    private var session: LiveSession = LiveSession()
    private val debug = PlayerDebugMetrics()
    @Volatile private var liveHlsDebugInfo: LiveHlsDebugInfo? = null

    private var lastPlay: BiliApi.LivePlayUrl? = null
    private var lastLiveStatus: Int = 0
    private var transientPlaybackResumeRequested: Boolean? = null

    private var messageClient: LiveMessageClient? = null
    private var liveDanmakuBaseUptimeMs: Long = 0L
    private var liveDanmakuLastAppendMs: Int = Int.MIN_VALUE
    private var temporarySuperChatPreviewStarted = false
    private var superChatHistoryConsumedKeyUp: Int = KeyEvent.KEYCODE_UNKNOWN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlayerOsdSizing.applyTheme(this)
        val prefs = BiliClient.prefs
        val playerInflater = PlayerOsdSizing.cloneInflater(this, layoutInflater)
        val root =
            playerInflater.inflate(
                if (prefs.playerRenderViewType == AppPrefs.PLAYER_RENDER_VIEW_TEXTURE_VIEW) blbl.cat3399.R.layout.activity_player_texture else blbl.cat3399.R.layout.activity_player,
                null,
            )
        binding = ActivityPlayerBinding.bind(root)
        upQuickCard =
            PlayerUpQuickCardController(
                activity = this,
                binding = binding,
                isCardVisible = { controlsVisible },
                keepControlsVisible = { setControlsVisible(true) },
                beforeOpenUpDetail = { prepareTransientPlaybackExit() },
            )
        setContentView(binding.root)
        Immersive.apply(this, prefs.fullscreenEnabled)
        PlayerUiMode.applyLive(this, binding)
        resetBufferingOverlayState()

        roomId = intent.getLongExtra(EXTRA_ROOM_ID, 0L)
        roomTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        roomUname = intent.getStringExtra(EXTRA_UNAME).orEmpty()
        if (roomId <= 0L) {
            AppToast.show(this, "缺少 room_id")
            finish()
            return
        }

        val sessionOverrideJson =
            intent.getStringExtra(EXTRA_ENGINE_SWITCH_SESSION_JSON)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        session =
            LiveSession(
                engineKind = PlayerEngineKind.fromPrefValue(prefs.playerEngineKind),
                highBitrateEnabled = prefs.liveHighBitrateEnabled,
                danmaku = DanmakuSessionSettings(
                    enabled = prefs.danmakuEnabled,
                    opacity = prefs.danmakuOpacity,
                    textSizeSp = prefs.danmakuTextSizeSp,
                    fontWeight = DanmakuFontWeight.fromPrefValue(prefs.danmakuFontWeight),
                    strokeWidthPx = prefs.danmakuStrokeWidthPx,
                    speedLevel = prefs.danmakuSpeed,
                    area = prefs.danmakuArea,
                    laneDensity = DanmakuLaneDensity.fromPrefValue(prefs.dan@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package blbl.cat3399.feature.live

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.KeyEvent
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.MarginLayoutParams
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.ui.AspectRatioFrameLayout
import blbl.cat3399.BuildConfig
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.Danmaku
import blbl.cat3399.core.model.LiveSuperChat
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.prefs.PlayerCustomShortcutAction
import blbl.cat3399.core.prefs.PlayerCustomShortcutTrigger
import blbl.cat3399.core.prefs.PlayerCustomShortcutsStore
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.DoubleBackToExitHandler
import blbl.cat3399.core.ui.FocusReturn
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.popup.AppPopup
import blbl.cat3399.core.ui.popup.PopupHost
import blbl.cat3399.databinding.ActivityPlayerBinding
import blbl.cat3399.databinding.ViewLiveSuperChatHistoryPanelBinding
import blbl.cat3399.databinding.ViewLiveSuperChatOverlayBinding
import blbl.cat3399.feature.player.AudioBalanceLevel
import blbl.cat3399.feature.player.PlayerBufferingOverlayController
import blbl.cat3399.feature.player.PlayerCustomShortcutBindings
import blbl.cat3399.feature.player.PlayerCustomShortcutDispatchResult
import blbl.cat3399.feature.player.PlayerCustomShortcutInputPolicy
import blbl.cat3399.feature.player.PlayerCustomShortcutPressController
import blbl.cat3399.feature.player.PlayerDefaultShortPressDispatcher
import blbl.cat3399.feature.player.PlayerDebugMetrics
import blbl.cat3399.feature.player.PlayerOsdSizing
import blbl.cat3399.feature.player.PlayerSettingsAdapter
import blbl.cat3399.feature.player.PlayerTouchController
import blbl.cat3399.feature.player.PlayerTouchGestureHost
import blbl.cat3399.feature.player.PlayerUpQuickCardController
import blbl.cat3399.feature.player.PlayerUiMode
import blbl.cat3399.feature.player.areaText
import blbl.cat3399.feature.player.danmaku.DanmakuSessionSettings
import blbl.cat3399.feature.player.danmaku.DanmakuFontWeight
import blbl.cat3399.feature.player.danmaku.DanmakuLaneDensity
import blbl.cat3399.feature.player.engine.BlblPlayerEngine
import blbl.cat3399.feature.player.engine.ExoPlayerEngine
import blbl.cat3399.feature.player.engine.IjkPlayerPlugin
import blbl.cat3399.feature.player.engine.IjkPlayerPluginUi
import blbl.cat3399.feature.player.engine.IjkPlayerEngine
import blbl.cat3399.feature.player.engine.LiveHlsDebugInfo
import blbl.cat3399.feature.player.engine.PlayerEngineKind
import blbl.cat3399.feature.player.engine.PlaybackSource
import blbl.cat3399.feature.player.requirePlayerTouchOverlayBinding
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private object LivePlayerSettingKeys {
    const val QUALITY = "quality"
    const val LINE = "line"
    const val HIGH_BITRATE = "high_bitrate"
    const val AUDIO_BALANCE = "audio_balance"
    const val PLAYER_ENGINE = "player_engine"
    const val DEBUG_INFO = "debug_info"
}

class LivePlayerActivity : BaseActivity() {
    override fun shouldRecreateOnUiScaleChange(): Boolean = true

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var upQuickCard: PlayerUpQuickCardController
    private lateinit var superChatOverlay: LiveSuperChatOverlayController
    private val superChatHistory = LiveSuperChatHistory()
    private val superChatHistoryReturnFocus = FocusReturn()
    private var superChatHistoryPanel: LiveSuperChatHistoryPanelController? = null

    private var player: BlblPlayerEngine? = null
    private var ijkRenderView: View? = null
    private var ijkTextureSurface: Surface? = null
    private val settingsPanelReturnFocus = FocusReturn()
    private val osdFocusReturn = FocusReturn()
    private var autoHideJob: Job? = null
    private var seekHintJob: Job? = null
    private var superChatLoadJob: Job? = null
    private var touchController: PlayerTouchController? = null
    private val shortcutPrevDanmakuOpacityByKey = HashMap<Int, Float>()
    private val shortcutPrevDanmakuTextSizeByKey = HashMap<Int, Float>()
    private val shortcutPrevDanmakuSpeedLevelByKey = HashMap<Int, Int>()
    private val shortcutPrevDanmakuAreaByKey = HashMap<Int, Float>()
    private val shortcutPressController by lazy {
        PlayerCustomShortcutPressController<PlayerCustomShortcutAction, KeyEvent>(
            scope = lifecycleScope,
            longPressTimeoutMillis = ViewConfiguration.getLongPressTimeout().toLong(),
            isEligibleKey = { keyCode ->
                keyCode > 0 &&
                    keyCode != KeyEvent.KEYCODE_UNKNOWN &&
                    !PlayerCustomShortcutsStore.isForbiddenKeyCode(keyCode)
            },
            bindingsForKey = { keyCode ->
                val bindings = BiliClient.prefs.playerCustomShortcuts.filter { it.keyCode == keyCode }
                PlayerCustomShortcutBindings(
                    shortAction =
                        bindings.firstOrNull { it.trigger == PlayerCustomShortcutTrigger.SHORT_PRESS }
                            ?.action
                            ?.takeIf(::isLiveCustomShortcutActionSupported),
                    longAction =
                        bindings.firstOrNull { it.trigger == PlayerCustomShortcutTrigger.LONG_PRESS }
                            ?.action
                            ?.takeIf(::isLiveCustomShortcutActionSupported),
                )
            },
            canDispatch = ::canDispatchLiveCustomShortcut,
            copyEventToken = { source -> KeyEvent(source) },
            executeAction = { keyCode, trigger, action ->
                applyLiveCustomShortcut(
                    keyCode = shortcutMemoryKey(keyCode, trigger),
                    action = action,
                )
            },
        )
    }
    private var debugJob: Job? = null
    private var autoFailoverJob: Job? = null
    private var liveEntryReportedRoomId: Long = 0L
    private var finishOnBackKeyUp: Boolean = false
    private var controlsVisible: Boolean = false
    private var lastInteractionAtMs: Long = 0L
    private var autoFailoverWindowStartAtMs: Long = 0L
    private var autoFailoverSwitchCount: Int = 0
    private var autoFailoverLastSwitchAtMs: Long = 0L
    private var autoFailoverInFlight: Boolean = false
    private var behindLiveWindowWindowStartAtMs: Long = 0L
    private var behindLiveWindowRecoverCount: Int = 0
    private var behindLiveWindowLastRecoverAtMs: Long = 0L
    private var exitRequested: Boolean = false
    private val bufferingOverlayController: PlayerBufferingOverlayController by lazy {
        PlayerBufferingOverlayController(
            context = this,
            bindingProvider = { if (::binding.isInitialized) binding else null },
            scope = lifecycleScope,
            playbackStateProvider = { player?.playbackState },
        )
    }

    private val doubleBackToExit by lazy {
        DoubleBackToExitHandler(context = this, windowMs = BACK_DOUBLE_PRESS_WINDOW_MS) {
            if (controlsVisible) setControlsVisible(false)
        }
    }

    private var roomId: Long = 0L
    private var realRoomId: Long = 0L
    private var roomUid: Long = 0L
    private var roomTitle: String = ""
    private var roomUname: String = ""
    private var roomFace: String? = null

    private var session: LiveSession = LiveSession()
    private val debug = PlayerDebugMetrics()
    @Volatile private var liveHlsDebugInfo: LiveHlsDebugInfo? = null

    private var lastPlay: BiliApi.LivePlayUrl? = null
    private var lastLiveStatus: Int = 0
    private var transientPlaybackResumeRequested: Boolean? = null

    private var messageClient: LiveMessageClient? = null
    private var liveDanmakuBaseUptimeMs: Long = 0L
    private var liveDanmakuLastAppendMs: Int = Int.MIN_VALUE
    private var temporarySuperChatPreviewStarted = false
    private var superChatHistoryConsumedKeyUp: Int = KeyEvent.KEYCODE_UNKNOWN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlayerOsdSizing.applyTheme(this)
        val prefs = BiliClient.prefs
        val playerInflater = PlayerOsdSizing.cloneInflater(this, layoutInflater)
        val root =
            playerInflater.inflate(
                if (prefs.playerRenderViewType == AppPrefs.PLAYER_RENDER_VIEW_TEXTURE_VIEW) blbl.cat3399.R.layout.activity_player_texture else blbl.cat3399.R.layout.activity_player,
                null,
            )
        binding = ActivityPlayerBinding.bind(root)
        upQuickCard =
            PlayerUpQuickCardController(
                activity = this,
                binding = binding,
                isCardVisible = { controlsVisible },
                keepControlsVisible = { setControlsVisible(true) },
                beforeOpenUpDetail = { prepareTransientPlaybackExit() },
            )
        setContentView(binding.root)
        Immersive.apply(this, prefs.fullscreenEnabled)
        PlayerUiMode.applyLive(this, binding)
        resetBufferingOverlayState()

        roomId = intent.getLongExtra(EXTRA_ROOM_ID, 0L)
        roomTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        roomUname = intent.getStringExtra(EXTRA_UNAME).orEmpty()
        if (roomId <= 0L) {
            AppToast.show(this, "缺少 room_id")
            finish()
            return
        }

        val sessionOverrideJson =
            intent.getStringExtra(EXTRA_ENGINE_SWITCH_SESSION_JSON)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        session =
            LiveSession(
                engineKind = PlayerEngineKind.fromPrefValue(prefs.playerEngineKind),
                highBitrateEnabled = prefs.liveHighBitrateEnabled,
                danmaku = DanmakuSessionSettings(
                    enabled = prefs.danmakuEnabled,
                    opacity = prefs.danmakuOpacity,
                    textSizeSp = prefs.danmakuTextSizeSp,
                    fontWeight = DanmakuFontWeight.fromPrefValue(prefs.danmakuFontWeight),
                    strokeWidthPx = prefs.danmakuStrokeWidthPx,
                    speedLevel = prefs.danmakuSpeed,
                    area = prefs.danmakuArea,
                    laneDensity = DanmakuLaneDensity.fromPrefValue(prefs.dan@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package blbl.cat3399.feature.live

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.KeyEvent
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.MarginLayoutParams
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.ui.AspectRatioFrameLayout
import blbl.cat3399.BuildConfig
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.Danmaku
import blbl.cat3399.core.model.LiveSuperChat
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.prefs.PlayerCustomShortcutAction
import blbl.cat3399.core.prefs.PlayerCustomShortcutTrigger
import blbl.cat3399.core.prefs.PlayerCustomShortcutsStore
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.DoubleBackToExitHandler
import blbl.cat3399.core.ui.FocusReturn
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.popup.AppPopup
import blbl.cat3399.core.ui.popup.PopupHost
import blbl.cat3399.databinding.ActivityPlayerBinding
import blbl.cat3399.databinding.ViewLiveSuperChatHistoryPanelBinding
import blbl.cat3399.databinding.ViewLiveSuperChatOverlayBinding
import blbl.cat3399.feature.player.AudioBalanceLevel
import blbl.cat3399.feature.player.PlayerBufferingOverlayController
import blbl.cat3399.feature.player.PlayerCustomShortcutBindings
import blbl.cat3399.feature.player.PlayerCustomShortcutDispatchResult
import blbl.cat3399.feature.player.PlayerCustomShortcutInputPolicy
import blbl.cat3399.feature.player.PlayerCustomShortcutPressController
import blbl.cat3399.feature.player.PlayerDefaultShortPressDispatcher
import blbl.cat3399.feature.player.PlayerDebugMetrics
import blbl.cat3399.feature.player.PlayerOsdSizing
import blbl.cat3399.feature.player.PlayerSettingsAdapter
import blbl.cat3399.feature.player.PlayerTouchController
import blbl.cat3399.feature.player.PlayerTouchGestureHost
import blbl.cat3399.feature.player.PlayerUpQuickCardController
import blbl.cat3399.feature.player.PlayerUiMode
import blbl.cat3399.feature.player.areaText
import blbl.cat3399.feature.player.danmaku.DanmakuSessionSettings
import blbl.cat3399.feature.player.danmaku.DanmakuFontWeight
import blbl.cat3399.feature.player.danmaku.DanmakuLaneDensity
import blbl.cat3399.feature.player.engine.BlblPlayerEngine
import blbl.cat3399.feature.player.engine.ExoPlayerEngine
import blbl.cat3399.feature.player.engine.IjkPlayerPlugin
import blbl.cat3399.feature.player.engine.IjkPlayerPluginUi
import blbl.cat3399.feature.player.engine.IjkPlayerEngine
import blbl.cat3399.feature.player.engine.LiveHlsDebugInfo
import blbl.cat3399.feature.player.engine.PlayerEngineKind
import blbl.cat3399.feature.player.engine.PlaybackSource
import blbl.cat3399.feature.player.requirePlayerTouchOverlayBinding
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private object LivePlayerSettingKeys {
    const val QUALITY = "quality"
    const val LINE = "line"
    const val HIGH_BITRATE = "high_bitrate"
    const val AUDIO_BALANCE = "audio_balance"
    const val PLAYER_ENGINE = "player_engine"
    const val DEBUG_INFO = "debug_info"
}

class LivePlayerActivity : BaseActivity() {
    override fun shouldRecreateOnUiScaleChange(): Boolean = true

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var upQuickCard: PlayerUpQuickCardController
    private lateinit var superChatOverlay: LiveSuperChatOverlayController
    private val superChatHistory = LiveSuperChatHistory()
    private val superChatHistoryReturnFocus = FocusReturn()
    private var superChatHistoryPanel: LiveSuperChatHistoryPanelController? = null

    private var player: BlblPlayerEngine? = null
    private var ijkRenderView: View? = null
    private var ijkTextureSurface: Surface? = null
    private val settingsPanelReturnFocus = FocusReturn()
    private val osdFocusReturn = FocusReturn()
    private var autoHideJob: Job? = null
    private var seekHintJob: Job? = null
    private var superChatLoadJob: Job? = null
    private var touchController: PlayerTouchController? = null
    private val shortcutPrevDanmakuOpacityByKey = HashMap<Int, Float>()
    private val shortcutPrevDanmakuTextSizeByKey = HashMap<Int, Float>()
    private val shortcutPrevDanmakuSpeedLevelByKey = HashMap<Int, Int>()
    private val shortcutPrevDanmakuAreaByKey = HashMap<Int, Float>()
    private val shortcutPressController by lazy {
        PlayerCustomShortcutPressController<PlayerCustomShortcutAction, KeyEvent>(
            scope = lifecycleScope,
            longPressTimeoutMillis = ViewConfiguration.getLongPressTimeout().toLong(),
            isEligibleKey = { keyCode ->
                keyCode > 0 &&
                    keyCode != KeyEvent.KEYCODE_UNKNOWN &&
                    !PlayerCustomShortcutsStore.isForbiddenKeyCode(keyCode)
            },
            bindingsForKey = { keyCode ->
                val bindings = BiliClient.prefs.playerCustomShortcuts.filter { it.keyCode == keyCode }
                PlayerCustomShortcutBindings(
                    shortAction =
                        bindings.firstOrNull { it.trigger == PlayerCustomShortcutTrigger.SHORT_PRESS }
                            ?.action
                            ?.takeIf(::isLiveCustomShortcutActionSupported),
                    longAction =
                        bindings.firstOrNull { it.trigger == PlayerCustomShortcutTrigger.LONG_PRESS }
                            ?.action
                            ?.takeIf(::isLiveCustomShortcutActionSupported),
                )
            },
            canDispatch = ::canDispatchLiveCustomShortcut,
            copyEventToken = { source -> KeyEvent(source) },
            executeAction = { keyCode, trigger, action ->
                applyLiveCustomShortcut(
                    keyCode = shortcutMemoryKey(keyCode, trigger),
                    action = action,
                )
            },
        )
    }
    private var debugJob: Job? = null
    private var autoFailoverJob: Job? = null
    private var liveEntryReportedRoomId: Long = 0L
    private var finishOnBackKeyUp: Boolean = false
    private var controlsVisible: Boolean = false
    private var lastInteractionAtMs: Long = 0L
    private var autoFailoverWindowStartAtMs: Long = 0L
    private var autoFailoverSwitchCount: Int = 0
    private var autoFailoverLastSwitchAtMs: Long = 0L
    private var autoFailoverInFlight: Boolean = false
    private var behindLiveWindowWindowStartAtMs: Long = 0L
    private var behindLiveWindowRecoverCount: Int = 0
    private var behindLiveWindowLastRecoverAtMs: Long = 0L
    private var exitRequested: Boolean = false
    private val bufferingOverlayController: PlayerBufferingOverlayController by lazy {
        PlayerBufferingOverlayController(
            context = this,
            bindingProvider = { if (::binding.isInitialized) binding else null },
            scope = lifecycleScope,
            playbackStateProvider = { player?.playbackState },
        )
    }

    private val doubleBackToExit by lazy {
        DoubleBackToExitHandler(context = this, windowMs = BACK_DOUBLE_PRESS_WINDOW_MS) {
            if (controlsVisible) setControlsVisible(false)
        }
    }

    private var roomId: Long = 0L
    private var realRoomId: Long = 0L
    private var roomUid: Long = 0L
    private var roomTitle: String = ""
    private var roomUname: String = ""
    private var roomFace: String? = null

    private var session: LiveSession = LiveSession()
    private val debug = PlayerDebugMetrics()
    @Volatile private var liveHlsDebugInfo: LiveHlsDebugInfo? = null

    private var lastPlay: BiliApi.LivePlayUrl? = null
    private var lastLiveStatus: Int = 0
    private var transientPlaybackResumeRequested: Boolean? = null

    private var messageClient: LiveMessageClient? = null
    private var liveDanmakuBaseUptimeMs: Long = 0L
    private var liveDanmakuLastAppendMs: Int = Int.MIN_VALUE
    private var temporarySuperChatPreviewStarted = false
    private var superChatHistoryConsumedKeyUp: Int = KeyEvent.KEYCODE_UNKNOWN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlayerOsdSizing.applyTheme(this)
        val prefs = BiliClient.prefs
        val playerInflater = PlayerOsdSizing.cloneInflater(this, layoutInflater)
        val root =
            playerInflater.inflate(
                if (prefs.playerRenderViewType == AppPrefs.PLAYER_RENDER_VIEW_TEXTURE_VIEW) blbl.cat3399.R.layout.activity_player_texture else blbl.cat3399.R.layout.activity_player,
                null,
            )
        binding = ActivityPlayerBinding.bind(root)
        upQuickCard =
            PlayerUpQuickCardController(
                activity = this,
                binding = binding,
                isCardVisible = { controlsVisible },
                keepControlsVisible = { setControlsVisible(true) },
                beforeOpenUpDetail = { prepareTransientPlaybackExit() },
            )
        setContentView(binding.root)
        Immersive.apply(this, prefs.fullscreenEnabled)
        PlayerUiMode.applyLive(this, binding)
        resetBufferingOverlayState()

        roomId = intent.getLongExtra(EXTRA_ROOM_ID, 0L)
        roomTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        roomUname = intent.getStringExtra(EXTRA_UNAME).orEmpty()
        if (roomId <= 0L) {
            AppToast.show(this, "缺少 room_id")
            finish()
            return
        }

        val sessionOverrideJson =
            intent.getStringExtra(EXTRA_ENGINE_SWITCH_SESSION_JSON)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        session =
            LiveSession(
                engineKind = PlayerEngineKind.fromPrefValue(prefs.playerEngineKind),
                highBitrateEnabled = prefs.liveHighBitrateEnabled,
                danmaku = DanmakuSessionSettings(
                    enabled = prefs.danmakuEnabled,
                    opacity = prefs.danmakuOpacity,
                    textSizeSp = prefs.danmakuTextSizeSp,
                    fontWeight = DanmakuFontWeight.fromPrefValue(prefs.danmakuFontWeight),
                    strokeWidthPx = prefs.danmakuStrokeWidthPx,
                    speedLevel = prefs.danmakuSpeed,
                    area = prefs.danmakuArea,
                    laneDensity = DanmakuLaneDensity.fromPrefValue(prefs.dan@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package blbl.cat3399.feature.live

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.KeyEvent
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.MarginLayoutParams
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.ui.AspectRatioFrameLayout
import blbl.cat3399.BuildConfig
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.Danmaku
import blbl.cat3399.core.model.LiveSuperChat
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.prefs.PlayerCustomShortcutAction
import blbl.cat3399.core.prefs.PlayerCustomShortcutTrigger
import blbl.cat3399.core.prefs.PlayerCustomShortcutsStore
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.DoubleBackToExitHandler
import blbl.cat3399.core.ui.FocusReturn
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.popup.AppPopup
import blbl.cat3399.core.ui.popup.PopupHost
import blbl.cat3399.databinding.ActivityPlayerBinding
import blbl.cat3399.databinding.ViewLiveSuperChatHistoryPanelBinding
import blbl.cat3399.databinding.ViewLiveSuperChatOverlayBinding
import blbl.cat3399.feature.player.AudioBalanceLevel
import blbl.cat3399.feature.player.PlayerBufferingOverlayController
import blbl.cat3399.feature.player.PlayerCustomShortcutBindings
import blbl.cat3399.feature.player.PlayerCustomShortcutDispatchResult
import blbl.cat3399.feature.player.PlayerCustomShortcutInputPolicy
import blbl.cat3399.feature.player.PlayerCustomShortcutPressController
import blbl.cat3399.feature.player.PlayerDefaultShortPressDispatcher
import blbl.cat3399.feature.player.PlayerDebugMetrics
import blbl.cat3399.feature.player.PlayerOsdSizing
import blbl.cat3399.feature.player.PlayerSettingsAdapter
import blbl.cat3399.feature.player.PlayerTouchController
import blbl.cat3399.feature.player.PlayerTouchGestureHost
import blbl.cat3399.feature.player.PlayerUpQuickCardController
import blbl.cat3399.feature.player.PlayerUiMode
import blbl.cat3399.feature.player.areaText
import blbl.cat3399.feature.player.danmaku.DanmakuSessionSettings
import blbl.cat3399.feature.player.danmaku.DanmakuFontWeight
import blbl.cat3399.feature.player.danmaku.DanmakuLaneDensity
import blbl.cat3399.feature.player.engine.BlblPlayerEngine
import blbl.cat3399.feature.player.engine.ExoPlayerEngine
import blbl.cat3399.feature.player.engine.IjkPlayerPlugin
import blbl.cat3399.feature.player.engine.IjkPlayerPluginUi
import blbl.cat3399.feature.player.engine.IjkPlayerEngine
import blbl.cat3399.feature.player.engine.LiveHlsDebugInfo
import blbl.cat3399.feature.player.engine.PlayerEngineKind
import blbl.cat3399.feature.player.engine.PlaybackSource
import blbl.cat3399.feature.player.requirePlayerTouchOverlayBinding
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private object LivePlayerSettingKeys {
    const val QUALITY = "quality"
    const val LINE = "line"
    const val HIGH_BITRATE = "high_bitrate"
    const val AUDIO_BALANCE = "audio_balance"
    const val PLAYER_ENGINE = "player_engine"
    const val DEBUG_INFO = "debug_info"
}

class LivePlayerActivity : BaseActivity() {
    override fun shouldRecreateOnUiScaleChange(): Boolean = true

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var upQuickCard: PlayerUpQuickCardController
    private lateinit var superChatOverlay: LiveSuperChatOverlayController
    private val superChatHistory = LiveSuperChatHistory()
    private val superChatHistoryReturnFocus = FocusReturn()
    private var superChatHistoryPanel: LiveSuperChatHistoryPanelController? = null

    private var player: BlblPlayerEngine? = null
    private var ijkRenderView: View? = null
    private var ijkTextureSurface: Surface? = null
    private val settingsPanelReturnFocus = FocusReturn()
    private val osdFocusReturn = FocusReturn()
    private var autoHideJob: Job? = null
    private var seekHintJob: Job? = null
    private var superChatLoadJob: Job? = null
    private var touchController: PlayerTouchController? = null
    private val shortcutPrevDanmakuOpacityByKey = HashMap<Int, Float>()
    private val shortcutPrevDanmakuTextSizeByKey = HashMap<Int, Float>()
    private val shortcutPrevDanmakuSpeedLevelByKey = HashMap<Int, Int>()
    private val shortcutPrevDanmakuAreaByKey = HashMap<Int, Float>()
    private val shortcutPressController by lazy {
        PlayerCustomShortcutPressController<PlayerCustomShortcutAction, KeyEvent>(
            scope = lifecycleScope,
            longPressTimeoutMillis = ViewConfiguration.getLongPressTimeout().toLong(),
            isEligibleKey = { keyCode ->
                keyCode > 0 &&
                    keyCode != KeyEvent.KEYCODE_UNKNOWN &&
                    !PlayerCustomShortcutsStore.isForbiddenKeyCode(keyCode)
            },
            bindingsForKey = { keyCode ->
                val bindings = BiliClient.prefs.playerCustomShortcuts.filter { it.keyCode == keyCode }
                PlayerCustomShortcutBindings(
                    shortAction =
                        bindings.firstOrNull { it.trigger == PlayerCustomShortcutTrigger.SHORT_PRESS }
                            ?.action
                            ?.takeIf(::isLiveCustomShortcutActionSupported),
                    longAction =
                        bindings.firstOrNull { it.trigger == PlayerCustomShortcutTrigger.LONG_PRESS }
                            ?.action
                            ?.takeIf(::isLiveCustomShortcutActionSupported),
                )
            },
            canDispatch = ::canDispatchLiveCustomShortcut,
            copyEventToken = { source -> KeyEvent(source) },
            executeAction = { keyCode, trigger, action ->
                applyLiveCustomShortcut(
                    keyCode = shortcutMemoryKey(keyCode, trigger),
                    action = action,
                )
            },
        )
    }
    private var debugJob: Job? = null
    private var autoFailoverJob: Job? = null
    private var liveEntryReportedRoomId: Long = 0L
    private var finishOnBackKeyUp: Boolean = false
    private var controlsVisible: Boolean = false
    private var lastInteractionAtMs: Long = 0L
    private var autoFailoverWindowStartAtMs: Long = 0L
    private var autoFailoverSwitchCount: Int = 0
    private var autoFailoverLastSwitchAtMs: Long = 0L
    private var autoFailoverInFlight: Boolean = false
    private var behindLiveWindowWindowStartAtMs: Long = 0L
    private var behindLiveWindowRecoverCount: Int = 0
    private var behindLiveWindowLastRecoverAtMs: Long = 0L
    private var exitRequested: Boolean = false
    private val bufferingOverlayController: PlayerBufferingOverlayController by lazy {
        PlayerBufferingOverlayController(
            context = this,
            bindingProvider = { if (::binding.isInitialized) binding else null },
            scope = lifecycleScope,
            playbackStateProvider = { player?.playbackState },
        )
    }

    private val doubleBackToExit by lazy {
        DoubleBackToExitHandler(context = this, windowMs = BACK_DOUBLE_PRESS_WINDOW_MS) {
            if (controlsVisible) setControlsVisible(false)
        }
    }

    private var roomId: Long = 0L
    private var realRoomId: Long = 0L
    private var roomUid: Long = 0L
    private var roomTitle: String = ""
    private var roomUname: String = ""
    private var roomFace: String? = null

    private var session: LiveSession = LiveSession()
    private val debug = PlayerDebugMetrics()
    @Volatile private var liveHlsDebugInfo: LiveHlsDebugInfo? = null

    private var lastPlay: BiliApi.LivePlayUrl? = null
    private var lastLiveStatus: Int = 0
    private var transientPlaybackResumeRequested: Boolean? = null

    private var messageClient: LiveMessageClient? = null
    private var liveDanmakuBaseUptimeMs: Long = 0L
    private var liveDanmakuLastAppendMs: Int = Int.MIN_VALUE
    private var temporarySuperChatPreviewStarted = false
    private var superChatHistoryConsumedKeyUp: Int = KeyEvent.KEYCODE_UNKNOWN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlayerOsdSizing.applyTheme(this)
        val prefs = BiliClient.prefs
        val playerInflater = PlayerOsdSizing.cloneInflater(this, layoutInflater)
        val root =
            playerInflater.inflate(
                if (prefs.playerRenderViewType == AppPrefs.PLAYER_RENDER_VIEW_TEXTURE_VIEW) blbl.cat3399.R.layout.activity_player_texture else blbl.cat3399.R.layout.activity_player,
                null,
            )
        binding = ActivityPlayerBinding.bind(root)
        upQuickCard =
            PlayerUpQuickCardController(
                activity = this,
                binding = binding,
                isCardVisible = { controlsVisible },
                keepControlsVisible = { setControlsVisible(true) },
                beforeOpenUpDetail = { prepareTransientPlaybackExit() },
            )
        setContentView(binding.root)
        Immersive.apply(this, prefs.fullscreenEnabled)
        PlayerUiMode.applyLive(this, binding)
        resetBufferingOverlayState()

        roomId = intent.getLongExtra(EXTRA_ROOM_ID, 0L)
        roomTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        roomUname = intent.getStringExtra(EXTRA_UNAME).orEmpty()
        if (roomId <= 0L) {
            AppToast.show(this, "缺少 room_id")
            finish()
            return
        }

        val sessionOverrideJson =
            intent.getStringExtra(EXTRA_ENGINE_SWITCH_SESSION_JSON)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        session =
            LiveSession(
                engineKind = PlayerEngineKind.fromPrefValue(prefs.playerEngineKind),
                highBitrateEnabled = prefs.liveHighBitrateEnabled,
                danmaku = DanmakuSessionSettings(
                    enabled = prefs.danmakuEnabled,
                    opacity = prefs.danmakuOpacity,
                    textSizeSp = prefs.danmakuTextSizeSp,
                    fontWeight = DanmakuFontWeight.fromPrefValue(prefs.danmakuFontWeight),
                    strokeWidthPx = prefs.danmakuStrokeWidthPx,
                    speedLevel = prefs.danmakuSpeed,
                    area = prefs.danmakuArea,
                    laneDensity = DanmakuLaneDensity.fromPrefValue(prefs.dan@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package blbl.cat3399.feature.live

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.KeyEvent
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.MarginLayoutParams
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.ui.AspectRatioFrameLayout
import blbl.cat3399.BuildConfig
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.Danmaku
import blbl.cat3399.core.model.LiveSuperChat
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.prefs.PlayerCustomShortcutAction
import blbl.cat3399.core.prefs.PlayerCustomShortcutTrigger
import blbl.cat3399.core.prefs.PlayerCustomShortcutsStore
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.DoubleBackToExitHandler
import blbl.cat3399.core.ui.FocusReturn
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.popup.AppPopup
import blbl.cat3399.core.ui.popup.PopupHost
import blbl.cat3399.databinding.ActivityPlayerBinding
import blbl.cat3399.databinding.ViewLiveSuperChatHistoryPanelBinding
import blbl.cat3399.databinding.ViewLiveSuperChatOverlayBinding
import blbl.cat3399.feature.player.AudioBalanceLevel
import blbl.cat3399.feature.player.PlayerBufferingOverlayController
import blbl.cat3399.feature.player.PlayerCustomShortcutBindings
import blbl.cat3399.feature.player.PlayerCustomShortcutDispatchResult
import blbl.cat3399.feature.player.PlayerCustomShortcutInputPolicy
import blbl.cat3399.feature.player.PlayerCustomShortcutPressController
import blbl.cat3399.feature.player.PlayerDefaultShortPressDispatcher
import blbl.cat3399.feature.player.PlayerDebugMetrics
import blbl.cat3399.feature.player.PlayerOsdSizing
import blbl.cat3399.feature.player.PlayerSettingsAdapter
import blbl.cat3399.feature.player.PlayerTouchController
import blbl.cat3399.feature.player.PlayerTouchGestureHost
import blbl.cat3399.feature.player.PlayerUpQuickCardController
import blbl.cat3399.feature.player.PlayerUiMode
import blbl.cat3399.feature.player.areaText
import blbl.cat3399.feature.player.danmaku.DanmakuSessionSettings
import blbl.cat3399.feature.player.danmaku.DanmakuFontWeight
import blbl.cat3399.feature.player.danmaku.DanmakuLaneDensity
import blbl.cat3399.feature.player.engine.BlblPlayerEngine
import blbl.cat3399.feature.player.engine.ExoPlayerEngine
import blbl.cat3399.feature.player.engine.IjkPlayerPlugin
import blbl.cat3399.feature.player.engine.IjkPlayerPluginUi
import blbl.cat3399.feature.player.engine.IjkPlayerEngine
import blbl.cat3399.feature.player.engine.LiveHlsDebugInfo
import blbl.cat3399.feature.player.engine.PlayerEngineKind
import blbl.cat3399.feature.player.engine.PlaybackSource
import blbl.cat3399.feature.player.requirePlayerTouchOverlayBinding
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private object LivePlayerSettingKeys {
    const val QUALITY = "quality"
    const val LINE = "line"
    const val HIGH_BITRATE = "high_bitrate"
    const val AUDIO_BALANCE = "audio_balance"
    const val PLAYER_ENGINE = "player_engine"
    const val DEBUG_INFO = "debug_info"
}

class LivePlayerActivity : BaseActivity() {
    override fun shouldRecreateOnUiScaleChange(): Boolean = true

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var upQuickCard: PlayerUpQuickCardController
    private lateinit var superChatOverlay: LiveSuperChatOverlayController
    private val superChatHistory = LiveSuperChatHistory()
    private val superChatHistoryReturnFocus = FocusReturn()
    private var superChatHistoryPanel: LiveSuperChatHistoryPanelController? = null

    private var player: BlblPlayerEngine? = null
    private var ijkRenderView: View? = null
    private var ijkTextureSurface: Surface? = null
    private val settingsPanelReturnFocus = FocusReturn()
    private val osdFocusReturn = FocusReturn()
    private var autoHideJob: Job? = null
    private var seekHintJob: Job? = null
    private var superChatLoadJob: Job? = null
    private var touchController: PlayerTouchController? = null
    private val shortcutPrevDanmakuOpacityByKey = HashMap<Int, Float>()
    private val shortcutPrevDanmakuTextSizeByKey = HashMap<Int, Float>()
    private val shortcutPrevDanmakuSpeedLevelByKey = HashMap<Int, Int>()
    private val shortcutPrevDanmakuAreaByKey = HashMap<Int, Float>()
    private val shortcutPressController by lazy {
        PlayerCustomShortcutPressController<PlayerCustomShortcutAction, KeyEvent>(
            scope = lifecycleScope,
            longPressTimeoutMillis = ViewConfiguration.getLongPressTimeout().toLong(),
            isEligibleKey = { keyCode ->
                keyCode > 0 &&
                    keyCode != KeyEvent.KEYCODE_UNKNOWN &&
                    !PlayerCustomShortcutsStore.isForbiddenKeyCode(keyCode)
            },
            bindingsForKey = { keyCode ->
                val bindings = BiliClient.prefs.playerCustomShortcuts.filter { it.keyCode == keyCode }
                PlayerCustomShortcutBindings(
                    shortAction =
                        bindings.firstOrNull { it.trigger == PlayerCustomShortcutTrigger.SHORT_PRESS }
                            ?.action
                            ?.takeIf(::isLiveCustomShortcutActionSupported),
                    longAction =
                        bindings.firstOrNull { it.trigger == PlayerCustomShortcutTrigger.LONG_PRESS }
                            ?.action
                            ?.takeIf(::isLiveCustomShortcutActionSupported),
                )
            },
            canDispatch = ::canDispatchLiveCustomShortcut,
            copyEventToken = { source -> KeyEvent(source) },
            executeAction = { keyCode, trigger, action ->
                applyLiveCustomShortcut(
                    keyCode = shortcutMemoryKey(keyCode, trigger),
                    action = action,
                )
            },
        )
    }
    private var debugJob: Job? = null
    private var autoFailoverJob: Job? = null
    private var liveEntryReportedRoomId: Long = 0L
    private var finishOnBackKeyUp: Boolean = false
    private var controlsVisible: Boolean = false
    private var lastInteractionAtMs: Long = 0L
    private var autoFailoverWindowStartAtMs: Long = 0L
    private var autoFailoverSwitchCount: Int = 0
    private var autoFailoverLastSwitchAtMs: Long = 0L
    private var autoFailoverInFlight: Boolean = false
    private var behindLiveWindowWindowStartAtMs: Long = 0L
    private var behindLiveWindowRecoverCount: Int = 0
    private var behindLiveWindowLastRecoverAtMs: Long = 0L
    private var exitRequested: Boolean = false
    private val bufferingOverlayController: PlayerBufferingOverlayController by lazy {
        PlayerBufferingOverlayController(
            context = this,
            bindingProvider = { if (::binding.isInitialized) binding else null },
            scope = lifecycleScope,
            playbackStateProvider = { player?.playbackState },
        )
    }

    private val doubleBackToExit by lazy {
        DoubleBackToExitHandler(context = this, windowMs = BACK_DOUBLE_PRESS_WINDOW_MS) {
            if (controlsVisible) setControlsVisible(false)
        }
    }

    private var roomId: Long = 0L
    private var realRoomId: Long = 0L
    private var roomUid: Long = 0L
    private var roomTitle: String = ""
    private var roomUname: String = ""
    private var roomFace: String? = null

    private var session: LiveSession = LiveSession()
    private val debug = PlayerDebugMetrics()
    @Volatile private var liveHlsDebugInfo: LiveHlsDebugInfo? = null

    private var lastPlay: BiliApi.LivePlayUrl? = null
    private var lastLiveStatus: Int = 0
    private var transientPlaybackResumeRequested: Boolean? = null

    private var messageClient: LiveMessageClient? = null
    private var liveDanmakuBaseUptimeMs: Long = 0L
    private var liveDanmakuLastAppendMs: Int = Int.MIN_VALUE
    private var temporarySuperChatPreviewStarted = false
    private var superChatHistoryConsumedKeyUp: Int = KeyEvent.KEYCODE_UNKNOWN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlayerOsdSizing.applyTheme(this)
        val prefs = BiliClient.prefs
        val playerInflater = PlayerOsdSizing.cloneInflater(this, layoutInflater)
        val root =
            playerInflater.inflate(
                if (prefs.playerRenderViewType == AppPrefs.PLAYER_RENDER_VIEW_TEXTURE_VIEW) blbl.cat3399.R.layout.activity_player_texture else blbl.cat3399.R.layout.activity_player,
                null,
            )
        binding = ActivityPlayerBinding.bind(root)
        upQuickCard =
            PlayerUpQuickCardController(
                activity = this,
                binding = binding,
                isCardVisible = { controlsVisible },
                keepControlsVisible = { setControlsVisible(true) },
                beforeOpenUpDetail = { prepareTransientPlaybackExit() },
            )
        setContentView(binding.root)
        Immersive.apply(this, prefs.fullscreenEnabled)
        PlayerUiMode.applyLive(this, binding)
        resetBufferingOverlayState()

        roomId = intent.getLongExtra(EXTRA_ROOM_ID, 0L)
        roomTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        roomUname = intent.getStringExtra(EXTRA_UNAME).orEmpty()
        if (roomId <= 0L) {
            AppToast.show(this, "缺少 room_id")
            finish()
            return
        }

        val sessionOverrideJson =
            intent.getStringExtra(EXTRA_ENGINE_SWITCH_SESSION_JSON)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        session =
            LiveSession(
                engineKind = PlayerEngineKind.fromPrefValue(prefs.playerEngineKind),
                highBitrateEnabled = prefs.liveHighBitrateEnabled,
                danmaku = DanmakuSessionSettings(
                    enabled = prefs.danmakuEnabled,
                    opacity = prefs.danmakuOpacity,
                    textSizeSp = prefs.danmakuTextSizeSp,
                    fontWeight = DanmakuFontWeight.fromPrefValue(prefs.danmakuFontWeight),
                    strokeWidthPx = prefs.danmakuStrokeWidthPx,
                    speedLevel = prefs.danmakuSpeed,
                    area = prefs.danmakuArea,
                    laneDensity = DanmakuLaneDensity.fromPrefValue(prefs.dan@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package blbl.cat3399.feature.live

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.KeyEvent
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.MarginLayoutParams
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.ui.AspectRatioFrameLayout
import blbl.cat3399.BuildConfig
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.Danmaku
import blbl.cat3399.core.model.LiveSuperChat
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.prefs.PlayerCustomShortcutAction
import blbl.cat3399.core.prefs.PlayerCustomShortcutTrigger
import blbl.cat3399.core.prefs.PlayerCustomShortcutsStore
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.DoubleBackToExitHandler
import blbl.cat3399.core.ui.FocusReturn
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.popup.AppPopup
import blbl.cat3399.core.ui.popup.PopupHost
import blbl.cat3399.databinding.ActivityPlayerBinding
import blbl.cat3399.databinding.ViewLiveSuperChatHistoryPanelBinding
import blbl.cat3399.databinding.ViewLiveSuperChatOverlayBinding
import blbl.cat3399.feature.player.AudioBalanceLevel
import blbl.cat3399.feature.player.PlayerBufferingOverlayController
import blbl.cat3399.feature.player.PlayerCustomShortcutBindings
import blbl.cat3399.feature.player.PlayerCustomShortcutDispatchResult
import blbl.cat3399.feature.player.PlayerCustomShortcutInputPolicy
import blbl.cat3399.feature.player.PlayerCustomShortcutPressController
import blbl.cat3399.feature.player.PlayerDefaultShortPressDispatcher
import blbl.cat3399.feature.player.PlayerDebugMetrics
import blbl.cat3399.feature.player.PlayerOsdSizing
import blbl.cat3399.feature.player.PlayerSettingsAdapter
import blbl.cat3399.feature.player.PlayerTouchController
import blbl.cat3399.feature.player.PlayerTouchGestureHost
import blbl.cat3399.feature.player.PlayerUpQuickCardController
import blbl.cat3399.feature.player.PlayerUiMode
import blbl.cat3399.feature.player.areaText
import blbl.cat3399.feature.player.danmaku.DanmakuSessionSettings
import blbl.cat3399.feature.player.danmaku.DanmakuFontWeight
import blbl.cat3399.feature.player.danmaku.DanmakuLaneDensity
import blbl.cat3399.feature.player.engine.BlblPlayerEngine
import blbl.cat3399.feature.player.engine.ExoPlayerEngine
import blbl.cat3399.feature.player.engine.IjkPlayerPlugin
import blbl.cat3399.feature.player.engine.IjkPlayerPluginUi
import blbl.cat3399.feature.player.engine.IjkPlayerEngine
import blbl.cat3399.feature.player.engine.LiveHlsDebugInfo
import blbl.cat3399.feature.player.engine.PlayerEngineKind
import blbl.cat3399.feature.player.engine.PlaybackSource
import blbl.cat3399.feature.player.requirePlayerTouchOverlayBinding
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private object LivePlayerSettingKeys {
    const val QUALITY = "quality"
    const val LINE = "line"
    const val HIGH_BITRATE = "high_bitrate"
    const val AUDIO_BALANCE = "audio_balance"
    const val PLAYER_ENGINE = "player_engine"
    const val DEBUG_INFO = "debug_info"
}

class LivePlayerActivity : BaseActivity() {
    override fun shouldRecreateOnUiScaleChange(): Boolean = true

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var upQuickCard: PlayerUpQuickCardController
    private lateinit var superChatOverlay: LiveSuperChatOverlayController
    private val superChatHistory = LiveSuperChatHistory()
    private val superChatHistoryReturnFocus = FocusReturn()
    private var superChatHistoryPanel: LiveSuperChatHistoryPanelController? = null

    private var player: BlblPlayerEngine? = null
    private var ijkRenderView: View? = null
    private var ijkTextureSurface: Surface? = null
    private val settingsPanelReturnFocus = FocusReturn()
    private val osdFocusReturn = FocusReturn()
    private var autoHideJob: Job? = null
    private var seekHintJob: Job? = null
    private var superChatLoadJob: Job? = null
    private var touchController: PlayerTouchController? = null
    private val shortcutPrevDanmakuOpacityByKey = HashMap<Int, Float>()
    private val shortcutPrevDanmakuTextSizeByKey = HashMap<Int, Float>()
    private val shortcutPrevDanmakuSpeedLevelByKey = HashMap<Int, Int>()
    private val shortcutPrevDanmakuAreaByKey = HashMap<Int, Float>()
    private val shortcutPressController by lazy {
        PlayerCustomShortcutPressController<PlayerCustomShortcutAction, KeyEvent>(
            scope = lifecycleScope,
            longPressTimeoutMillis = ViewConfiguration.getLongPressTimeout().toLong(),
            isEligibleKey = { keyCode ->
                keyCode > 0 &&
                    keyCode != KeyEvent.KEYCODE_UNKNOWN &&
                    !PlayerCustomShortcutsStore.isForbiddenKeyCode(keyCode)
            },
            bindingsForKey = { keyCode ->
                val bindings = BiliClient.prefs.playerCustomShortcuts.filter { it.keyCode == keyCode }
                PlayerCustomShortcutBindings(
                    shortAction =
                        bindings.firstOrNull { it.trigger == PlayerCustomShortcutTrigger.SHORT_PRESS }
                            ?.action
                            ?.takeIf(::isLiveCustomShortcutActionSupported),
                    longAction =
                        bindings.firstOrNull { it.trigger == PlayerCustomShortcutTrigger.LONG_PRESS }
                            ?.action
                            ?.takeIf(::isLiveCustomShortcutActionSupported),
                )
            },
            canDispatch = ::canDispatchLiveCustomShortcut,
            copyEventToken = { source -> KeyEvent(source) },
            executeAction = { keyCode, trigger, action ->
                applyLiveCustomShortcut(
                    keyCode = shortcutMemoryKey(keyCode, trigger),
                    action = action,
                )
            },
        )
    }
    private var debugJob: Job? = null
    private var autoFailoverJob: Job? = null
    private var liveEntryReportedRoomId: Long = 0L
    private var finishOnBackKeyUp: Boolean = false
    private var controlsVisible: Boolean = false
    private var lastInteractionAtMs: Long = 0L
    private var autoFailoverWindowStartAtMs: Long = 0L
    private var autoFailoverSwitchCount: Int = 0
    private var autoFailoverLastSwitchAtMs: Long = 0L
    private var autoFailoverInFlight: Boolean = false
    private var behindLiveWindowWindowStartAtMs: Long = 0L
    private var behindLiveWindowRecoverCount: Int = 0
    private var behindLiveWindowLastRecoverAtMs: Long = 0L
    private var exitRequested: Boolean = false
    private val bufferingOverlayController: PlayerBufferingOverlayController by lazy {
        PlayerBufferingOverlayController(
            context = this,
            bindingProvider = { if (::binding.isInitialized) binding else null },
            scope = lifecycleScope,
            playbackStateProvider = { player?.playbackState },
        )
    }

    private val doubleBackToExit by lazy {
        DoubleBackToExitHandler(context = this, windowMs = BACK_DOUBLE_PRESS_WINDOW_MS) {
            if (controlsVisible) setControlsVisible(false)
        }
    }

    private var roomId: Long = 0L
    private var realRoomId: Long = 0L
    private var roomUid: Long = 0L
    private var roomTitle: String = ""
    private var roomUname: String = ""
    private var roomFace: String? = null

    private var session: LiveSession = LiveSession()
    private val debug = PlayerDebugMetrics()
    @Volatile private var liveHlsDebugInfo: LiveHlsDebugInfo? = null

    private var lastPlay: BiliApi.LivePlayUrl? = null
    private var lastLiveStatus: Int = 0
    private var transientPlaybackResumeRequested: Boolean? = null

    private var messageClient: LiveMessageClient? = null
    private var liveDanmakuBaseUptimeMs: Long = 0L
    private var liveDanmakuLastAppendMs: Int = Int.MIN_VALUE
    private var temporarySuperChatPreviewStarted = false
    private var superChatHistoryConsumedKeyUp: Int = KeyEvent.KEYCODE_UNKNOWN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlayerOsdSizing.applyTheme(this)
        val prefs = BiliClient.prefs
        val playerInflater = PlayerOsdSizing.cloneInflater(this, layoutInflater)
        val root =
            playerInflater.inflate(
                if (prefs.playerRenderViewType == AppPrefs.PLAYER_RENDER_VIEW_TEXTURE_VIEW) blbl.cat3399.R.layout.activity_player_texture else blbl.cat3399.R.layout.activity_player,
                null,
            )
        binding = ActivityPlayerBinding.bind(root)
        upQuickCard =
            PlayerUpQuickCardController(
                activity = this,
                binding = binding,
                isCardVisible = { controlsVisible },
                keepControlsVisible = { setControlsVisible(true) },
                beforeOpenUpDetail = { prepareTransientPlaybackExit() },
            )
        setContentView(binding.root)
        Immersive.apply(this, prefs.fullscreenEnabled)
        PlayerUiMode.applyLive(this, binding)
        resetBufferingOverlayState()

        roomId = intent.getLongExtra(EXTRA_ROOM_ID, 0L)
        roomTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        roomUname = intent.getStringExtra(EXTRA_UNAME).orEmpty()
        if (roomId <= 0L) {
            AppToast.show(this, "缺少 room_id")
            finish()
            return
        }

        val sessionOverrideJson =
            intent.getStringExtra(EXTRA_ENGINE_SWITCH_SESSION_JSON)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        session =
            LiveSession(
                engineKind = PlayerEngineKind.fromPrefValue(prefs.playerEngineKind),
                highBitrateEnabled = prefs.liveHighBitrateEnabled,
                danmaku = DanmakuSessionSettings(
                    enabled = prefs.danmakuEnabled,
                    opacity = prefs.danmakuOpacity,
                    textSizeSp = prefs.danmakuTextSizeSp,
                    fontWeight = DanmakuFontWeight.fromPrefValue(prefs.danmakuFontWeight),
                    strokeWidthPx = prefs.danmakuStrokeWidthPx,
                    speedLevel = prefs.danmakuSpeed,
                    area = prefs.danmakuArea,
                    laneDensity = DanmakuLaneDensity.fromPrefValue(prefs.dan@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package blbl.cat3399.feature.live

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.KeyEvent
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.MarginLayoutParams
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.ui.AspectRatioFrameLayout
import blbl.cat3399.BuildConfig
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.Danmaku
import blbl.cat3399.core.model.LiveSuperChat
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.prefs.PlayerCustomShortcutAction
import blbl.cat3399.core.prefs.PlayerCustomShortcutTrigger
import blbl.cat3399.core.prefs.PlayerCustomShortcutsStore
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.DoubleBackToExitHandler
import blbl.cat3399.core.ui.FocusReturn
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.popup.AppPopup
import blbl.cat3399.core.ui.popup.PopupHost
import blbl.cat3399.databinding.ActivityPlayerBinding
import blbl.cat3399.databinding.ViewLiveSuperChatHistoryPanelBinding
import blbl.cat3399.databinding.ViewLiveSuperChatOverlayBinding
import blbl.cat3399.feature.player.AudioBalanceLevel
import blbl.cat3399.feature.player.PlayerBufferingOverlayController
import blbl.cat3399.feature.player.PlayerCustomShortcutBindings
import blbl.cat3399.feature.player.PlayerCustomShortcutDispatchResult
import blbl.cat3399.feature.player.PlayerCustomShortcutInputPolicy
import blbl.cat3399.feature.player.PlayerCustomShortcutPressController
import blbl.cat3399.feature.player.PlayerDefaultShortPressDispatcher
import blbl.cat3399.feature.player.PlayerDebugMetrics
import blbl.cat3399.feature.player.PlayerOsdSizing
import blbl.cat3399.feature.player.PlayerSettingsAdapter
import blbl.cat3399.feature.player.PlayerTouchController
import blbl.cat3399.feature.player.PlayerTouchGestureHost
import blbl.cat3399.feature.player.PlayerUpQuickCardController
import blbl.cat3399.feature.player.PlayerUiMode
import blbl.cat3399.feature.player.areaText
import blbl.cat3399.feature.player.danmaku.DanmakuSessionSettings
import blbl.cat3399.feature.player.danmaku.DanmakuFontWeight
import blbl.cat3399.feature.player.danmaku.DanmakuLaneDensity
import blbl.cat3399.feature.player.engine.BlblPlayerEngine
import blbl.cat3399.feature.player.engine.ExoPlayerEngine
import blbl.cat3399.feature.player.engine.IjkPlayerPlugin
import blbl.cat3399.feature.player.engine.IjkPlayerPluginUi
import blbl.cat3399.feature.player.engine.IjkPlayerEngine
import blbl.cat3399.feature.player.engine.LiveHlsDebugInfo
import blbl.cat3399.feature.player.engine.PlayerEngineKind
import blbl.cat3399.feature.player.engine.PlaybackSource
import blbl.cat3399.feature.player.requirePlayerTouchOverlayBinding
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private object LivePlayerSettingKeys {
    const val QUALITY = "quality"
    const val LINE = "line"
    const val HIGH_BITRATE = "high_bitrate"
    const val AUDIO_BALANCE = "audio_balance"
    const val PLAYER_ENGINE = "player_engine"
    const val DEBUG_INFO = "debug_info"
}

class LivePlayerActivity : BaseActivity() {
    override fun shouldRecreateOnUiScaleChange(): Boolean = true

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var upQuickCard: PlayerUpQuickCardController
    private lateinit var superChatOverlay: LiveSuperChatOverlayController
    private val superChatHistory = LiveSuperChatHistory()
    private val superChatHistoryReturnFocus = FocusReturn()
    private var superChatHistoryPanel: LiveSuperChatHistoryPanelController? = null

    private var player: BlblPlayerEngine? = null
    private var ijkRenderView: View? = null
    private var ijkTextureSurface: Surface? = null
    private val settingsPanelReturnFocus = FocusReturn()
    private val osdFocusReturn = FocusReturn()
    private var autoHideJob: Job? = null
    private var seekHintJob: Job? = null
    private var superChatLoadJob: Job? = null
    private var touchController: PlayerTouchController? = null
    private val shortcutPrevDanmakuOpacityByKey = HashMap<Int, Float>()
    private val shortcutPrevDanmakuTextSizeByKey = HashMap<Int, Float>()
    private val shortcutPrevDanmakuSpeedLevelByKey = HashMap<Int, Int>()
    private val shortcutPrevDanmakuAreaByKey = HashMap<Int, Float>()
    private val shortcutPressController by lazy {
        PlayerCustomShortcutPressController<PlayerCustomShortcutAction, KeyEvent>(
            scope = lifecycleScope,
            longPressTimeoutMillis = ViewConfiguration.getLongPressTimeout().toLong(),
            isEligibleKey = { keyCode ->
                keyCode > 0 &&
                    keyCode != KeyEvent.KEYCODE_UNKNOWN &&
                    !PlayerCustomShortcutsStore.isForbiddenKeyCode(keyCode)
            },
            bindingsForKey = { keyCode ->
                val bindings = BiliClient.prefs.playerCustomShortcuts.filter { it.keyCode == keyCode }
                PlayerCustomShortcutBindings(
                    shortAction =
                        bindings.firstOrNull { it.trigger == PlayerCustomShortcutTrigger.SHORT_PRESS }
                            ?.action
                            ?.takeIf(::isLiveCustomShortcutActionSupported),
                    longAction =
                        bindings.firstOrNull { it.trigger == PlayerCustomShortcutTrigger.LONG_PRESS }
                            ?.action
                            ?.takeIf(::isLiveCustomShortcutActionSupported),
                )
            },
            canDispatch = ::canDispatchLiveCustomShortcut,
            copyEventToken = { source -> KeyEvent(source) },
            executeAction = { keyCode, trigger, action ->
                applyLiveCustomShortcut(
                    keyCode = shortcutMemoryKey(keyCode, trigger),
                    action = action,
                )
            },
        )
    }
    private var debugJob: Job? = null
    private var autoFailoverJob: Job? = null
    private var liveEntryReportedRoomId: Long = 0L
    private var finishOnBackKeyUp: Boolean = false
    private var controlsVisible: Boolean = false
    private var lastInteractionAtMs: Long = 0L
    private var autoFailoverWindowStartAtMs: Long = 0L
    private var autoFailoverSwitchCount: Int = 0
    private var autoFailoverLastSwitchAtMs: Long = 0L
    private var autoFailoverInFlight: Boolean = false
    private var behindLiveWindowWindowStartAtMs: Long = 0L
    private var behindLiveWindowRecoverCount: Int = 0
    private var behindLiveWindowLastRecoverAtMs: Long = 0L
    private var exitRequested: Boolean = false
    private val bufferingOverlayController: PlayerBufferingOverlayController by lazy {
        PlayerBufferingOverlayController(
            context = this,
            bindingProvider = { if (::binding.isInitialized) binding else null },
            scope = lifecycleScope,
            playbackStateProvider = { player?.playbackState },
        )
    }

    private val doubleBackToExit by lazy {
        DoubleBackToExitHandler(context = this, windowMs = BACK_DOUBLE_PRESS_WINDOW_MS) {
            if (controlsVisible) setControlsVisible(false)
        }
    }

    private var roomId: Long = 0L
    private var realRoomId: Long = 0L
    private var roomUid: Long = 0L
    private var roomTitle: String = ""
    private var roomUname: String = ""
    private var roomFace: String? = null

    private var session: LiveSession = LiveSession()
    private val debug = PlayerDebugMetrics()
    @Volatile private var liveHlsDebugInfo: LiveHlsDebugInfo? = null

    private var lastPlay: BiliApi.LivePlayUrl? = null
    private var lastLiveStatus: Int = 0
    private var transientPlaybackResumeRequested: Boolean? = null

    private var messageClient: LiveMessageClient? = null
    private var liveDanmakuBaseUptimeMs: Long = 0L
    private var liveDanmakuLastAppendMs: Int = Int.MIN_VALUE
    private var temporarySuperChatPreviewStarted = false
    private var superChatHistoryConsumedKeyUp: Int = KeyEvent.KEYCODE_UNKNOWN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlayerOsdSizing.applyTheme(this)
        val prefs = BiliClient.prefs
        val playerInflater = PlayerOsdSizing.cloneInflater(this, layoutInflater)
        val root =
            playerInflater.inflate(
                if (prefs.playerRenderViewType == AppPrefs.PLAYER_RENDER_VIEW_TEXTURE_VIEW) blbl.cat3399.R.layout.activity_player_texture else blbl.cat3399.R.layout.activity_player,
                null,
            )
        binding = ActivityPlayerBinding.bind(root)
        upQuickCard =
            PlayerUpQuickCardController(
                activity = this,
                binding = binding,
                isCardVisible = { controlsVisible },
                keepControlsVisible = { setControlsVisible(true) },
                beforeOpenUpDetail = { prepareTransientPlaybackExit() },
            )
        setContentView(binding.root)
        Immersive.apply(this, prefs.fullscreenEnabled)
        PlayerUiMode.applyLive(this, binding)
        resetBufferingOverlayState()

        roomId = intent.getLongExtra(EXTRA_ROOM_ID, 0L)
        roomTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        roomUname = intent.getStringExtra(EXTRA_UNAME).orEmpty()
        if (roomId <= 0L) {
            AppToast.show(this, "缺少 room_id")
            finish()
            return
        }

        val sessionOverrideJson =
            intent.getStringExtra(EXTRA_ENGINE_SWITCH_SESSION_JSON)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        session =
            LiveSession(
                engineKind = PlayerEngineKind.fromPrefValue(prefs.playerEngineKind),
                highBitrateEnabled = prefs.liveHighBitrateEnabled,
                danmaku = DanmakuSessionSettings(
                    enabled = prefs.danmakuEnabled,
                    opacity = prefs.danmakuOpacity,
                    textSizeSp = prefs.danmakuTextSizeSp,
                    fontWeight = DanmakuFontWeight.fromPrefValue(prefs.danmakuFontWeight),
                    strokeWidthPx = prefs.danmakuStrokeWidthPx,
                    speedLevel = prefs.danmakuSpeed,
                    area = prefs.danmakuArea,
                    laneDensity = DanmakuLaneDensity.fromPrefValue(prefs.dan@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package blbl.cat3399.feature.live

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.KeyEvent
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.MarginLayoutParams
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.ui.AspectRatioFrameLayout
import blbl.cat3399.BuildConfig
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.Danmaku
import blbl.cat3399.core.model.LiveSuperChat
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.prefs.PlayerCustomShortcutAction
import blbl.cat3399.core.prefs.PlayerCustomShortcutTrigger
import blbl.cat3399.core.prefs.PlayerCustomShortcutsStore
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.DoubleBackToExitHandler
import blbl.cat3399.core.ui.FocusReturn
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.popup.AppPopup
import blbl.cat3399.core.ui.popup.PopupHost
import blbl.cat3399.databinding.ActivityPlayerBinding
import blbl.cat3399.databinding.ViewLiveSuperChatHistoryPanelBinding
import blbl.cat3399.databinding.ViewLiveSuperChatOverlayBinding
import blbl.cat3399.feature.player.AudioBalanceLevel
import blbl.cat3399.feature.player.PlayerBufferingOverlayController
import blbl.cat3399.feature.player.PlayerCustomShortcutBindings
import blbl.cat3399.feature.player.PlayerCustomShortcutDispatchResult
import blbl.cat3399.feature.player.PlayerCustomShortcutInputPolicy
import blbl.cat3399.feature.player.PlayerCustomShortcutPressController
import blbl.cat3399.feature.player.PlayerDefaultShortPressDispatcher
import blbl.cat3399.feature.player.PlayerDebugMetrics
import blbl.cat3399.feature.player.PlayerOsdSizing
import blbl.cat3399.feature.player.PlayerSettingsAdapter
import blbl.cat3399.feature.player.PlayerTouchController
import blbl.cat3399.feature.player.PlayerTouchGestureHost
import blbl.cat3399.feature.player.PlayerUpQuickCardController
import blbl.cat3399.feature.player.PlayerUiMode
import blbl.cat3399.feature.player.areaText
import blbl.cat3399.feature.player.danmaku.DanmakuSessionSettings
import blbl.cat3399.feature.player.danmaku.DanmakuFontWeight
import blbl.cat3399.feature.player.danmaku.DanmakuLaneDensity
import blbl.cat3399.feature.player.engine.BlblPlayerEngine
import blbl.cat3399.feature.player.engine.ExoPlayerEngine
import blbl.cat3399.feature.player.engine.IjkPlayerPlugin
import blbl.cat3399.feature.player.engine.IjkPlayerPluginUi
import blbl.cat3399.feature.player.engine.IjkPlayerEngine
import blbl.cat3399.feature.player.engine.LiveHlsDebugInfo
import blbl.cat3399.feature.player.engine.PlayerEngineKind
import blbl.cat3399.feature.player.engine.PlaybackSource
import blbl.cat3399.feature.player.requirePlayerTouchOverlayBinding
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private object LivePlayerSettingKeys {
    const val QUALITY = "quality"
    const val LINE = "line"
    const val HIGH_BITRATE = "high_bitrate"
    const val AUDIO_BALANCE = "audio_balance"
    const val PLAYER_ENGINE = "player_engine"
    const val DEBUG_INFO = "debug_info"
}

class LivePlayerActivity : BaseActivity() {
    override fun shouldRecreateOnUiScaleChange(): Boolean = true

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var upQuickCard: PlayerUpQuickCardController
    private lateinit var superChatOverlay: LiveSuperChatOverlayController
    private val superChatHistory = LiveSuperChatHistory()
    private val superChatHistoryReturnFocus = FocusReturn()
    private var superChatHistoryPanel: LiveSuperChatHistoryPanelController? = null

    private var player: BlblPlayerEngine? = null
    private var ijkRenderView: View? = null
    private var ijkTextureSurface: Surface? = null
    private val settingsPanelReturnFocus = FocusReturn()
    private val osdFocusReturn = FocusReturn()
    private var autoHideJob: Job? = null
    private var seekHintJob: Job? = null
    private var superChatLoadJob: Job? = null
    private var touchController: PlayerTouchController? = null
    private val shortcutPrevDanmakuOpacityByKey = HashMap<Int, Float>()
    private val shortcutPrevDanmakuTextSizeByKey = HashMap<Int, Float>()
    private val shortcutPrevDanmakuSpeedLevelByKey = HashMap<Int, Int>()
    private val shortcutPrevDanmakuAreaByKey = HashMap<Int, Float>()
    private val shortcutPressController by lazy {
        PlayerCustomShortcutPressController<PlayerCustomShortcutAction, KeyEvent>(
            scope = lifecycleScope,
            longPressTimeoutMillis = ViewConfiguration.getLongPressTimeout().toLong(),
            isEligibleKey = { keyCode ->
                keyCode > 0 &&
                    keyCode != KeyEvent.KEYCODE_UNKNOWN &&
                    !PlayerCustomShortcutsStore.isForbiddenKeyCode(keyCode)
            },
            bindingsForKey = { keyCode ->
                val bindings = BiliClient.prefs.playerCustomShortcuts.filter { it.keyCode == keyCode }
                PlayerCustomShortcutBindings(
                    shortAction =
                        bindings.firstOrNull { it.trigger == PlayerCustomShortcutTrigger.SHORT_PRESS }
                            ?.action
                            ?.takeIf(::isLiveCustomShortcutActionSupported),
                    longAction =
                        bindings.firstOrNull { it.trigger == PlayerCustomShortcutTrigger.LONG_PRESS }
                            ?.action
                            ?.takeIf(::isLiveCustomShortcutActionSupported),
                )
            },
            canDispatch = ::canDispatchLiveCustomShortcut,
            copyEventToken = { source -> KeyEvent(source) },
            executeAction = { keyCode, trigger, action ->
                applyLiveCustomShortcut(
                    keyCode = shortcutMemoryKey(keyCode, trigger),
                    action = action,
                )
            },
        )
    }
    private var debugJob: Job? = null
    private var autoFailoverJob: Job? = null
    private var liveEntryReportedRoomId: Long = 0L
    private var finishOnBackKeyUp: Boolean = false
    private var controlsVisible: Boolean = false
    private var lastInteractionAtMs: Long = 0L
    private var autoFailoverWindowStartAtMs: Long = 0L
    private var autoFailoverSwitchCount: Int = 0
    private var autoFailoverLastSwitchAtMs: Long = 0L
    private var autoFailoverInFlight: Boolean = false
    private var behindLiveWindowWindowStartAtMs: Long = 0L
    private var behindLiveWindowRecoverCount: Int = 0
    private var behindLiveWindowLastRecoverAtMs: Long = 0L
    private var exitRequested: Boolean = false
    private val bufferingOverlayController: PlayerBufferingOverlayController by lazy {
        PlayerBufferingOverlayController(
            context = this,
            bindingProvider = { if (::binding.isInitialized) binding else null },
            scope = lifecycleScope,
            playbackStateProvider = { player?.playbackState },
        )
    }

    private val doubleBackToExit by lazy {
        DoubleBackToExitHandler(context = this, windowMs = BACK_DOUBLE_PRESS_WINDOW_MS) {
            if (controlsVisible) setControlsVisible(false)
        }
    }

    private var roomId: Long = 0L
    private var realRoomId: Long = 0L
    private var roomUid: Long = 0L
    private var roomTitle: String = ""
    private var roomUname: String = ""
    private var roomFace: String? = null

    private var session: LiveSession = LiveSession()
    private val debug = PlayerDebugMetrics()
    @Volatile private var liveHlsDebugInfo: LiveHlsDebugInfo? = null

    private var lastPlay: BiliApi.LivePlayUrl? = null
    private var lastLiveStatus: Int = 0
    private var transientPlaybackResumeRequested: Boolean? = null

    private var messageClient: LiveMessageClient? = null
    private var liveDanmakuBaseUptimeMs: Long = 0L
    private var liveDanmakuLastAppendMs: Int = Int.MIN_VALUE
    private var temporarySuperChatPreviewStarted = false
    private var superChatHistoryConsumedKeyUp: Int = KeyEvent.KEYCODE_UNKNOWN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlayerOsdSizing.applyTheme(this)
        val prefs = BiliClient.prefs
        val playerInflater = PlayerOsdSizing.cloneInflater(this, layoutInflater)
        val root =
            playerInflater.inflate(
                if (prefs.playerRenderViewType == AppPrefs.PLAYER_RENDER_VIEW_TEXTURE_VIEW) blbl.cat3399.R.layout.activity_player_texture else blbl.cat3399.R.layout.activity_player,
                null,
            )
        binding = ActivityPlayerBinding.bind(root)
        upQuickCard =
            PlayerUpQuickCardController(
                activity = this,
                binding = binding,
                isCardVisible = { controlsVisible },
                keepControlsVisible = { setControlsVisible(true) },
                beforeOpenUpDetail = { prepareTransientPlaybackExit() },
            )
        setContentView(binding.root)
        Immersive.apply(this, prefs.fullscreenEnabled)
        PlayerUiMode.applyLive(this, binding)
        resetBufferingOverlayState()

        roomId = intent.getLongExtra(EXTRA_ROOM_ID, 0L)
        roomTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        roomUname = intent.getStringExtra(EXTRA_UNAME).orEmpty()
        if (roomId <= 0L) {
            AppToast.show(this, "缺少 room_id")
            finish()
            return
        }

        val sessionOverrideJson =
            intent.getStringExtra(EXTRA_ENGINE_SWITCH_SESSION_JSON)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        session =
            LiveSession(
                engineKind = PlayerEngineKind.fromPrefValue(prefs.playerEngineKind),
                highBitrateEnabled = prefs.liveHighBitrateEnabled,
                danmaku = DanmakuSessionSettings(
                    enabled = prefs.danmakuEnabled,
                    opacity = prefs.danmakuOpacity,
                    textSizeSp = prefs.danmakuTextSizeSp,
                    fontWeight = DanmakuFontWeight.fromPrefValue(prefs.danmakuFontWeight),
                    strokeWidthPx = prefs.danmakuStrokeWidthPx,
                    speedLevel = prefs.danmakuSpeed,
                    area = prefs.danmakuArea,
                    laneDensity = DanmakuLaneDensity.fromPrefValue(prefs.dan@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package blbl.cat3399.feature.live

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.KeyEvent
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.MarginLayoutParams
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.ui.AspectRatioFrameLayout
import blbl.cat3399.BuildConfig
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.Danmaku
import blbl.cat3399.core.model.LiveSuperChat
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.prefs.PlayerCustomShortcutAction
import blbl.cat3399.core.prefs.PlayerCustomShortcutTrigger
import blbl.cat3399.core.prefs.PlayerCustomShortcutsStore
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.DoubleBackToExitHandler
import blbl.cat3399.core.ui.FocusReturn
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.popup.AppPopup
import blbl.cat3399.core.ui.popup.PopupHost
import blbl.cat3399.databinding.ActivityPlayerBinding
import blbl.cat3399.databinding.ViewLiveSuperChatHistoryPanelBinding
import blbl.cat3399.databinding.ViewLiveSuperChatOverlayBinding
import blbl.cat3399.feature.player.AudioBalanceLevel
import blbl.cat3399.feature.player.PlayerBufferingOverlayController
import blbl.cat3399.feature.player.PlayerCustomShortcutBindings
import blbl.cat3399.feature.player.PlayerCustomShortcutDispatchResult
import blbl.cat3399.feature.player.PlayerCustomShortcutInputPolicy
import blbl.cat3399.feature.player.PlayerCustomShortcutPressController
import blbl.cat3399.feature.player.PlayerDefaultShortPressDispatcher
import blbl.cat3399.feature.player.PlayerDebugMetrics
import blbl.cat3399.feature.player.PlayerOsdSizing
import blbl.cat3399.feature.player.PlayerSettingsAdapter
import blbl.cat3399.feature.player.PlayerTouchController
import blbl.cat3399.feature.player.PlayerTouchGestureHost
import blbl.cat3399.feature.player.PlayerUpQuickCardController
import blbl.cat3399.feature.player.PlayerUiMode
import blbl.cat3399.feature.player.areaText
import blbl.cat3399.feature.player.danmaku.DanmakuSessionSettings
import blbl.cat3399.feature.player.danmaku.DanmakuFontWeight
import blbl.cat3399.feature.player.danmaku.DanmakuLaneDensity
import blbl.cat3399.feature.player.engine.BlblPlayerEngine
import blbl.cat3399.feature.player.engine.ExoPlayerEngine
import blbl.cat3399.feature.player.engine.IjkPlayerPlugin
import blbl.cat3399.feature.player.engine.IjkPlayerPluginUi
import blbl.cat3399.feature.player.engine.IjkPlayerEngine
import blbl.cat3399.feature.player.engine.LiveHlsDebugInfo
import blbl.cat3399.feature.player.engine.PlayerEngineKind
import blbl.cat3399.feature.player.engine.PlaybackSource
import blbl.cat3399.feature.player.requirePlayerTouchOverlayBinding
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private object LivePlayerSettingKeys {
    const val QUALITY = "quality"
    const val LINE = "line"
    const val HIGH_BITRATE = "high_bitrate"
    const val AUDIO_BALANCE = "audio_balance"
    const val PLAYER_ENGINE = "player_engine"
    const val DEBUG_INFO = "debug_info"
}

class LivePlayerActivity : BaseActivity() {
    override fun shouldRecreateOnUiScaleChange(): Boolean = true

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var upQuickCard: PlayerUpQuickCardController
    private lateinit var superChatOverlay: LiveSuperChatOverlayController
    private val superChatHistory = LiveSuperChatHistory()
    private val superChatHistoryReturnFocus = FocusReturn()
    private var superChatHistoryPanel: LiveSuperChatHistoryPanelController? = null

    private var player: BlblPlayerEngine? = null
    private var ijkRenderView: View? = null
    private var ijkTextureSurface: Surface? = null
    private val settingsPanelReturnFocus = FocusReturn()
    private val osdFocusReturn = FocusReturn()
    private var autoHideJob: Job? = null
    private var seekHintJob: Job? = null
    private var superChatLoadJob: Job? = null
    private var touchController: PlayerTouchController? = null
    private val shortcutPrevDanmakuOpacityByKey = HashMap<Int, Float>()
    private val shortcutPrevDanmakuTextSizeByKey = HashMap<Int, Float>()
    private val shortcutPrevDanmakuSpeedLevelByKey = HashMap<Int, Int>()
    private val shortcutPrevDanmakuAreaByKey = HashMap<Int, Float>()
    private val shortcutPressController by lazy {
        PlayerCustomShortcutPressController<PlayerCustomShortcutAction, KeyEvent>(
            scope = lifecycleScope,
            longPressTimeoutMillis = ViewConfiguration.getLongPressTimeout().toLong(),
            isEligibleKey = { keyCode ->
                keyCode > 0 &&
                    keyCode != KeyEvent.KEYCODE_UNKNOWN &&
                    !PlayerCustomShortcutsStore.isForbiddenKeyCode(keyCode)
            },
            bindingsForKey = { keyCode ->
                val bindings = BiliClient.prefs.playerCustomShortcuts.filter { it.keyCode == keyCode }
                PlayerCustomShortcutBindings(
                    shortAction =
                        bindings.firstOrNull { it.trigger == PlayerCustomShortcutTrigger.SHORT_PRESS }
                            ?.action
                            ?.takeIf(::isLiveCustomShortcutActionSupported),
                    longAction =
                        bindings.firstOrNull { it.trigger == PlayerCustomShortcutTrigger.LONG_PRESS }
                            ?.action
                            ?.takeIf(::isLiveCustomShortcutActionSupported),
                )
            },
            canDispatch = ::canDispatchLiveCustomShortcut,
            copyEventToken = { source -> KeyEvent(source) },
            executeAction = { keyCode, trigger, action ->
                applyLiveCustomShortcut(
                    keyCode = shortcutMemoryKey(keyCode, trigger),
                    action = action,
                )
            },
        )
    }
    private var debugJob: Job? = null
    private var autoFailoverJob: Job? = null
    private var liveEntryReportedRoomId: Long = 0L
    private var finishOnBackKeyUp: Boolean = false
    private var controlsVisible: Boolean = false
    private var lastInteractionAtMs: Long = 0L
    private var autoFailoverWindowStartAtMs: Long = 0L
    private var autoFailoverSwitchCount: Int = 0
    private var autoFailoverLastSwitchAtMs: Long = 0L
    private var autoFailoverInFlight: Boolean = false
    private var behindLiveWindowWindowStartAtMs: Long = 0L
    private var behindLiveWindowRecoverCount: Int = 0
    private var behindLiveWindowLastRecoverAtMs: Long = 0L
    private var exitRequested: Boolean = false
    private val bufferingOverlayController: PlayerBufferingOverlayController by lazy {
        PlayerBufferingOverlayController(
            context = this,
            bindingProvider = { if (::binding.isInitialized) binding else null },
            scope = lifecycleScope,
            playbackStateProvider = { player?.playbackState },
        )
    }

    private val doubleBackToExit by lazy {
        DoubleBackToExitHandler(context = this, windowMs = BACK_DOUBLE_PRESS_WINDOW_MS) {
            if (controlsVisible) setControlsVisible(false)
        }
    }

    private var roomId: Long = 0L
    private var realRoomId: Long = 0L
    private var roomUid: Long = 0L
    private var roomTitle: String = ""
    private var roomUname: String = ""
    private var roomFace: String? = null

    private var session: LiveSession = LiveSession()
    private val debug = PlayerDebugMetrics()
    @Volatile private var liveHlsDebugInfo: LiveHlsDebugInfo? = null

    private var lastPlay: BiliApi.LivePlayUrl? = null
    private var lastLiveStatus: Int = 0
    private var transientPlaybackResumeRequested: Boolean? = null

    private var messageClient: LiveMessageClient? = null
    private var liveDanmakuBaseUptimeMs: Long = 0L
    private var liveDanmakuLastAppendMs: Int = Int.MIN_VALUE
    private var temporarySuperChatPreviewStarted = false
    private var superChatHistoryConsumedKeyUp: Int = KeyEvent.KEYCODE_UNKNOWN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlayerOsdSizing.applyTheme(this)
        val prefs = BiliClient.prefs
        val playerInflater = PlayerOsdSizing.cloneInflater(this, layoutInflater)
        val root =
            playerInflater.inflate(
                if (prefs.playerRenderViewType == AppPrefs.PLAYER_RENDER_VIEW_TEXTURE_VIEW) blbl.cat3399.R.layout.activity_player_texture else blbl.cat3399.R.layout.activity_player,
                null,
            )
        binding = ActivityPlayerBinding.bind(root)
        upQuickCard =
            PlayerUpQuickCardController(
                activity = this,
                binding = binding,
                isCardVisible = { controlsVisible },
                keepControlsVisible = { setControlsVisible(true) },
                beforeOpenUpDetail = { prepareTransientPlaybackExit() },
            )
        setContentView(binding.root)
        Immersive.apply(this, prefs.fullscreenEnabled)
        PlayerUiMode.applyLive(this, binding)
        resetBufferingOverlayState()

        roomId = intent.getLongExtra(EXTRA_ROOM_ID, 0L)
        roomTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        roomUname = intent.getStringExtra(EXTRA_UNAME).orEmpty()
        if (roomId <= 0L) {
            AppToast.show(this, "缺少 room_id")
            finish()
            return
        }

        val sessionOverrideJson =
            intent.getStringExtra(EXTRA_ENGINE_SWITCH_SESSION_JSON)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        session =
            LiveSession(
                engineKind = PlayerEngineKind.fromPrefValue(prefs.playerEngineKind),
                highBitrateEnabled = prefs.liveHighBitrateEnabled,
                danmaku = DanmakuSessionSettings(
                    enabled = prefs.danmakuEnabled,
                    opacity = prefs.danmakuOpacity,
                    textSizeSp = prefs.danmakuTextSizeSp,
                    fontWeight = DanmakuFontWeight.fromPrefValue(prefs.danmakuFontWeight),
                    strokeWidthPx = prefs.danmakuStrokeWidthPx,
                    speedLevel = prefs.danmakuSpeed,
                    area = prefs.danmakuArea,
                    laneDensity = DanmakuLaneDensity.fromPrefValue(prefs.dan