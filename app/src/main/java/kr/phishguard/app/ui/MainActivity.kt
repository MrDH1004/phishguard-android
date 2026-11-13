package kr.phishguard.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.FamilyRestroom
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kr.phishguard.app.background.BackgroundScheduler
import kr.phishguard.app.background.InstallWatchWorker
import kr.phishguard.app.family.FamilyController
import kr.phishguard.app.family.FamilyScreen
import kr.phishguard.app.guard.CleanupScreen
import kr.phishguard.app.guard.LockOverlay
import kr.phishguard.app.history.HistoryScreen
import kr.phishguard.app.net.BlockList
import kr.phishguard.app.net.MonitorScreen
import kr.phishguard.app.remove.RemovalScreen
import kr.phishguard.app.scan.AppDetailScreen
import kr.phishguard.app.scan.ScanScreen
import kr.phishguard.app.scan.TrustList
import kr.phishguard.app.update.SettingsScreen
import kr.phishguard.app.update.UpdatePromptHost

class MainActivity : ComponentActivity() {
    private var pendingTab by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BlockList.load(applicationContext)
        TrustList.load(applicationContext)
        BackgroundScheduler.ensureScheduled(applicationContext)
        FamilyController.ensureServices(applicationContext)
        pendingTab = intent?.getStringExtra(InstallWatchWorker.EXTRA_OPEN_TAB)
        setContent {
            PhishGuardTheme {
                var onboardingDone by remember { mutableStateOf(isOnboardingDone(applicationContext)) }
                if (onboardingDone) {
                    AppRoot(pendingTab = pendingTab, onTabHandled = { pendingTab = null })
                } else {
                    OnboardingScreen(onFinish = { onboardingDone = true })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 잠금이 남아 있으면 다시 덮고, 그 사이 끝났으면 정리 안내 (리뷰 L2)
        LockOverlay.restoreIfActive(applicationContext)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingTab = intent.getStringExtra(InstallWatchWorker.EXTRA_OPEN_TAB)
    }
}

private data class Tab(val route: String, val label: String, val selectedIcon: ImageVector, val icon: ImageVector)

private val tabs = listOf(
    Tab("scan", "검사", Icons.Filled.Search, Icons.Outlined.Search),
    Tab("monitor", "통신 감시", Icons.Filled.NetworkCheck, Icons.Outlined.NetworkCheck),
    Tab("history", "기록", Icons.Filled.History, Icons.Outlined.History),
    Tab("family", "가족", Icons.Filled.FamilyRestroom, Icons.Outlined.FamilyRestroom),
    Tab("settings", "설정", Icons.Filled.Settings, Icons.Outlined.Settings),
)

@Composable
fun AppRoot(pendingTab: String? = null, onTabHandled: () -> Unit = {}) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    LaunchedEffect(pendingTab) {
        if (pendingTab == "history" || pendingTab == "family" || pendingTab == LockOverlay.TAB_CLEANUP) {
            nav.navigate(pendingTab) {
                popUpTo("scan") { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        if (pendingTab != null) onTabHandled()
    }
    UpdatePromptHost()
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { t ->
                    val selected = current == t.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo("scan") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(if (selected) t.selectedIcon else t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner)) {
            NavHost(nav, startDestination = "scan") {
                composable("scan") { ScanScreen(onOpenApp = { nav.navigate("app/$it") }) }
                composable("monitor") { MonitorScreen(onOpenApp = { nav.navigate("app/$it") }) }
                composable("history") { HistoryScreen(onOpenApp = { nav.navigate("app/$it") }) }
                composable("family") { FamilyScreen() }
                composable(LockOverlay.TAB_CLEANUP) { CleanupScreen(onDone = { nav.popBackStack("scan", inclusive = false) }) }
                composable("settings") { SettingsScreen() }
                composable(
                    "app/{pkg}",
                    arguments = listOf(navArgument("pkg") { type = NavType.StringType }),
                ) { e ->
                    val pkg = e.arguments?.getString("pkg").orEmpty()
                    AppDetailScreen(
                        packageName = pkg,
                        onStartRemoval = { nav.navigate("remove/$it") },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(
                    "remove/{pkg}",
                    arguments = listOf(navArgument("pkg") { type = NavType.StringType }),
                ) { e ->
                    val pkg = e.arguments?.getString("pkg").orEmpty()
                    RemovalScreen(
                        packageName = pkg,
                        onDone = { nav.popBackStack("scan", inclusive = false) },
                        onBack = { nav.popBackStack() },
                    )
                }
            }
        }
    }
}
