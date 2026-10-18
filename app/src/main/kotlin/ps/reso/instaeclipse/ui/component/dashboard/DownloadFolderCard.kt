package ps.reso.instaeclipse.ui.component.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ps.reso.instaeclipse.ui.theme.AppSpacing
import ps.reso.instaeclipse.ui.theme.m3FadeThrough

private fun formatDownloadFolderDisplay(uri: String): String {
    if (uri.isBlank()) return uri
    return try {
        val decoded = java.net.URLDecoder.decode(uri, "UTF-8")
        val treeMarker = "/tree/"
        val treeIdx = decoded.indexOf(treeMarker)
        if (decoded.startsWith("content://com.android.externalstorage.documents") && treeIdx >= 0) {
            var docId = decoded.substring(treeIdx + treeMarker.length)
            val docSuffix = "/document/"
            val docSuffixIdx = docId.indexOf(docSuffix)
            if (docSuffixIdx >= 0) docId = docId.substring(0, docSuffixIdx)
            val colonIdx = docId.indexOf(':')
            val volume = if (colonIdx >= 0) docId.substring(0, colonIdx) else "primary"
            val path = if (colonIdx >= 0) docId.substring(colonIdx + 1) else docId
            val root = if (volume.equals("primary", ignoreCase = true)) "Internal storage" else volume
            if (path.isBlank()) root else "$root/$path"
        } else {
            decoded
        }
    } catch (_: Throwable) {
        uri
    }
}

@Composable
fun DownloadFolderCard(
    uri: String,
    onPickFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedContent(
        targetState = uri.isNotBlank(),
        transitionSpec = { m3FadeThrough() },
        label = "DownloadFolderCardTransition",
        modifier = modifier.fillMaxWidth()
    ) { hasFolder ->
        if (hasFolder) {
            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
            Column(modifier = Modifier.padding(AppSpacing.md)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(AppSpacing.xs))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Download Folder",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        val displayPath = remember(uri) { formatDownloadFolderDisplay(uri) }
                        Text(
                            text = displayPath.take(72) + if (displayPath.length > 72) "…" else "",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(AppSpacing.sm))
                FilledTonalButton(
                    onClick = onPickFolder,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Change Download Folder", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    } else {
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(AppSpacing.md)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(AppSpacing.xs))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Select Download Folder",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Select a folder to enable media downloading",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                        )
                    }
                }
                Spacer(Modifier.height(AppSpacing.sm))
                Button(
                    onClick = onPickFolder,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Select Folder", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
}




