package ps.reso.instaeclipse.ui.component.faq

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ps.reso.instaeclipse.R
import ps.reso.instaeclipse.ui.component.settings.SettingsSectionCard
import ps.reso.instaeclipse.ui.theme.AppSpacing

@Composable
fun FaqScreen(
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current

    val faqList = FAQ_STRINGS.map { parseFaq(stringResource(it)) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = AppSpacing.md)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
    ) {
        Spacer(Modifier.height(AppSpacing.xxs))

        SettingsSectionCard(title = stringResource(R.string.frequently_asked_questions)) {
            faqList.forEachIndexed { index, (question, answer) ->
                if (index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        modifier = Modifier.padding(vertical = AppSpacing.xs)
                    )
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = question,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = answer,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        SettingsSectionCard(title = stringResource(R.string.module_not_working_title)) {
            Text(
                text = "If features are not applying or the app behaves unexpectedly, check the instructions below:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(AppSpacing.xs))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { uriHandler.openUri("https://github.com/JingMatrix/LSPosed/releases/latest") }
                            .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_vector_statue),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(38.dp)
                                    .padding(4.dp)
                            )
                        }

                        Spacer(Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.help_lsposed_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.help_lsposed_sub),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        modifier = Modifier.padding(start = 78.dp, end = 16.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { uriHandler.openUri("https://github.com/JingMatrix/LSPatch/releases/latest") }
                            .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_logo_lspatch),
                            contentDescription = null,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )

                        Spacer(Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.help_lspatch_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.help_lspatch_sub),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    TextButton(
                        onClick = { uriHandler.openUri("https://t.me/instaEclipse_discussion/158/5149") },
                        modifier = Modifier.padding(start = 70.dp, bottom = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.help_lspatch_guide),
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.AutoMirrored.Filled.Launch,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }

        SettingsSectionCard(title = stringResource(R.string.help_community)) {
            Button(
                onClick = { uriHandler.openUri("https://github.com/ReSo7200/InstaEclipse") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_github_logo),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(AppSpacing.xs))
                Text(stringResource(R.string.btn_instaeclipse_github), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(AppSpacing.xxs))
                Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(16.dp))
            }

            FilledTonalButton(
                onClick = { uriHandler.openUri("https://t.me/InstaEclipse") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_telegram_logo),
                    contentDescription = stringResource(R.string.cd_telegram_icon),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(AppSpacing.xs))
                Text(stringResource(R.string.btn_telegram_support), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(AppSpacing.xxs))
                Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }

        Spacer(Modifier.height(AppSpacing.md))
    }
}

private val FAQ_STRINGS = intArrayOf(
    R.string.faq_1,
    R.string.faq_2,
    R.string.faq_3,
    R.string.faq_4,
    R.string.faq_5,
    R.string.faq_6,
    R.string.faq_7,
    R.string.faq_8,
)

internal fun parseFaq(raw: CharSequence): Pair<String, String> {
    val text = raw.toString()
        .replace("<![CDATA[", "")
        .replace("]]>", "")
        .replace("<br/>", "\n")
        .replace("<br>", "\n")
        .replace("<b>", "")
        .replace("</b>", "")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .trim()

    var cut = text.indexOf('\n')
    var answerStart = cut + 1
    if (cut < 0) {
        for (i in text.indices) {
            val c = text[i]
            if (c == '?' || c == '؟' || c == '？') {
                cut = i + 1
                answerStart = cut
                break
            }
        }
    }
    if (cut <= 0 || cut >= text.length) return text to ""
    val q = text.substring(0, cut).trim()
    var a = text.substring(answerStart).trim()
    if (a.startsWith('-')) a = a.substring(1).trim()
    return q to a
}
