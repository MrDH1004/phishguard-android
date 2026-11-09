package kr.phishguard.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BlockList.load(applicationContext)
        TrustList.load(applicationContext)
        setContent { PhishGuardTheme { AppRoot() } }
    }
}

private data class Tab(val route: String, val label: String, val selectedIcon: ImageVector, val icon: ImageVector)

private val tabs = listOf(
    Tab("scan", "검사", Icons.Filled.Search, Icons.Outlined.Search),
    Tab("monitor", "통신 감시", Icons.Filled.NetworkCheck, Icons.Outlined.NetworkCheck),
    Tab("history", "기록", Icons.Filled.History, Icons.Outlined.History),
    Tab("settings", "설정", Icons.Filled.Settings, Icons.Outlined.Settings),
)

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
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
