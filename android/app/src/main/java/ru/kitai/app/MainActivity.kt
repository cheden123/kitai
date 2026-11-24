package ru.kitai.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val model: KitaiViewModel by viewModels()
    private val updater: KitaiUpdateViewModel by viewModels()
    private val updateInstaller = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updater.installFinished(it.resultCode)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by model.settings.collectAsStateWithLifecycle()
            val dark = when (settings.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            val scheme = if (dark) nightColors else dayColors
            LaunchedEffect(dark) {
                window.statusBarColor = scheme.background.toArgb()
                window.navigationBarColor = scheme.surfaceContainer.toArgb()
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            MaterialTheme(colorScheme = scheme) {
                KitaiApp(model, settings, updater) { updateInstaller.launch(it) }
            }
        }
    }
}

private val dayColors = lightColorScheme(
    primary = Color(0xFF205746), onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE8D5), onPrimaryContainer = Color(0xFF143E30),
    secondary = Color(0xFF4F634E), secondaryContainer = Color(0xFFDBE9CD),
    onSecondaryContainer = Color(0xFF273E28),
    background = Color(0xFFFAF9F5), onBackground = Color(0xFF222820),
    surface = Color(0xFFFFFEFA), onSurface = Color(0xFF222820),
    surfaceVariant = Color(0xFFE9ECE3), onSurfaceVariant = Color(0xFF485248),
    surfaceContainer = Color(0xFFF1F2EB), outline = Color(0xFF768176),
    outlineVariant = Color(0xFFD1DAD0),
)

private val nightColors = darkColorScheme(
    primary = Color(0xFFA8D6BC), onPrimary = Color(0xFF12382B),
    primaryContainer = Color(0xFF204C3C), onPrimaryContainer = Color(0xFFD1F0D9),
    secondary = Color(0xFFB9CEAB), secondaryContainer = Color(0xFF354836),
    onSecondaryContainer = Color(0xFFE0F0D7),
    background = Color(0xFF161C18), onBackground = Color(0xFFE9EEE6),
    surface = Color(0xFF1C251E), onSurface = Color(0xFFE9EEE6),
    surfaceVariant = Color(0xFF303C33), onSurfaceVariant = Color(0xFFBCCBBE),
    surfaceContainer = Color(0xFF202A23), outline = Color(0xFF9CAA9D),
    outlineVariant = Color(0xFF455548),
)

private enum class Destination(val title: String) {
    CATALOG("Каталог"), SAVED("Сохранено"), COMPARE("Сравнение"), TRANSLATE("Перевод"),
}

data class WebDestination(val market: Market, val url: String)

private val webDestinationSaver = listSaver<WebDestination?, String>(
    save = { destination -> destination?.let { listOf(it.market.id, it.url) }.orEmpty() },
    restore = { parts ->
        val market = parts.firstOrNull()?.let { Market.byId(it) }
        val address = parts.getOrNull(1)
        if (market != null && address != null && market.accepts(address)) WebDestination(market, address) else null
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KitaiApp(
    model: KitaiViewModel,
    settings: Settings,
    updater: KitaiUpdateViewModel,
    launchInstaller: (Intent) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val tabState = rememberSaveableStateHolder()
    val notice by model.notice.collectAsStateWithLifecycle()
    val detail by model.detail.collectAsStateWithLifecycle()
    val saved by model.saved.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Destination.CATALOG) }
    var inSettings by rememberSaveable { mutableStateOf(false) }
    var web by rememberSaveable(stateSaver = webDestinationSaver) { mutableStateOf<WebDestination?>(null) }
    var productAddress by rememberSaveable { mutableStateOf<String?>(null) }
    var productTitle by rememberSaveable { mutableStateOf("") }
    val openOriginal: (Market, String) -> Unit = { market, address ->
        if (market.accepts(address)) web = WebDestination(market, address)
    }
    val inOverlay = web != null || inSettings || detail != null
    val goBack = {
        when {
            web != null -> web = null
            inSettings -> inSettings = false
            detail != null -> model.closeProduct()
            else -> Unit
        }
    }
    BackHandler(inOverlay, goBack)
    LaunchedEffect(notice) {
        notice?.let { snackbar.showSnackbar(it) }
        if (notice != null) model.dismissNotice()
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    val title = when {
                        web != null -> web?.market?.title.orEmpty()
                        inSettings -> "Настройки"
                        detail != null -> "Предложение"
                        else -> "Kitai"
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!inOverlay) BrandMark()
                        Text(title, fontWeight = FontWeight.SemiBold)
                    }
                },
                navigationIcon = {
                    if (inOverlay) {
                        IconButton(onClick = goBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                        }
                    }
                },
                actions = {
                    if (!inOverlay) {
                        IconButton(onClick = { inSettings = true }) {
                            Icon(Icons.Filled.Settings, "Настройки")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!inOverlay) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Destination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = tab == destination,
                            onClick = { tab = destination },
                            icon = {
                                when (destination) {
                                    Destination.CATALOG -> Icon(Icons.Filled.Search, null)
                                    Destination.SAVED -> Icon(Icons.Filled.FavoriteBorder, null)
                                    Destination.COMPARE -> Icon(Icons.AutoMirrored.Filled.List, null)
                                    Destination.TRANSLATE -> Text("译", Modifier.clearAndSetSemantics {}, fontWeight = FontWeight.Bold)
                                }
                            },
                            label = { Text(destination.title, maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val activeWeb = web
            val activeDetail = detail
            when {
                activeWeb != null -> MarketplaceWebScreen(
                    destination = activeWeb,
                    canTranslate = settings.openRouterKey.isNotBlank(),
                    openTranslationSettings = {
                        web = null
                        inSettings = true
                    },
                    translatePage = model::translateMarketplacePage,
                    onVisit = model::recordMarketplaceVisit,
                    onAddProduct = { link, title ->
                        productTitle = title
                        productAddress = link.url
                    },
                )
                inSettings -> SettingsScreen(settings, updater, launchInstaller) {
                    model.saveSettings(it) { inSettings = false }
                }
                activeDetail != null -> ProductDetailScreen(
                    activeDetail,
                    saved.firstOrNull { it.product.key == activeDetail.key },
                    onMark = { action, enabled -> model.mark(activeDetail, action, enabled) },
                    openOriginal = openOriginal,
                )
                else -> tabState.SaveableStateProvider(tab.name) {
                    when (tab) {
                        Destination.CATALOG -> {
                            val catalog by model.catalog.collectAsStateWithLifecycle()
                            CatalogScreen(
                                catalog,
                                settings,
                                model,
                                openOriginal,
                                openSettings = { inSettings = true },
                                addProduct = {
                                    productTitle = ""
                                    productAddress = ""
                                },
                            )
                        }
                        Destination.SAVED -> SavedScreen(saved, model)
                        Destination.COMPARE -> CompareScreen(saved, model)
                        Destination.TRANSLATE -> TranslationScreen(model, settings) { inSettings = true }
                    }
                }
            }
        }
        productAddress?.let { address ->
            val existing = marketplaceProductLink(address)?.let { link ->
                saved.firstOrNull { it.product.source == link.market.id && it.product.id == link.id }?.product
            }
            ManualProductDialog(
                initialAddress = address,
                initialTitle = productTitle,
                existingProduct = existing,
                onDismiss = { productAddress = null },
                onSave = model::saveMarketplaceProduct,
            )
        }
    }
}

@Composable
private fun BrandMark() {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(9.dp),
    ) {
        Text(
            "K",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clearAndSetSemantics {}.padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}
