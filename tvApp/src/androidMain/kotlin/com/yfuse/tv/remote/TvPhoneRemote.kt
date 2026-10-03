package com.yfuse.tv.remote

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.yfuse.app.RootComponent
import com.yfuse.core.account.ACCOUNT_BASE_URL
import com.yfuse.core.account.AccountAccessTokenSource
import com.yfuse.core.handoff.HandoffController
import com.yfuse.core.remote.RemoteControlEvent
import com.yfuse.core.remote.RemoteControlHost
import com.yfuse.core.remote.RemoteControlPhone
import com.yfuse.core.remote.RemoteSignInRequest
import com.yfuse.feature.search.SearchComponent
import com.yfuse.feature.search.SearchIntent
import com.yfuse.tv.TvMainActivity
import com.yfuse.tv.ui.TvPhoneRemoteOverlay
import com.yfuse.watch.protocol.RemoteControlKey
import com.yfuse.watch.protocol.RemoteSignInServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import java.lang.ref.WeakReference

/**
 * 手机遥控 on the television. While the app is in the foreground it hosts a remote session on the
 * watch relay, and it replays what a phone of the same account sends: keys into whichever of this
 * app's windows has focus — a dialog over the page included — the way a physical remote presses
 * them, so focus moves everywhere it already moves; text into the focused field, or into 搜索 when
 * no field has focus — but never into a password field.
 *
 * Being signed in to the same account does not let a phone in. The first time one connects, the
 * page in front asks the viewer 允许一次 / 始终允许此设备 / 拒绝 (TvPhoneRemotePrompt): the shell
 * itself, and the player through a layer this class lays over it. Until the answer nothing that
 * phone sends counts, and while any phone waits no phone is heard at all, so one already let in
 * cannot answer the question for another.
 *
 * While a phone is in, the shell shows 手机遥控中 · 断开 at its top right and the player shows 手机遥控中
 * (TvPhoneRemoteIndicator). 设置 → 手机遥控 turns it off altogether and forgets trusted phones.
 *
 * 添加服务器 asks on the same session for 用手机登录 (TvPhoneSignInDialog): a phone of the same account
 * offers one of its servers, the television shows which, and only once the phone confirms does its
 * session arrive, to be checked with the server and saved as any sign-in here would be.
 */
internal class TvPhoneRemote private constructor(
    private val application: Application,
) : Application.ActivityLifecycleCallbacks {
    private var resumed: WeakReference<Activity>? = null
    private var shell: WeakReference<TvMainActivity>? = null
    private var passwordNoticeAt = 0L

    /** Set once by [start], on whichever thread the sessions were restored; the screens read it on main. */
    @Volatile
    private var host: RemoteControlHost? = null
    private val _phones = MutableStateFlow<List<RemoteControlPhone>>(emptyList())

    /** Phones on this television now; none until hosting has started. */
    val phones: StateFlow<List<RemoteControlPhone>> = _phones.asStateFlow()

    private val _signInAvailable = MutableStateFlow(false)

    /** 用手机登录 can be offered: this television hosts, on a relay that carries it. */
    val signInAvailable: StateFlow<Boolean> = _signInAvailable.asStateFlow()

    private val _signIn = MutableStateFlow<RemoteSignInRequest>(RemoteSignInRequest.Idle)

    /** Where 用手机登录 stands. */
    val signIn: StateFlow<RemoteSignInRequest> = _signIn.asStateFlow()

    /**
     * Each server a phone handed over, session and all, once — for 添加服务器 to check and save,
     * then to answer with [finishSignIn]. Nothing until hosting has started.
     */
    val handedServers: Flow<RemoteSignInServer>
        get() = host?.handedServers ?: emptyFlow()

    /** 用手机登录: ask the phones of this account for a server. */
    fun askForServer() {
        host?.askForServer()
    }

    /** Stops asking — 取消, Back, or 添加服务器 closing. A session already on its way is answered instead. */
    fun cancelSignIn() {
        host?.cancelSignIn()
    }

    /** Whether the server a phone handed over was saved; the phone hears which. */
    fun finishSignIn(saved: Boolean) {
        host?.finishSignIn(saved)
    }

    /** Koin holds the app's settings from the first line of the application's onCreate. */
    val preferences: TvPhoneRemotePreferences by lazy { TvPhoneRemotePreferences(GlobalContext.get().get()) }

    private fun replay(event: RemoteControlEvent) {
        // Only phones already let in send anything; while another waits, even they are not heard.
        if (_phones.value.any { !it.allowed }) return
        when (event) {
            is RemoteControlEvent.Key -> press(event.key)
            is RemoteControlEvent.Text -> type(event.text)
        }
    }

    /** The viewer's answer to [phone]; 始终允许此设备 is remembered on this television alone. */
    fun answer(
        phone: RemoteControlPhone,
        answer: TvPhoneRemoteAnswer,
    ) {
        val host = host ?: return
        when (answer) {
            TvPhoneRemoteAnswer.Refuse -> host.release(phone.deviceId)
            TvPhoneRemoteAnswer.AllowOnce -> host.allow(phone.deviceId)
            TvPhoneRemoteAnswer.AllowAlways -> {
                if (phone.rememberable) preferences.trust(phone.deviceId, phone.name)
                host.allow(phone.deviceId)
            }
        }
    }

    private fun press(key: RemoteControlKey) {
        val activity = resumed?.get() ?: return
        when (key) {
            RemoteControlKey.Home -> goHome(activity)
            RemoteControlKey.Back -> goBack(focusedRoot(activity))
            else -> remoteKeyCode(key)?.let { dispatchKey(focusedRoot(activity), it) }
        }
    }

    /**
     * What a physical 返回 does on this Android. From 13 on, with the predictive-back opt-in this
     * app has, the focused window's back dispatcher answers it and no key event is delivered at all;
     * before that it is a key event like any other.
     */
    private fun goBack(root: View) {
        val dispatcher = root.findViewTreeOnBackPressedDispatcherOwner()?.onBackPressedDispatcher
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && dispatcher != null) {
            dispatcher.onBackPressed()
        } else {
            dispatchKey(root, KeyEvent.KEYCODE_BACK)
        }
    }

    /**
     * 主页 is this app's 首页. Android keeps its own Home key from apps, and leaving the app would end
     * the very session the phone is using; a player or any other page over the shell is closed.
     */
    private fun goHome(activity: Activity) {
        if (activity !is TvMainActivity) activity.finish()
        val root = shell?.get()?.remoteRoot ?: return
        root.selectTab(RootComponent.Tab.Home)
        root.home.popToRoot()
    }

    private fun type(text: String) {
        val activity = resumed?.get() ?: return
        if (typeIntoFocusedField(focusedRoot(activity), text)) return
        // A search never interrupts playback: the text stays in the phone's field until then.
        val root = (activity as? TvMainActivity)?.remoteRoot ?: return
        if (text.isEmpty() && root.activeTab.value != RootComponent.Tab.Search) return
        root.selectTab(RootComponent.Tab.Search)
        root.search.popToRoot()
        val search =
            root.search.stack.value.items
                .firstOrNull()
                ?.instance as? SearchComponent.Child.Home ?: return
        search.component.store.accept(SearchIntent.QueryChanged(text))
    }

    /**
     * Replaces the focused field's whole text, since the phone sends its field whole. A password
     * field is left alone — see [isPasswordInputType] — and still counts as the field typed into,
     * so the text never goes on to 搜索 instead.
     */
    private fun typeIntoFocusedField(
        root: View,
        text: String,
    ): Boolean {
        val focused = root.findFocus()?.takeIf { it.onCheckIsTextEditor() } ?: return false
        val field = EditorInfo()
        val connection = focused.onCreateInputConnection(field) ?: return false
        try {
            if (isPasswordInputType(field.inputType)) {
                refusePassword()
                return true
            }
            val current = connection.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()
            if (current == text) return true
            connection.beginBatchEdit()
            if (current != null) {
                connection.setSelection(0, current.length)
            } else {
                connection.performContextMenuAction(android.R.id.selectAll)
            }
            connection.commitText(text, 1)
            connection.endBatchEdit()
        } finally {
            connection.closeConnection()
        }
        return true
    }

    /**
     * Says why the phone's typing did not arrive. The phone resends its field on every pause in
     * typing, so this is said once in a while rather than on every resend.
     */
    private fun refusePassword() {
        val now = SystemClock.uptimeMillis()
        if (passwordNoticeAt != 0L && now - passwordNoticeAt < PASSWORD_NOTICE_INTERVAL_MS) return
        passwordNoticeAt = now
        Toast.makeText(application, "手机遥控不能输入密码，请用遥控器输入", Toast.LENGTH_SHORT).show()
    }

    private fun dispatchKey(
        root: View,
        keyCode: Int,
    ) {
        val media = keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        val source = if (media) InputDevice.SOURCE_KEYBOARD else InputDevice.SOURCE_DPAD
        val downAt = SystemClock.uptimeMillis()
        root.dispatchKeyEvent(remoteKeyEvent(downAt, downAt, KeyEvent.ACTION_DOWN, keyCode, source))
        root.dispatchKeyEvent(remoteKeyEvent(downAt, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, source))
    }

    private fun remoteKeyEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        keyCode: Int,
        source: Int,
    ): KeyEvent = KeyEvent(downTime, eventTime, action, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, source)

    /**
     * The root of this app's window that has focus: a dialog over the page when one is up, else the
     * page. Android has no public list of an app's windows, so the window manager's own list is read
     * when the page itself does not have focus; where that is refused, the page is used.
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi")
    private fun focusedRoot(activity: Activity): View {
        val page = activity.window.decorView
        if (page.hasWindowFocus()) return page
        val roots =
            runCatching {
                val global = Class.forName("android.view.WindowManagerGlobal")
                val instance = global.getMethod("getInstance").invoke(null)
                val views = global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance)
                (views as? List<*>)?.filterIsInstance<View>().orEmpty()
            }.getOrDefault(emptyList())
        return roots.lastOrNull { it.hasWindowFocus() } ?: page
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) {
        if (activity is TvMainActivity) shell = WeakReference(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
        // The shell asks in its own composition (TvRoot); any other page is given a layer to ask in.
        if (activity !is TvMainActivity && activity is ComponentActivity) layOver(activity)
    }

    /**
     * A layer over [activity] — the player — in which 手机遥控 asks about a phone that connects
     * mid-film. It sits on the window's root beside the page, not inside the page's content, which
     * the page's own setContent would take over; and it is never a focus stop, so the page keeps
     * the remote while the question arrives in a dialog window of its own.
     */
    private fun layOver(activity: ComponentActivity) {
        val root = activity.window.decorView as? ViewGroup ?: return
        if (root.findViewWithTag<View>(OVERLAY_TAG) != null) return
        val layer =
            ComposeView(activity).apply {
                tag = OVERLAY_TAG
                // Its own key for saved state, apart from the page's.
                id = View.generateViewId()
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                // The page may not have set its content, which sets these on the window, yet.
                setViewTreeLifecycleOwner(activity)
                setViewTreeSavedStateRegistryOwner(activity)
                setViewTreeOnBackPressedDispatcherOwner(activity)
                setContent { TvPhoneRemoteOverlay(this@TvPhoneRemote) }
            }
        val fill = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(layer, ViewGroup.LayoutParams(fill, fill))
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (shell?.get() === activity) shell = null
    }

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    /**
     * Starts hosting 手机遥控 once the saved sessions are restored: the account token source and the
     * handoff controller both resolve the server registry. The television's application calls it once.
     */
    fun start(koin: Koin) {
        val tokens = koin.get<AccountAccessTokenSource>()
        val host =
            RemoteControlHost(
                signedIn = tokens.sessionAvailable,
                accessToken = { tokens.validAccessTokenFor(ACCOUNT_BASE_URL) },
                refreshAccessToken = { tokens.refreshAccessTokenFor(ACCOUNT_BASE_URL) },
                trusted = preferences::isTrusted,
            )
        this.host = host
        val handoff = koin.get<HandoffController>()
        handoff.hostRemoteControl(
            accepting = { host.hosting.value },
            askingForServer = { host.signIn.value.asking },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        // A phone learns of this television from the handoff heartbeat — that it hosts, and that
        // 添加服务器 asks for a server — so send it as soon as either changes.
        scope.launch { host.hosting.drop(1).collect { handoff.refreshPresence() } }
        scope.launch {
            host.signIn
                .map { it.asking }
                .distinctUntilChanged()
                .drop(1)
                .collect { handoff.refreshPresence() }
        }
        // Read on the main thread, where replay and the screens run.
        scope.launch { host.phones.collect { _phones.value = it } }
        scope.launch { host.signInAvailable.collect { _signInAvailable.value = it } }
        scope.launch { host.signIn.collect { _signIn.value = it } }
        scope.launch { host.events.collect { replay(it) } }
        // Hosting follows the app's foreground and the television's own 手机遥控 switch: switched
        // off, the relay tells every phone its television left, and phones stop listing it.
        val foreground = MutableStateFlow(false)
        scope.launch {
            combine(foreground, preferences.enabled) { front, on -> front && on }
                .collect { host.setActive(it) }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    foreground.value = true
                }

                override fun onStop(owner: LifecycleOwner) {
                    foreground.value = false
                }
            },
        )
    }

    /** 断开: lets every phone go. One trusted for good is let in again if it reconnects. */
    fun disconnectAll() {
        host?.releaseAll()
    }

    companion object {
        /** This process's, for the screens that ask about phones and show who is on; set at start. */
        @Volatile
        var current: TvPhoneRemote? = null
            private set

        /**
         * Tracks this app's activities from process start. The sessions restore on a worker and
         * [start] runs after it, usually once the first activity is already created and resumed; a
         * callback registered only then would miss both, and keys from the phone would go nowhere
         * until the next page change.
         */
        fun register(application: Application): TvPhoneRemote =
            TvPhoneRemote(application).also { remote ->
                application.registerActivityLifecycleCallbacks(remote)
                current = remote
            }
    }
}

/** What the viewer said to a phone asking to use 手机遥控. */
internal enum class TvPhoneRemoteAnswer {
    Refuse,
    AllowOnce,

    /** 始终允许此设备: let in now, and without asking whenever it connects again. */
    AllowAlways,
}

private const val OVERLAY_TAG = "yfuse.tv.phoneRemote.overlay"

/** The key a physical remote sends for [key]; 主页 has none an app may receive, and is handled in-app. */
internal fun remoteKeyCode(key: RemoteControlKey): Int? =
    when (key) {
        RemoteControlKey.Up -> KeyEvent.KEYCODE_DPAD_UP
        RemoteControlKey.Down -> KeyEvent.KEYCODE_DPAD_DOWN
        RemoteControlKey.Left -> KeyEvent.KEYCODE_DPAD_LEFT
        RemoteControlKey.Right -> KeyEvent.KEYCODE_DPAD_RIGHT
        RemoteControlKey.Center -> KeyEvent.KEYCODE_DPAD_CENTER
        RemoteControlKey.Back -> KeyEvent.KEYCODE_BACK
        RemoteControlKey.PlayPause -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        RemoteControlKey.Home -> null
    }

/**
 * Whether a field of [inputType] takes a password, shown or hidden. A phone's typing never fills
 * one: it would pass through the relay and sit in the phone's own field in the clear, and a
 * password is short enough to enter with the remote itself.
 */
internal fun isPasswordInputType(inputType: Int): Boolean {
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    return when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_TEXT ->
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        else -> false
    }
}

/** A toast lasts about two seconds; this keeps the next one from following straight on. */
private const val PASSWORD_NOTICE_INTERVAL_MS = 5_000L
