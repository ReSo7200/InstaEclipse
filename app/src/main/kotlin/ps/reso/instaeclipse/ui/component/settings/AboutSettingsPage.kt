package ps.reso.instaeclipse.ui.component.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ps.reso.instaeclipse.BuildConfig
import ps.reso.instaeclipse.R
import ps.reso.instaeclipse.ui.component.common.AppWordmark
import ps.reso.instaeclipse.ui.theme.AppSpacing
import ps.reso.instaeclipse.utils.ui.AvatarLoader

data class ContributorItem(
    val name: String,
    val role: String? = null,
    val githubUrl: String? = null,
    val linkedinUrl: String? = null,
    val telegramUrl: String? = null
)

private val AVATAR_COLORS = listOf(
    Color(0xFF5E5CE6),
    Color(0xFFFF375F),
    Color(0xFFFF9F0A),
    Color(0xFF30D158),
    Color(0xFF64D2FF),
    Color(0xFFBF5AF2),
    Color(0xFFFF9500),
    Color(0xFF32D74B),
    Color(0xFF0A84FF),
    Color(0xFFFFD60A)
)

private val CONTRIBUTORS = listOf(
    ContributorItem("ReSo7200", "Lead Developer", "https://github.com/ReSo7200", "https://linkedin.com/in/abdalhaleem-altamimi", null),
    ContributorItem("swakwork", "Developer", "https://github.com/swakwork"),
    ContributorItem("isma3iloiso", "Contributor", "https://github.com/isma3iloiso"),
    ContributorItem("Placeholder6", "Contributor", "https://github.com/Placeholder6"),
    ContributorItem("frknkrc44", "Contributor", "https://github.com/frknkrc44"),
    ContributorItem("BrianML", "Contributor", "https://github.com/brianml31", null, "https://t.me/instamoon_channel"),
    ContributorItem("silvzr", "Contributor", "https://github.com/silvzr"),
    ContributorItem("oct", "Contributor", "https://github.com/oct888"),
    ContributorItem("HalfManBear", "Contributor", "https://github.com/halfmanbear"),
    ContributorItem("ar5to", "Contributor", "https://github.com/ar5to", null, "https://t.me/ar5to"),
    ContributorItem("particle-box", "Contributor", "https://github.com/particle-box"),
    ContributorItem("rsr", "Contributor", null, null, "https://t.me/rsr1337"),
    ContributorItem("akifakif32", "Contributor", "https://github.com/akifakif32"),
    ContributorItem("HackZy01", "Contributor", "https://github.com/HackZy01"),
    ContributorItem("Xiddoc", "Contributor", "https://github.com/Xiddoc"),
    ContributorItem("GlitchDaBest", "Contributor", "https://github.com/GlitchDaBest"),
    ContributorItem("NicKz101", "Contributor", "https://github.com/NicKz101"),
    ContributorItem("HcNguyen111", "Contributor", "https://github.com/HcNguyen111"),
    ContributorItem("Lxchoooo", "Contributor", "https://github.com/Lxchoooo"),
    ContributorItem("EscapeA", "Contributor", "https://github.com/EscapeA"),
    ContributorItem("Figim", "Contributor", "https://github.com/Figim"),
    ContributorItem("oka1da", "Contributor", "https://github.com/oka1da"),
    ContributorItem("uurcan7", "Contributor", "https://github.com/uurcan7"),
    ContributorItem("zarzet", "Contributor", "https://github.com/zarzet"),
    ContributorItem("xxOrdulu52xx", "Contributor", "https://github.com/xxOrdulu52xx"),
    ContributorItem("dpwbusr", "Contributor", "https://github.com/dpwbusr"),
    ContributorItem("d9k6s", "Contributor", "https://github.com/d9k6s"),
    ContributorItem("izadiegizabal", "Contributor", "https://github.com/izadiegizabal"),
    ContributorItem("b4lol", "Contributor", "https://github.com/b4lol"),
    ContributorItem("s1ddhants1", "Contributor", "https://github.com/s1ddhants1")
)

private val SPECIAL_THANKS = listOf(
    ContributorItem("xHookman", "Pioneer & Research", "https://github.com/xHookman"),
    ContributorItem("Bluepapilte", "Community & SmashRepo", null, null, "https://t.me/instasmashrepo"),
    ContributorItem("BdrcnAYYDIN", "Special Support", null, null, "https://t.me/BdrcnAYYDIN"),
    ContributorItem("Amàzing World", "Inspiration & Testing")
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AboutSettingsPage() {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = AppSpacing.md)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
    ) {
        Spacer(Modifier.height(AppSpacing.xxs))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AppSpacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_logo),
                contentDescription = stringResource(R.string.cd_app_icon),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(96.dp)
            )

            AppWordmark(height = 28.dp)

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Text(
                    text = stringResource(R.string.about_version_format, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xxs)
                )
            }
        }

        SettingsSectionCard(title = stringResource(R.string.about_authors_maintainers)) {
            CONTRIBUTORS.forEachIndexed { index, contributor ->
                if (index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        modifier = Modifier.padding(vertical = AppSpacing.xs)
                    )
                }
                ContributorItemRow(contributor = contributor, onOpenUrl = { uriHandler.openUri(it) })
            }
        }

        SettingsSectionCard(title = stringResource(R.string.about_special_thanks)) {
            SPECIAL_THANKS.forEachIndexed { index, contributor ->
                if (index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        modifier = Modifier.padding(vertical = AppSpacing.xs)
                    )
                }
                ContributorItemRow(contributor = contributor, onOpenUrl = { uriHandler.openUri(it) })
            }
        }


        Spacer(Modifier.height(AppSpacing.md))
    }
}

@Composable
private fun ContributorAvatar(
    name: String,
    githubUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    val initial = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    val colorIndex = Math.abs(name.hashCode()) % AVATAR_COLORS.size
    val avatarBg = AVATAR_COLORS[colorIndex]

    val avatarUrl = remember(githubUrl) {
        githubUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { url ->
            val clean = if (url.endsWith("/")) url.dropLast(1) else url
            "$clean.png?size=144"
        }
    }

    var bitmap by remember(avatarUrl) {
        mutableStateOf<Bitmap?>(avatarUrl?.let { AvatarLoader.getCached(it) })
    }

    LaunchedEffect(avatarUrl) {
        if (avatarUrl != null && bitmap == null) {
            val loaded = withContext(Dispatchers.IO) {
                AvatarLoader.fetchBitmap(avatarUrl)
            }
            if (loaded != null) {
                bitmap = loaded
            }
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(avatarBg)
    ) {
        val currentBitmap = bitmap
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = initial,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }
    }
}

@Composable
private fun ContributorItemRow(
    contributor: ContributorItem,
    onOpenUrl: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
            modifier = Modifier.weight(1f)
        ) {
            ContributorAvatar(
                name = contributor.name,
                githubUrl = contributor.githubUrl
            )

            Column {
                Text(
                    text = contributor.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                if (contributor.role != null) {
                    Text(
                        text = contributor.role,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            contributor.githubUrl?.let { url ->
                IconButton(
                    onClick = { onOpenUrl(url) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_github_logo),
                        contentDescription = "GitHub",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            contributor.linkedinUrl?.let { url ->
                IconButton(
                    onClick = { onOpenUrl(url) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_linkedin_logo),
                        contentDescription = "LinkedIn",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            contributor.telegramUrl?.let { url ->
                IconButton(
                    onClick = { onOpenUrl(url) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_telegram_logo),
                        contentDescription = "Telegram",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
