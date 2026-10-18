package ps.reso.instaeclipse.ui.component.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.outlined.Help
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.ui.graphics.vector.ImageVector
import ps.reso.instaeclipse.R
import ps.reso.instaeclipse.ui.viewmodel.AppNavDestination

data class AppNavEntry(
    val destination: AppNavDestination,
    @StringRes val labelRes: Int,
    @StringRes val shortLabelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector
)

val appNavEntries = listOf(
    AppNavEntry(
        destination = AppNavDestination.HOME,
        labelRes = R.string.home,
        shortLabelRes = R.string.home,
        icon = Icons.Outlined.Home,
        selectedIcon = Icons.Filled.Home
    ),
    AppNavEntry(
        destination = AppNavDestination.FEATURES,
        labelRes = R.string.features,
        shortLabelRes = R.string.features,
        icon = Icons.Outlined.Widgets,
        selectedIcon = Icons.Filled.Widgets
    ),
    AppNavEntry(
        destination = AppNavDestination.LOGS,
        labelRes = R.string.logging,
        shortLabelRes = R.string.logging,
        icon = Icons.Outlined.Terminal,
        selectedIcon = Icons.Filled.Terminal
    ),
    AppNavEntry(
        destination = AppNavDestination.HELP,
        labelRes = R.string.help,
        shortLabelRes = R.string.help,
        icon = Icons.AutoMirrored.Outlined.Help,
        selectedIcon = Icons.AutoMirrored.Filled.Help
    )
)

