package ps.reso.instaeclipse.ui.component.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import ps.reso.instaeclipse.core.PreferencesManager.ThemeMode
import ps.reso.instaeclipse.ui.theme.ksuScreenTransition
import ps.reso.instaeclipse.ui.viewmodel.SettingsSubpage

@Composable
fun SettingsScreen(
    currentSubpage: SettingsSubpage?,
    onNavigateToSubpage: (SettingsSubpage) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    amoledEnabled: Boolean,
    onAmoledToggle: (Boolean) -> Unit,
    onExportConfig: () -> Unit,
    onImportConfig: () -> Unit
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    AnimatedContent(
        targetState = currentSubpage,
        transitionSpec = {
            ksuScreenTransition(forward = targetState != null, isRtl = isRtl)
        },
        label = "SettingsSubpageTransition"
    ) { subpage ->
        when (subpage) {
            null -> SettingsIndexPage(onNavigateToSubpage = onNavigateToSubpage)
            SettingsSubpage.APPEARANCE -> AppearanceSettingsPage(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                amoledEnabled = amoledEnabled,
                onAmoledToggle = onAmoledToggle
            )
            SettingsSubpage.BACKUP -> BackupSettingsPage(
                onExportClick = onExportConfig,
                onImportClick = onImportConfig
            )
            SettingsSubpage.ABOUT -> AboutSettingsPage()
        }
    }
}
