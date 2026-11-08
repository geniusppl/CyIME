package com.kingzcheung.xime.ui.settings

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kingzcheung.xime.ui.common.menuSwipeBack

data class SettingsNavigationRequest(val route: String, val id: Int)

@Composable
fun SettingsScreen(
    initialRoute: String? = null,
    initialPluginId: String? = null,
    initialPluginSettings: Boolean = false,
    onThemeChanged: () -> Unit = {},
    onWizardBack: () -> Unit = {},
    navigationRequest: SettingsNavigationRequest? = null,
) {
    val navController = rememberNavController()
    val startDestination = if (initialRoute == "manage_dict") SettingsRoutes.Dictionary
    else if (initialRoute == "schema") SettingsRoutes.Schema
    else if (initialRoute == "plugins") SettingsRoutes.Plugins
    else SettingsRoutes.Main

    // Shortcuts enter the existing settings graph with Main underneath, so Back
    // works exactly as it does when the page is opened from the settings home.
    LaunchedEffect(initialRoute) {
        // 键盘提示「缺手写模型」发的是 model_management，冷启动时也要落到模型页（以前只在设置已打开时才认）。
        val route = if (initialRoute == "model_management") SettingsRoutes.ModelLocal else initialRoute
        // 起始页已处理的几个之外，图里有的页面都可直接进（键盘菜单长按跳到对应设置）；不认识的留在首页。
        if (route != null && route !in setOf(SettingsRoutes.Main, "manage_dict", SettingsRoutes.Schema, SettingsRoutes.Plugins)) {
            runCatching { navController.navigate(route) { launchSingleTop = true } }
        }
    }

    LaunchedEffect(initialPluginId) {
        if (initialPluginId != null) {
            // 网络授权也从这里进：详情页有域名授权，也能点「设置」「卸载」；面板里的「设置」直接进设置页。
            navController.navigate(com.kingzcheung.xime.MainActivity.pluginLaunchRoute(initialPluginId, initialPluginSettings))
        }
    }

    LaunchedEffect(navigationRequest) {
        val request = navigationRequest ?: return@LaunchedEffect
        val destination = when (request.route) {
            "manage_dict" -> SettingsRoutes.Dictionary
            "model_management" -> SettingsRoutes.ModelLocal
            else -> request.route
        }
        if (destination == SettingsRoutes.Main) {
            // 冷启动直接进了方案、插件、词库时起始页不是首页：换成首页，不停在那一页。
            if (navController.graph.startDestinationRoute == SettingsRoutes.Main) {
                navController.popBackStack(navController.graph.startDestinationId, inclusive = false)
            } else runCatching {
                navController.navigate(SettingsRoutes.Main) {
                    popUpTo(navController.graph.id) { inclusive = true }
                    launchSingleTop = true
                }
            }
        } else if (navController.currentDestination?.route != destination) {
            runCatching {
                navController.navigate(destination) {
                    popUpTo(navController.graph.startDestinationId) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }
    }
    
    val motion = rememberSettingsMotionEnabled()
    val entry by navController.currentBackStackEntryAsState()
    val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val context = androidx.compose.ui.platform.LocalContext.current
    // 说明里的「」指路：亮哪一项和搜索点开一样；已经在那一页就只滚过去高亮，不再压一层。
    val jump: (SettingsSearchEntry) -> Unit = { target ->
        SettingsSearchFocus.open(target)
        val current = navController.currentBackStackEntry
        if (target.target == SEARCH_TARGET_CUSTOM_LAYOUT) {
            context.startActivity(android.content.Intent(context, com.kingzcheung.xime.CustomLayoutActivity::class.java))
        } else if (!settingsPageMatches(current?.destination?.route, { current?.arguments?.getString(it) }, target.target)) {
            runCatching { navController.navigate(target.target) }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalSettingsJump provides jump) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier.menuSwipeBack(entry, if (back != null && entry != null && entry?.destination?.route != SettingsRoutes.Main) {
            { if (back.hasEnabledCallbacks()) back.onBackPressed() }
        } else null),
        enterTransition = { if (motion) SettingsPageMotion.enter else EnterTransition.None },
        exitTransition = { if (motion) SettingsPageMotion.exit else ExitTransition.None },
        popEnterTransition = { if (motion) SettingsPageMotion.popEnter else EnterTransition.None },
        popExitTransition = { if (motion) SettingsPageMotion.popExit else ExitTransition.None },
    ) {
        composable(SettingsRoutes.Main) {
            androidx.compose.runtime.CompositionLocalProvider(LocalSettingsSearchAnchors provides false) {
                SettingsMainContent(
                    onNavigateToHardwareKeyboard = { navController.navigate(SettingsRoutes.HardwareKeyboard) },
                    onNavigateToLanguages = { navController.navigate("languages") },
                    onNavigateToSchema = { navController.navigate(SettingsRoutes.Schema) },
                    onNavigateToMarket = { navController.navigate(SettingsRoutes.Market) },
                    onNavigateToTheme = { navController.navigate(SettingsRoutes.Theme) },
                    onNavigateToKeyEffect = { navController.navigate(SettingsRoutes.KeyEffect) },
                    onNavigateToLayoutDisplay = { navController.navigate(SettingsRoutes.LayoutDisplay) },
                    onNavigateToDictionary = { navController.navigate(SettingsRoutes.Dictionary) },
                    onNavigateToPlugins = { navController.navigate(SettingsRoutes.Plugins) },
                    onNavigateToModelLocal = { navController.navigate(SettingsRoutes.ModelLocal) },
                    onNavigateToHandwritingTraining = { navController.navigate(SettingsRoutes.HandwritingTraining) },
                    onNavigateToSmartPrediction = { navController.navigate(SettingsRoutes.SmartPrediction) },
                    onNavigateToSpeechToText = { navController.navigate(SettingsRoutes.SpeechToText) },
                    onNavigateToAbout = { navController.navigate(SettingsRoutes.About) },
                    onNavigateToClipboardSync = { navController.navigate(SettingsRoutes.ClipboardSync) },
                    onNavigateToBackup = { navController.navigate(SettingsRoutes.Backup) },
                    onNavigateToRoute = { navController.navigate(it) },
                )
            }
        }
        composable(SettingsRoutes.TypingStats) { TypingStatsScreen { navController.popBackStack() } }
        composable(SettingsRoutes.VocabularyBook) { VocabularyBookScreen { navController.popBackStack() } }
        composable(SettingsRoutes.HardwareKeyboard) { HardwareKeyboardSettingsContent { navController.popBackStack() } }
        composable("languages") {
            LanguageSettingsContent(onBack = { navController.popBackStack() },
                onOpenSchemaResources = { navController.navigate(SettingsRoutes.Schema) },
                onOpenDoublePinyin = { navController.navigate(SettingsRoutes.DoublePinyin) },
                onOpenChineseInput = { navController.navigate(SettingsRoutes.ChineseInput) },
                onOpenEnglishInput = { navController.navigate(SettingsRoutes.EnglishInput) })
        }
        composable(SettingsRoutes.ChineseInput) { ChineseInputSettingsContent { navController.popBackStack() } }
        composable(SettingsRoutes.EnglishInput) { EnglishInputSettingsContent { navController.popBackStack() } }
        composable(SettingsRoutes.DoublePinyin) { DoublePinyinSettingsContent { navController.popBackStack() } }
        composable(SettingsRoutes.Schema) {
            val leaveSchema = {
                if (initialRoute == "schema") {
                    navController.navigate(SettingsRoutes.Main) {
                        popUpTo(SettingsRoutes.Main) { inclusive = true }
                    }
                } else navController.popBackStack()
                onWizardBack()
            }
            BackHandler(enabled = initialRoute == "schema", onBack = leaveSchema)
            SchemaSettingsContent(
                onBack = leaveSchema,
                onNavigateToRimeFileBrowser = { navController.navigate(SettingsRoutes.RimeFileBrowser) },
            )
        }
        composable(
            "${SettingsRoutes.Market}?tab={tab}",
            arguments = listOf(navArgument("tab") { type = NavType.IntType; defaultValue = 0 }),
        ) { entry ->
            MarketHubContent(
                initialTab = entry.arguments?.getInt("tab")?.coerceIn(0, 2) ?: 0,
                onBack = { navController.popBackStack() },
                onNavigateToDetail = { schemeId ->
                    navController.navigate("schema_market_detail/$schemeId")
                },
                onNavigateToModelDetail = { modelId ->
                    navController.navigate("model_market_detail/$modelId")
                },
                onNavigateToPluginDetail = { pluginId -> openInstalledPlugin(navController, pluginId) },
                // 方案的已安装管理与「语言与方案 › 已安装的方案」是同一页，不再另开一份本地方案页。
                onNavigateToLocal = { navController.navigate(SettingsRoutes.Schema) },
                onNavigateToModelLocal = { navController.navigate(SettingsRoutes.ModelLocal) },
                onNavigateToLanguages = { navController.navigate("languages") },
                onNavigateToPlugins = {
                    if (navController.previousBackStackEntry?.destination?.route == SettingsRoutes.Plugins)
                        navController.popBackStack()
                    else navController.navigate(SettingsRoutes.Plugins)
                },
            )
        }
        composable(SettingsRoutes.ModelLocal) {
            ModelLocalContent(
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = SettingsRoutes.SchemaMarketDetail,
            arguments = listOf(navArgument("schemeId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val schemeId = backStackEntry.arguments?.getString("schemeId") ?: return@composable
            MarketSchemeDetailContent(
                schemeId = schemeId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = SettingsRoutes.ModelMarketDetail,
            arguments = listOf(navArgument("modelId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val modelId = backStackEntry.arguments?.getString("modelId") ?: return@composable
            MarketModelDetailContent(
                modelId = modelId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = SettingsRoutes.PluginDetail,
            arguments = listOf(navArgument("pluginId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val pluginId = backStackEntry.arguments?.getString("pluginId") ?: return@composable
            PluginDetailContent(
                pluginId = pluginId,
                onBack = { navController.popBackStack() },
                onOpenSettings = { id -> navController.navigate("${SettingsRoutes.PluginSettings}/$id") },
            )
        }
        composable(SettingsRoutes.Theme) {
            ThemeSettingsContent(
                onBack = { navController.popBackStack() },
                onThemeChanged = onThemeChanged
            )
        }
        composable(SettingsRoutes.Plugins) {
            PluginsSettingsContent(
                onBack = { navController.popBackStack() },
                onNavigateToPluginMarket = {
                    if (navController.previousBackStackEntry?.destination?.route == "${SettingsRoutes.Market}?tab={tab}")
                        navController.popBackStack()
                    else navController.navigate(SettingsRoutes.MarketPlugins)
                },
                onNavigateToPluginSettings = { pluginId ->
                    navController.navigate("${SettingsRoutes.PluginSettings}/$pluginId")
                },
                onNavigateToPluginDetail = { pluginId -> openInstalledPlugin(navController, pluginId) },
                onNavigateToSpeechToText = { navController.navigate(SettingsRoutes.SpeechToText) },
                onNavigateToClipboardSync = { navController.navigate(SettingsRoutes.ClipboardSync) }
            )
        }
        composable(
            route = "${SettingsRoutes.PluginSettings}/{pluginId}",
            arguments = listOf(navArgument("pluginId") { type = NavType.StringType })
        ) { backStackEntry ->
            val pluginId = backStackEntry.arguments?.getString("pluginId")
            PluginSettingsContent(
                pluginId = pluginId ?: "",
                onBack = { navController.popBackStack() }
            )
        }
        composable(SettingsRoutes.Performance) {
            PerformanceSettingsContent(onBack = { navController.popBackStack() },
                onOpenSetupGuide = { navController.navigate(SettingsRoutes.SetupGuide) })
        }
        composable(SettingsRoutes.KeyEffect) {
            KeyEffectSettingsContent(
                onBack = { navController.popBackStack() }
            )
        }
        composable("${SettingsRoutes.LayoutDisplay}?page={page}",
            arguments = listOf(navArgument("page") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
            LayoutDisplaySettingsContent(
                onBack = { navController.popBackStack() },
                onOpenLayout = { navController.navigate(it) },
                initialPage = entry.arguments?.getString("page"),
            )
        }
        LayoutInputCategory.entries.forEach { category ->
            composable(category.route) {
                LayoutInputSettingsContent(category, onBack = { navController.popBackStack() })
            }
        }
        composable(SettingsRoutes.SmartPrediction) {
            SmartPredictionSettingsContent(onBack = { navController.popBackStack() })
        }
        composable(SettingsRoutes.SpeechToText) {
            SpeechToTextSettingsContent(
                onBack = { navController.popBackStack() },
                onNavigateToSetupGuide = { navController.navigate(SettingsRoutes.SetupGuide) },
                onNavigateToPluginSettings = { pluginId ->
                    navController.navigate("${SettingsRoutes.PluginSettings}/$pluginId")
                },
            )
        }
        composable(SettingsRoutes.Dictionary) {
            DictionarySettingsContent(
                onBack = { navController.popBackStack() },
                onCellDictionaries = { navController.navigate(SettingsRoutes.CellDictionaries) },
                onContribute = { navController.navigate(SettingsRoutes.DictContribute) }
            )
        }
        composable(SettingsRoutes.DictContribute) {
            DictContributeScreen(onBack = { navController.popBackStack() })
        }
        composable(SettingsRoutes.CellDictionaries) {
            CellDictionarySettingsContent(onBack = { navController.popBackStack() })
        }
        composable(SettingsRoutes.RimeFileBrowser) {
            RimeFileBrowserContent(
                onBack = { navController.popBackStack() }
            )
        }
        composable(SettingsRoutes.ClipboardSync) {
            ClipboardSyncSettingsContent(
                onBack = { navController.popBackStack() },
                onNavigateToPlugins = { navController.navigate(SettingsRoutes.Plugins) },
                onNavigateToPanel = { navController.navigate(SettingsRoutes.ClipboardPanel) },
            )
        }
        composable(SettingsRoutes.ClipboardPanel) {
            ClipboardPanelSettingsContent(
                onBack = { navController.popBackStack() },
                onNavigateToGroups = { navController.navigate(SettingsRoutes.ClipboardGroups) },
            )
        }
        composable(SettingsRoutes.ClipboardGroups) {
            ClipboardGroupSettingsContent(onBack = { navController.popBackStack() })
        }
        composable(SettingsRoutes.KeyboardResize) {
            KeyboardPanelSettingsContent(com.kingzcheung.xime.keyboard.KeyboardPanelLaunch.Panel.RESIZE) { navController.popBackStack() }
        }
        composable(SettingsRoutes.ToolbarArrange) {
            KeyboardPanelSettingsContent(com.kingzcheung.xime.keyboard.KeyboardPanelLaunch.Panel.ARRANGE) { navController.popBackStack() }
        }
        composable(SettingsRoutes.Other) {
            OtherSettingsContent(onBack = { navController.popBackStack() }, onNavigateToRoute = { navController.navigate(it) })
        }
        composable(SettingsRoutes.Backup) {
            BackupSettingsContent(onBack = { navController.popBackStack() })
        }
        composable(SettingsRoutes.About) {
            AboutContent(
                onBack = { navController.popBackStack() },
                onNavigateToPrivacy = { navController.navigate(SettingsRoutes.Privacy) },
                onNavigateToLicenses = { navController.navigate(SettingsRoutes.Licenses) },
                onNavigateToCredits = { navController.navigate(SettingsRoutes.Credits) },
                onNavigateToLogViewer = { navController.navigate(SettingsRoutes.LogViewer) },
                onNavigateToStorageSpace = { navController.navigate(SettingsRoutes.StorageSpace) }
            )
        }
        composable(SettingsRoutes.SetupGuide) {
            SetupGuideScreen(firstRun = false, onDone = { navController.popBackStack() },
                onOpenPerformance = { navController.navigate(SettingsRoutes.Performance) },
                onOpenRoute = { navController.navigate(it) })
        }
        composable(SettingsRoutes.StorageSpace) {
            StorageSpaceScreen(
                onBack = { navController.popBackStack() },
                onNavigateToPlugins = { navController.navigate(SettingsRoutes.Plugins) },
                onNavigateToModels = { navController.navigate(SettingsRoutes.ModelLocal) },
                onNavigateToPerformance = { navController.navigate(SettingsRoutes.Performance) }
            )
        }
        composable(SettingsRoutes.LogViewer) {
            LogViewerScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(SettingsRoutes.Handwriting) {
            HandwritingSettingsContent(onBack = { navController.popBackStack() },
                onOpenModels = { navController.navigate(SettingsRoutes.ModelLocal) },
                onOpenTraining = { navController.navigate(SettingsRoutes.HandwritingTraining) },
                onOpenContribute = { navController.navigate(SettingsRoutes.HandwritingContribute) })
        }
        composable(SettingsRoutes.HandwritingContribute) {
            HandwritingTrainingScreen(
                onBack = { navController.popBackStack() },
                contribute = true,
            )
        }
        composable(SettingsRoutes.HandwritingTraining) {
            val context = androidx.compose.ui.platform.LocalContext.current
            HandwritingTrainingScreen(
                onBack = { navController.popBackStack() },
                // 上次停在贡献题就接着写贡献题
                contribute = androidx.compose.runtime.remember {
                    com.kingzcheung.xime.handwriting.training.HandwritingContribution.lastOpened(context)
                },
            )
        }
        composable(SettingsRoutes.Privacy) {
            PrivacyPolicyContent(
                onBack = { navController.popBackStack() }
            )
        }
        composable(SettingsRoutes.Licenses) {
            LicensesContent(
                onBack = { navController.popBackStack() }
            )
        }
        composable(SettingsRoutes.Credits) {
            CreditsContent(onBack = { navController.popBackStack() })
        }
    }
    }
}

/**
 * 点已装的插件：能用的直接进它的设置页（填密钥、开关、网络授权都在那里）；已下架的进详情页，
 * 那里写着功能去了哪里、可以卸载（11-03「装完了点开啥都没有，空页面」）。
 */
private fun openInstalledPlugin(navController: androidx.navigation.NavController, pluginId: String) {
    navController.navigate(pluginPageRoute(pluginId))
}
