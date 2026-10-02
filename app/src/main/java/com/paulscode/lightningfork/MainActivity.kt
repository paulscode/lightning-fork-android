package com.paulscode.lightningfork

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.paulscode.lightningfork.data.AmountUnit
import com.paulscode.lightningfork.lock.BiometricGate
import com.paulscode.lightningfork.lock.LockScreen
import com.paulscode.lightningfork.ui.activity.ActivityScreen
import com.paulscode.lightningfork.ui.home.HomeScreen
import com.paulscode.lightningfork.ui.pair.PairScreen
import com.paulscode.lightningfork.ui.pair.PairViewModel
import com.paulscode.lightningfork.ui.receive.ReceiveScreen
import com.paulscode.lightningfork.ui.receive.ReceiveViewModel
import com.paulscode.lightningfork.ui.send.SendScreen
import com.paulscode.lightningfork.ui.send.SendViewModel
import com.paulscode.lightningfork.ui.settings.SettingsScreen
import com.paulscode.lightningfork.ui.theme.LightningForkTheme
import com.paulscode.lightningfork.ui.theme.Page
import kotlinx.coroutines.launch

/** Where the app can be. */
sealed interface Dest {
    data object Home : Dest
    data class Send(val prefill: String? = null) : Dest
    data object Receive : Dest
    data object Activity : Dest
    data object Settings : Dest
}

/** A screen on the stack, owning its view models: they end when it is popped. */
class Entry(val dest: Dest) : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

/** The back stack, kept across configuration changes. */
class Navigator : ViewModel() {
    val stack = mutableStateListOf(Entry(Dest.Home))
    var forward by mutableStateOf(true)
        private set

    fun push(dest: Dest) {
        forward = true
        stack.add(Entry(dest))
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        forward = false
        stack.removeAt(stack.lastIndex).viewModelStore.clear()
        return true
    }

    fun home() {
        forward = false
        while (stack.size > 1) stack.removeAt(stack.lastIndex).viewModelStore.clear()
    }

    override fun onCleared() {
        stack.forEach { it.viewModelStore.clear() }
    }
}

class MainActivity : FragmentActivity() {
    private val container get() = (application as App).container
    private lateinit var nav: Navigator

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Keep balances out of the recent-apps thumbnail. Screenshots stay
        // allowed: people share an invoice's QR code that way.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)

        nav = ViewModelProvider(this)[Navigator::class.java]
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                container.lock.onStart()
                if (container.isPaired) container.wallet.setForeground(true)
            }

            override fun onStop(owner: LifecycleOwner) {
                container.lock.onStop()
                container.wallet.setForeground(false)
            }
        })
        if (savedInstanceState == null) handleLink(intent)

        setContent {
            LightningForkTheme {
                Box(Modifier.fillMaxSize().background(Page)) {
                    var paired by androidx.compose.runtime.remember { mutableStateOf(container.isPaired) }
                    if (!paired) {
                        // A fresh owner per pairing, so pairing again after an
                        // unpair starts from the beginning.
                        val owner = androidx.compose.runtime.remember { Entry(Dest.Home) }
                        androidx.compose.runtime.DisposableEffect(owner) {
                            onDispose { owner.viewModelStore.clear() }
                        }
                        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                            PairFlow {
                                paired = true
                                container.wallet.setForeground(true)
                            }
                        }
                    } else {
                        LockGate { App(onUnpaired = { paired = false }) }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /**
     * A bitcoin: or lightning: link opens Send with it filled in. A
     * lightningfork://pair link (the dashboard's "Open in the app", for a
     * dashboard opened on this phone) carries a pairing code.
     */
    private fun handleLink(intent: Intent?) {
        val data = intent?.data ?: return
        val scheme = data.scheme?.lowercase() ?: return
        if (scheme == "lightningfork" && data.host == "pair") {
            val code = data.getQueryParameter("c") ?: return
            val json = runCatching {
                String(android.util.Base64.decode(code, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP), Charsets.UTF_8)
            }.getOrNull() ?: return
            if (!container.isPaired) container.pendingPairing.value = json
            return
        }
        if ((scheme == "bitcoin" || scheme == "lightning") && container.isPaired) {
            // Never over a send that is open: its outcome must stay on screen.
            if (nav.stack.any { it.dest is Dest.Send }) return
            nav.home()
            nav.push(Dest.Send(prefill = data.toString()))
        }
    }

    @Composable
    private fun App(onUnpaired: () -> Unit) {
        val wallet by container.wallet.state.collectAsStateWithLifecycle()
        val torStatus by container.tor.status.collectAsStateWithLifecycle()
        val torProgress by container.tor.progress.collectAsStateWithLifecycle()
        var unit by androidx.compose.runtime.remember { mutableStateOf(container.settings.unit) }
        fun setUnit(u: AmountUnit) {
            unit = u
            container.settings.unit = u
        }
        // A phone the node no longer knows: say so, and let the user choose.
        if (wallet.revoked) {
            com.paulscode.lightningfork.ui.pair.RemovedScreen(
                onPairAgain = {
                    nav.home()
                    container.unpair()
                    onUnpaired()
                },
                onTryAgain = {
                    container.wallet.clearRevoked()
                    container.appScope.launch { container.wallet.refresh() }
                },
            )
            return
        }
        BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
        val top = nav.stack.last()
        AnimatedContent(
            targetState = top,
            transitionSpec = {
                val dir = if (nav.forward) 1 else -1
                (slideInHorizontally(tween(260)) { it / 6 * dir } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(260)) { -it / 6 * dir } + fadeOut(tween(180)))
            },
            label = "nav",
        ) { entry ->
            CompositionLocalProvider(LocalViewModelStoreOwner provides entry) {
                when (val dest = entry.dest) {
                    Dest.Home -> HomeScreen(
                        state = wallet,
                        unit = unit,
                        onToggleUnit = { setUnit(if (unit == AmountUnit.Sats) AmountUnit.Btc else AmountUnit.Sats) },
                        onRefresh = { container.wallet.refresh() },
                        onSend = { nav.push(Dest.Send()) },
                        onReceive = { nav.push(Dest.Receive) },
                        onActivity = { nav.push(Dest.Activity) },
                        onSettings = { nav.push(Dest.Settings) },
                        torStarting = if (torStatus == com.paulscode.lightningfork.net.TorStatus.Bootstrapping) torProgress else null,
                    )
                    is Dest.Send -> {
                        val vm: SendViewModel = viewModel(factory = viewModelFactory {
                            initializer { SendViewModel(container.api, container.wallet, container.settings, dest.prefill) }
                        })
                        SendScreen(vm, wallet, startScanning = false, onClose = { nav.pop() })
                    }
                    Dest.Receive -> {
                        val vm: ReceiveViewModel = viewModel(factory = viewModelFactory {
                            initializer { ReceiveViewModel(container.api, container.wallet, container.settings) }
                        })
                        ReceiveScreen(vm, wallet, onClose = { nav.pop() })
                    }
                    Dest.Activity -> ActivityScreen(container.api, unit, onClose = { nav.pop() })
                    Dest.Settings -> SettingsScreen(
                        container = container,
                        wallet = wallet,
                        unit = unit,
                        onUnit = ::setUnit,
                        lockAvailable = BiometricGate.isAvailable(this@MainActivity),
                        onClose = { nav.pop() },
                        onUnpaired = {
                            nav.home()
                            onUnpaired()
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun LockGate(content: @Composable () -> Unit) {
        val locked by container.lock.locked.collectAsStateWithLifecycle()
        val wanted = container.settings.appLock && BiometricGate.isAvailable(this)
        LaunchedEffect(wanted) { if (!wanted) container.lock.disable() }
        if (locked && wanted) {
            LaunchedEffect(Unit) { promptUnlock() }
            LockScreen(onUnlock = ::promptUnlock)
        } else {
            content()
        }
    }

    private fun promptUnlock() {
        BiometricGate.prompt(
            activity = this,
            onSuccess = { container.lock.unlock() },
            onFailure = { /* stays locked; Unlock tries again */ },
        )
    }

    @Composable
    private fun PairFlow(onPaired: () -> Unit) {
        val vm: PairViewModel = viewModel(factory = viewModelFactory {
            initializer {
                PairViewModel(container.pairing, container.tor, defaultLabel = Build.MODEL ?: "Android phone")
            }
        })
        val ui by vm.ui.collectAsStateWithLifecycle()
        val linked by container.pendingPairing.collectAsStateWithLifecycle()
        LaunchedEffect(linked) {
            linked?.let {
                container.pendingPairing.value = null
                vm.onPasted(it)
            }
        }
        LaunchedEffect(ui.done) {
            if (ui.done) {
                container.lock.unlock()
                onPaired()
            }
        }
        PairScreen(vm)
    }
}
