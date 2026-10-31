package zw.co.accisafe.rdc.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import zw.co.accisafe.rdc.data.net.Me
import zw.co.accisafe.rdc.ui.admin.AuditScreen
import zw.co.accisafe.rdc.ui.admin.ConfigAdminScreen
import zw.co.accisafe.rdc.ui.admin.UsersScreen
import zw.co.accisafe.rdc.ui.auth.LoginScreen
import zw.co.accisafe.rdc.ui.auth.PasswordRecoveryScreen
import zw.co.accisafe.rdc.ui.auth.RegisterScreen
import zw.co.accisafe.rdc.ui.components.DemoBanner
import zw.co.accisafe.rdc.ui.detection.ContactsScreen
import zw.co.accisafe.rdc.ui.detection.MonitorScreen
import zw.co.accisafe.rdc.ui.detection.SimulationScreen
import zw.co.accisafe.rdc.ui.home.HomeScreen
import zw.co.accisafe.rdc.ui.incidents.IncidentDetailScreen
import zw.co.accisafe.rdc.ui.incidents.IncidentListScreen
import zw.co.accisafe.rdc.ui.map.MapScreen
import zw.co.accisafe.rdc.ui.notify.NotificationsScreen
import zw.co.accisafe.rdc.ui.ops.AssignmentInboxScreen
import zw.co.accisafe.rdc.ui.ops.ResourceDetailScreen
import zw.co.accisafe.rdc.ui.ops.ResourcesScreen
import zw.co.accisafe.rdc.ui.ops.TeamDetailScreen
import zw.co.accisafe.rdc.ui.ops.TeamsScreen
import zw.co.accisafe.rdc.ui.report.ReportConfirmationScreen
import zw.co.accisafe.rdc.ui.report.ReportIncidentScreen
import zw.co.accisafe.rdc.ui.report.SyncStatusScreen
import zw.co.accisafe.rdc.ui.reports.ReportsScreen
import zw.co.accisafe.rdc.ui.settings.MoreScreen
import zw.co.accisafe.rdc.ui.settings.PrivacyScreen
import zw.co.accisafe.rdc.ui.settings.ProfileScreen

object Routes {
    const val HOME = "home"
    const val INCIDENTS = "incidents"
    const val MAP = "map"
    const val NOTIFICATIONS = "notifications"
    const val MORE = "more"
    const val REPORT = "report?kind={kind}"
    fun report(kind: String = "EMERGENCY") = "report?kind=$kind"
    const val REPORT_DONE = "report_done/{localId}"
    fun reportDone(localId: String) = "report_done/$localId"
    const val INCIDENT = "incident/{id}"
    fun incident(id: Int) = "incident/$id"
    const val ASSIGNMENTS = "assignments"
    const val TEAMS = "teams"
    const val TEAM = "team/{id}"
    fun team(id: Int) = "team/$id"
    const val RESOURCES = "resources"
    const val RESOURCE = "resource/{id}"
    fun resource(id: Int) = "resource/$id"
    const val REPORTS = "reports"
    const val MONITOR = "monitor"
    const val CONTACTS = "contacts"
    const val SIMULATION = "simulation"
    const val SYNC = "sync"
    const val PROFILE = "profile"
    const val PRIVACY = "privacy"
    const val USERS = "admin_users"
    const val CONFIG = "admin_config"
    const val AUDIT = "audit"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

/** Community reporters land on the live council map; staff land on their dashboard. */
private fun startFor(me: Me) = if (me.isCommunityOnly) Routes.MAP else Routes.HOME

private fun tabsFor(me: Me): List<Tab> = buildList {
    if (me.isCommunityOnly) add(Tab(Routes.MAP, "Live map", Icons.Default.Map))
    add(Tab(Routes.HOME, "Home", Icons.Default.Dashboard))
    add(Tab(Routes.INCIDENTS, if (me.isCommunityOnly) "My reports" else "Incidents", Icons.Default.ListAlt))
    if (!me.isCommunityOnly) add(Tab(Routes.MAP, "Map", Icons.Default.Map))
    add(Tab(Routes.NOTIFICATIONS, "Alerts", Icons.Default.Notifications))
    add(Tab(Routes.MORE, "More", Icons.Default.Menu))
}

@Composable
fun AppRoot(session: SessionViewModel = hiltViewModel()) {
    val me by session.me.collectAsState()
    val nav = rememberNavController()

    val current = me
    if (current == null) {
        AuthNav()
        return
    }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val tabs = tabsFor(current)

    Column(Modifier.fillMaxSize()) {
        if (current.councilIsDemo) DemoBanner()
        androidx.compose.material3.Scaffold(
            bottomBar = {
                if (tabs.any { it.route == route }) {
                    NavigationBar {
                        tabs.forEach { t ->
                            NavigationBarItem(
                                selected = route == t.route,
                                onClick = { nav.navigateTab(t.route) },
                                icon = { Icon(t.icon, null) },
                                label = { Text(t.label) },
                                colors = NavigationBarItemDefaults.colors(),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = startFor(current), modifier = Modifier.fillMaxSize().padding(padding)) {
                composable(Routes.HOME) { HomeScreen(current, nav) }
                composable(Routes.INCIDENTS) { IncidentListScreen(current, nav) }
                composable(Routes.MAP) { MapScreen(current, nav) }
                composable(Routes.NOTIFICATIONS) { NotificationsScreen(nav) }
                composable(Routes.MORE) { MoreScreen(current, nav, onLogout = { session.logout() }) }
                composable(Routes.REPORT, arguments = listOf(navArgument("kind") { defaultValue = "EMERGENCY" })) {
                    ReportIncidentScreen(current, nav, it.arguments?.getString("kind") ?: "EMERGENCY")
                }
                composable(Routes.REPORT_DONE) { ReportConfirmationScreen(it.arguments!!.getString("localId")!!, nav) }
                composable(Routes.INCIDENT, arguments = listOf(navArgument("id") { type = NavType.IntType })) {
                    IncidentDetailScreen(it.arguments!!.getInt("id"), nav)
                }
                composable(Routes.ASSIGNMENTS) { AssignmentInboxScreen(nav) }
                composable(Routes.TEAMS) { TeamsScreen(current, nav) }
                composable(Routes.TEAM, arguments = listOf(navArgument("id") { type = NavType.IntType })) {
                    TeamDetailScreen(it.arguments!!.getInt("id"), current, nav)
                }
                composable(Routes.RESOURCES) { ResourcesScreen(current, nav) }
                composable(Routes.RESOURCE, arguments = listOf(navArgument("id") { type = NavType.IntType })) {
                    ResourceDetailScreen(it.arguments!!.getInt("id"), current, nav)
                }
                composable(Routes.REPORTS) { ReportsScreen(current, nav) }
                composable(Routes.MONITOR) { MonitorScreen(nav) }
                composable(Routes.CONTACTS) { ContactsScreen(nav) }
                composable(Routes.SIMULATION) { SimulationScreen(nav) }
                composable(Routes.SYNC) { SyncStatusScreen(current, nav) }
                composable(Routes.PROFILE) { ProfileScreen(current, nav) }
                composable(Routes.PRIVACY) { PrivacyScreen(nav) }
                composable(Routes.USERS) { UsersScreen(nav) }
                composable(Routes.CONFIG) { ConfigAdminScreen(nav) }
                composable(Routes.AUDIT) { AuditScreen(nav) }
            }
        }
    }
}

@Composable
private fun AuthNav() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "login") {
        composable("login") { LoginScreen(nav) }
        composable("register") { RegisterScreen(nav) }
        composable("recover") { PasswordRecoveryScreen(nav) }
    }
}

fun NavHostController.navigateTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
