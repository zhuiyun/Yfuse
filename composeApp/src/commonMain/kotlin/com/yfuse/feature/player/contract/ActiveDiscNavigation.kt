package com.yfuse.feature.player

import com.yfuse.core.logging.AppLog
import com.yfuse.core.playback.PlaybackDiscMenuCommand
import com.yfuse.core.playback.PlaybackDiscNavigationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-local binding for the disc-navigation popup and platform remote input.
 *
 * The player already owns the backend lifecycle; this bridge only lets common UI issue a direct
 * title/chapter/angle/menu command without threading an engine instance through every composable. The
 * owner identity check prevents an outgoing engine from clearing a newer handover binding.
 */
object ActiveDiscNavigation {
    private data class Binding(
        val owner: Any,
        val backend: DiscNavigationBackend,
    )

    private var binding: Binding? = null
    private val mutableRevision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()

    val isBound: Boolean get() = binding != null

    val navigation: PlaybackDiscNavigationState
        get() = binding?.backend?.navigation ?: PlaybackDiscNavigationState()

    val status: DiscNavigationBackendStatus
        get() = binding?.backend?.status ?: DiscNavigationBackendStatus()

    val menuActive: Boolean
        get() = navigation.menuActive && status.interactiveMenuReady

    internal fun bind(
        owner: Any,
        backend: DiscNavigationBackend,
    ) {
        val previous = binding
        if (previous?.backend !== backend) {
            previous?.backend?.setChangeListener(null)
            previous?.backend?.close()
        }
        binding = Binding(owner = owner, backend = backend)
        backend.setChangeListener {
            if (binding?.backend === backend) bumpRevision()
        }
        bumpRevision()
    }

    internal fun unbind(owner: Any) {
        val active = binding ?: return
        if (active.owner === owner) {
            binding = null
            active.backend.setChangeListener(null)
            active.backend.close()
            bumpRevision()
        }
    }

    fun selectTitle(index: Int): Boolean = binding?.backend?.selectTitle(index) == true

    fun selectChapter(index: Int): Boolean = binding?.backend?.selectChapter(index) == true

    fun selectAngle(index: Int): Boolean = binding?.backend?.selectAngle(index) == true

    /**
     * Menu work runs here, one command at a time, never on the caller's (main) thread: the native
     * disc runtime can hold its lock across network reads for seconds, and a press of 光盘菜单 on a
     * slow share used to freeze the player into an ANR.
     */
    private val menuWorker = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val menuWatchdog = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableMenuBusy = MutableStateFlow(false)
    private val mutableMenuSlow = MutableStateFlow(false)

    /** A menu command is still inside the disc runtime; another is not queued behind it. */
    val menuBusy: StateFlow<Boolean> = mutableMenuBusy.asStateFlow()

    /** The command in flight has taken longer than [MENU_COMMAND_SLOW_MS]: the disc is still reading. */
    val menuSlow: StateFlow<Boolean> = mutableMenuSlow.asStateFlow()

    /**
     * Runs [work] on the menu worker and returns at once. [fallback] runs there too when [work]
     * reports it could not act. Returns false, running nothing, while another command is in flight.
     */
    fun dispatchMenuWork(
        work: () -> Boolean,
        fallback: (() -> Boolean)? = null,
    ): Boolean {
        if (!mutableMenuBusy.compareAndSet(expect = false, update = true)) return false
        val job =
            menuWorker.launch {
                try {
                    if (!work()) fallback?.invoke()
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    AppLog.warning(
                        category = "player.disc",
                        event = "menu_command_failed",
                        message = "Disc menu command failed",
                        attributes = mapOf("exceptionType" to (failure::class.simpleName ?: "Exception")),
                    )
                } finally {
                    mutableMenuBusy.value = false
                    mutableMenuSlow.value = false
                }
            }
        menuWatchdog.launch {
            delay(MENU_COMMAND_SLOW_MS)
            if (job.isActive) mutableMenuSlow.value = true
        }
        return true
    }

    /**
     * Sends [command] to the bound runtime off the caller's thread. True whenever an interactive
     * runtime is bound: the command was handed over, or dropped because one is still in flight - the
     * press was the menu's either way, and must not fall through to back-to-exit.
     */
    fun sendMenuCommand(
        command: PlaybackDiscMenuCommand,
        fallback: (() -> Boolean)? = null,
    ): Boolean {
        val backend = binding?.backend ?: return false
        if (!backend.status.interactiveMenuReady) return false
        dispatchMenuWork({ backend.sendMenuCommand(command) }, fallback)
        return true
    }

    /** Menu commands are ignored unless a real interactive runtime reports an active menu. */
    fun routeActiveMenuCommand(command: PlaybackDiscMenuCommand): Boolean {
        if (!menuActive) return false
        return sendMenuCommand(command)
    }

    /** Touch coordinates are accepted only while the authored interactive plane is visible. */
    fun routeActiveMenuPoint(
        x: Int,
        y: Int,
        activate: Boolean,
    ): Boolean {
        val backend = binding?.backend ?: return false
        if (!menuActive || !backend.status.interactiveMenuReady) return false
        dispatchMenuWork({ backend.selectMenuPoint(x, y, activate) })
        return true
    }

    /** How long a menu command may take before the player says the disc is still reading. */
    private const val MENU_COMMAND_SLOW_MS = 1_500L

    private fun bumpRevision() {
        mutableRevision.value =
            if (mutableRevision.value == Long.MAX_VALUE) 0L else mutableRevision.value + 1L
    }
}
