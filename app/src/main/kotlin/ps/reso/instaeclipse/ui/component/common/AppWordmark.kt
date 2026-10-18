package ps.reso.instaeclipse.ui.component.common

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ps.reso.instaeclipse.R

private const val WORDMARK_ASPECT_RATIO = 844f / 162f

@Composable
fun AppWordmark(
    modifier: Modifier = Modifier,
    height: Dp = 25.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    contentDescription: String? = stringResource(R.string.app_name),
) {
    Icon(
        painter = painterResource(id = R.drawable.ic_wordmark),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier
            .height(height)
            .aspectRatio(WORDMARK_ASPECT_RATIO)
    )
}
