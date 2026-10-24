package moe.chenxy.oppopods.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.chenxy.oppopods.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun ThemePage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    config: AppThemeConfig,
    onConfigChange: (AppThemeConfig) -> Unit,
) {
    val modeLabels = listOf(
        stringResource(R.string.theme_follow_system),
        stringResource(R.string.theme_light),
        stringResource(R.string.theme_dark),
        stringResource(R.string.theme_monet_system),
        stringResource(R.string.theme_monet_light),
        stringResource(R.string.theme_monet_dark),
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .overScrollVertical(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        overscrollEffect = null,
    ) {
        item {
            SmallTitle(text = stringResource(R.string.theme_title))
        }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
            ) {
                OverlayDropdownPreference(
                    title = stringResource(R.string.theme_title),
                    items = modeLabels,
                    selectedIndex = config.mode.coerceIn(0, modeLabels.lastIndex),
                    onSelectedIndexChange = {
                        onConfigChange(config.copy(mode = it))
                    },
                )
            }
        }

        item {
            SmallTitle(text = stringResource(R.string.theme_section_navigation))
        }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
            ) {
                Column {
                    SwitchPreference(
                        title = stringResource(R.string.theme_floating_navbar),
                        summary = stringResource(R.string.theme_floating_navbar_summary),
                        checked = config.floatingNavbar,
                        onCheckedChange = {
                            onConfigChange(config.copy(floatingNavbar = it))
                        },
                    )
                    AnimatedVisibility(
                        visible = config.floatingNavbar,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        SwitchPreference(
                            title = stringResource(R.string.theme_liquid_glass),
                            summary = stringResource(R.string.theme_liquid_glass_summary),
                            checked = config.liquidGlass,
                            onCheckedChange = {
                                onConfigChange(config.copy(liquidGlass = it))
                            },
                        )
                    }
                    SwitchPreference(
                        title = stringResource(R.string.theme_background_blur),
                        summary = stringResource(R.string.theme_background_blur_summary),
                        checked = config.blurEnabled,
                        onCheckedChange = {
                            onConfigChange(config.copy(blurEnabled = it))
                        },
                    )
                }
            }
        }
    }
}
