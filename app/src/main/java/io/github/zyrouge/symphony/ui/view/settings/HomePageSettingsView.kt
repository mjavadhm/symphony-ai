package io.github.zyrouge.symphony.ui.view.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Recommend
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import io.github.zyrouge.symphony.ui.components.GlassSettingsScaffold
import io.github.zyrouge.symphony.ui.components.IconButtonPlaceholder
import io.github.zyrouge.symphony.ui.components.LocalHazeState
import io.github.zyrouge.symphony.ui.components.TopAppBarMinimalTitle
import io.github.zyrouge.symphony.ui.components.settings.SettingsMultiOptionTile
import io.github.zyrouge.symphony.ui.components.settings.SettingsOptionTile
import io.github.zyrouge.symphony.ui.components.settings.SettingsSideHeading
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.ui.view.HomePage
import io.github.zyrouge.symphony.ui.view.HomePageBottomBarLabelVisibility
import io.github.zyrouge.symphony.ui.view.home.ForYou
import kotlinx.serialization.Serializable

@Serializable
object HomePageSettingsViewRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomePageSettingsView(context: ViewContext) {
    val scrollState = rememberScrollState()
    val homeTabs by context.symphony.settings.homeTabs.flow.collectAsState()
    val forYouContents by context.symphony.settings.forYouContents.flow.collectAsState()
    val homePageBottomBarLabelVisibility by context.symphony.settings.homePageBottomBarLabelVisibility.flow.collectAsState()
    val savedOnlineUrl by context.symphony.settings.onlineServiceBaseUrl.flow.collectAsState()
    var onlineUrl by remember(savedOnlineUrl) { mutableStateOf(savedOnlineUrl) }
    val validOnlineUrl = remember(onlineUrl) {
        runCatching {
            val uri = java.net.URI(onlineUrl)
            (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
        }.getOrDefault(false)
    }

    GlassSettingsScaffold(
        context = context,
        title = "${context.symphony.t.Settings} - ${context.symphony.t.Home}",
        content = { contentPadding ->
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(state = LocalHazeState.current, zIndex = 1f)
                        .verticalScroll(scrollState)
                        .padding(contentPadding)
                ) {
                    SettingsSideHeading(context.symphony.t.Home)
                    OutlinedTextField(
                        value = onlineUrl,
                        onValueChange = { value ->
                            onlineUrl = value
                            val valid = runCatching {
                                val uri = java.net.URI(value)
                                (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
                            }.getOrDefault(false)
                            if (valid) context.symphony.settings.onlineServiceBaseUrl.setValue(value.trimEnd('/'))
                        },
                        modifier = Modifier.padding(horizontal = 16.dp),
                        label = { Text("Online service URL") },
                        supportingText = { if (!validOnlineUrl) Text("Enter a valid HTTP(S) URL") },
                        isError = !validOnlineUrl,
                        singleLine = true,
                    )
                    HorizontalDivider()
                    SettingsMultiOptionTile(
                        context,
                        icon = {
                            Icon(Icons.Filled.Home, null)
                        },
                        title = {
                            Text(context.symphony.t.HomeTabs)
                        },
                        note = {
                            Text(context.symphony.t.SelectAtleast2orAtmost5Tabs)
                        },
                        value = homeTabs,
                        values = HomePage.entries.associateWith { it.label(context) },
                        satisfies = { it.size in 2..5 },
                        onChange = { value ->
                            context.symphony.settings.homeTabs.setValue(value)
                        }
                    )
                    HorizontalDivider()
                    SettingsMultiOptionTile(
                        context,
                        icon = {
                            Icon(Icons.Filled.Recommend, null)
                        },
                        title = {
                            Text(context.symphony.t.ForYou)
                        },
                        value = forYouContents,
                        values = ForYou.entries.associateWith { it.label(context) },
                        onChange = { value ->
                            context.symphony.settings.forYouContents.setValue(value)
                        }
                    )
                    HorizontalDivider()
                    SettingsOptionTile(
                        icon = {
                            Icon(Icons.AutoMirrored.Filled.Label, null)
                        },
                        title = {
                            Text(context.symphony.t.BottomBarLabelVisibility)
                        },
                        value = homePageBottomBarLabelVisibility,
                        values = HomePageBottomBarLabelVisibility.entries
                            .associateWith { it.label(context) },
                        onChange = { value ->
                            context.symphony.settings.homePageBottomBarLabelVisibility.setValue(
                                value,
                            )
                        }
                    )
                }
            }
        }
    )
}

fun HomePageBottomBarLabelVisibility.label(context: ViewContext) = when (this) {
    HomePageBottomBarLabelVisibility.ALWAYS_VISIBLE -> context.symphony.t.AlwaysVisible
    HomePageBottomBarLabelVisibility.VISIBLE_WHEN_ACTIVE -> context.symphony.t.VisibleWhenActive
    HomePageBottomBarLabelVisibility.INVISIBLE -> context.symphony.t.Invisible
}
