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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.Toast
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.yfuse.app.RootComponent
import com.yfuse.core.account.ACCOUNT_BASE_URL
import com.yfuse.core.account.AccountAccessTokenSource
import com.yfuse.core.handoff.HandoffController
import com.yfuse.core.remote.RemoteControlEvent
import com.yfuse.core.remote.RemoteControlHost
import com.yfuse.feature.search.SearchComponent
import com.yfuse.feature.search.SearchIntent
import com.yfuse.tv.TvMainActivity
import com.yfuse.watch.protocol.RemoteControlKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.koin.core.Koin
import java.lang.ref.WeakReference

/**
 * 手机遥控 on the television. While the app is in the foreground it hosts a remote session on the
 * watch relay, and it replays what a phone of the same account sends: keys into whichever of this
 * app's windows has focus — a dialog over the page included — the way a physical remote presses
 * them, so focus moves everywhere it already moves; text into the focused field, or into 搜索 when
 * no field has focus — but never into a password field.
 */
internal class TvPhoneRemote private constructor(
    private val application: Application,
) : Application.ActivityLifecycleCallbacks {
    private var resumed: WeakReference<Activity>? = null
    private var shell: WeakReference<TvMainActivity>? = null
    private var passwordNoticeAt = 0L

    private fun replay(event: RemoteControlEvent) {
        when (event) {
            is RemoteControlEvent.Key -> press(event.key)
            is RemoteControlEvent.Text -> type(event.text)
            is RemoteControlEvent.Phones ->
                if (event.joined) Toast.makeText(application, "手机遥控已连接", Toast.LENGTH_SHORT).show()
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
            )
        val handoff = koin.get<HandoffController>()
        handoff.hostRemoteControl { host.hosting.value }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        // A phone learns of this television from the handoff heartbeat; send it as soon as that changes.
        scope.launch { host.hosting.drop(1).collect { handoff.refreshPresence() } }
        scope.launch { host.events.collect { replay(it) } }
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = host.setActive(true)

                override fun onStop(owner: LifecycleOwner) = host.setActive(false)
            },
        )
    }

    companion object {
        /**
         * Tracks this app's activities from process start. The sessions restore on a worker and
         * [start] runs after it, usually once the first activity is already created and resumed; a
         * callback registered only then would miss both, and keys from the phone would go nowhere
         * until the next page change.
         */
        fun register(application: Application): TvPhoneRemote =
            TvPhoneRemote(application).also(application::registerActivityLifecycleCallbacks)
    }
}

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
