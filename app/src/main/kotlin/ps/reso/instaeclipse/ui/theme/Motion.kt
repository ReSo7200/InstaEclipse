package ps.reso.instaeclipse.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

object MotionDuration {
    const val Short1 = 50
    const val Short2 = 100
    const val Short3 = 150
    const val Short4 = 200

    const val Medium1 = 250
    const val Medium2 = 300
}

object MotionEasing {
    val Emphasized = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
    val Standard = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val Linear = LinearEasing
}

fun m3FadeThrough(): ContentTransform {
    val enter = fadeIn(
        animationSpec = tween(
            durationMillis = MotionDuration.Medium2,
            delayMillis = MotionDuration.Short2,
            easing = MotionEasing.Linear
        )
    ) + scaleIn(
        initialScale = 0.92f,
        animationSpec = tween(
            durationMillis = MotionDuration.Medium2,
            delayMillis = MotionDuration.Short2,
            easing = MotionEasing.EmphasizedDecelerate
        )
    )

    val exit = fadeOut(
        animationSpec = tween(
            durationMillis = MotionDuration.Short2,
            easing = MotionEasing.Linear
        )
    )

    return enter togetherWith exit
}

val m3ContentSizeSpringSpec: FiniteAnimationSpec<IntSize> = spring(
    dampingRatio = 0.8f,
    stiffness = 400f,
    visibilityThreshold = IntSize.VisibilityThreshold
)

val m3ItemPlacementSpringSpec: FiniteAnimationSpec<IntOffset> = spring(
    dampingRatio = 0.8f,
    stiffness = 400f,
    visibilityThreshold = IntOffset.VisibilityThreshold
)

fun ksuTabTransition(
    initialIndex: Int,
    targetIndex: Int,
    isRtl: Boolean = false
): ContentTransform {
    val animationDirection = if (isRtl) -1 else 1
    val offsetSign = (if (targetIndex > initialIndex) 1 else -1) * animationDirection
    val enter = slideInHorizontally(
        initialOffsetX = { it * offsetSign },
        animationSpec = tween(durationMillis = 300)
    )
    val exit = slideOutHorizontally(
        targetOffsetX = { -it * offsetSign },
        animationSpec = tween(durationMillis = 300)
    )
    return enter togetherWith exit
}

fun ksuScreenTransition(
    forward: Boolean,
    isRtl: Boolean = false
): ContentTransform {
    val animationDirection = if (isRtl) -1 else 1
    val enter = if (forward) {
        slideInHorizontally(
            initialOffsetX = { it * animationDirection },
            animationSpec = tween(durationMillis = 300)
        )
    } else {
        slideInHorizontally(
            initialOffsetX = { -it * animationDirection },
            animationSpec = tween(durationMillis = 300)
        )
    }

    val exit = if (forward) {
        slideOutHorizontally(
            targetOffsetX = { -it * animationDirection },
            animationSpec = tween(durationMillis = 300)
        )
    } else {
        slideOutHorizontally(
            targetOffsetX = { it * animationDirection },
            animationSpec = tween(durationMillis = 300)
        )
    }

    return enter togetherWith exit
}

fun <T> Modifier.horizontalSwipeNavigator(
    currentDestination: T,
    destinations: List<T>,
    isRtl: Boolean,
    onNavigate: (T) -> Unit
): Modifier = pointerInput(currentDestination, isRtl) {
    var totalDrag = 0f

    detectHorizontalDragGestures(
        onDragStart = { totalDrag = 0f },
        onHorizontalDrag = { change, dragAmount ->
            change.consume()
            totalDrag += dragAmount
        },
        onDragEnd = {
            val threshold = 150f

            if (abs(totalDrag) > threshold) {
                val currentIndex = destinations.indexOf(currentDestination)
                if (currentIndex == -1) return@detectHorizontalDragGestures

                if (if (isRtl) totalDrag > 0 else totalDrag < 0) {
                    val next = (currentIndex + 1).coerceAtMost(destinations.lastIndex)
                    if (next != currentIndex) onNavigate(destinations[next])
                } else {
                    val prev = (currentIndex - 1).coerceAtLeast(0)
                    if (prev != currentIndex) onNavigate(destinations[prev])
                }
            }
        }
    )
}
