package ps.reso.instaeclipse.ui.theme

import android.graphics.Color as AndroidColor
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

object HsvColorUtils {

    fun hsvToColor(hue: Float, saturation: Float, value: Float, alpha: Int = 255): Int {
        val hsv = floatArrayOf(hue.coerceIn(0f, 360f), saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
        val rgb = AndroidColor.HSVToColor(hsv)
        return (alpha.coerceIn(0, 255) shl 24) or (0x00FFFFFF and rgb)
    }

    fun colorToHsv(color: Int): Triple<Float, Float, Float> {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(color, hsv)
        return Triple(hsv[0], hsv[1], hsv[2])
    }

    fun formatHex(color: Int): String {
        return String.format("#%06X", 0xFFFFFF and color)
    }

    fun parseHex(input: String): Int? {
        val clean = input.trim().removePrefix("#")
        return try {
            when (clean.length) {
                3 -> {
                    val r = clean.substring(0, 1).repeat(2).toInt(16)
                    val g = clean.substring(1, 2).repeat(2).toInt(16)
                    val b = clean.substring(2, 3).repeat(2).toInt(16)
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                6 -> {
                    (0xFF shl 24) or clean.toLong(16).toInt()
                }
                8 -> {
                    clean.toLong(16).toInt()
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isColorDark(color: Int): Boolean {
        val r = AndroidColor.red(color) / 255.0
        val g = AndroidColor.green(color) / 255.0
        val b = AndroidColor.blue(color) / 255.0
        val lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
        return lum < 0.5
    }
}

private val BASIC_PALETTE = listOf(
    0xFFFFFFFF.toInt(),
    0xFF78909C.toInt(),
    0xFF37474F.toInt(),
    0xFF000000.toInt(),
    0xFF8B0000.toInt(),
    0xFFC62828.toInt(),
    0xFFE91E63.toInt(),
    0xFF9C27B0.toInt(),
    0xFF1967D2.toInt(),
    0xFF0095F6.toInt(),
    0xFF00E676.toInt(),
    0xFFFFB300.toInt(),
    0xFFFF5722.toInt()
)

private val PALETTE_NAMES = listOf("Basic", "Material", "Pastel", "Neon")

private val MATERIAL_PALETTE = listOf(
    0xFFF44336.toInt(), 0xFFE91E63.toInt(), 0xFF9C27B0.toInt(),
    0xFF673AB7.toInt(), 0xFF3F51B5.toInt(), 0xFF2196F3.toInt(),
    0xFF03A9F4.toInt(), 0xFF00BCD4.toInt(), 0xFF009688.toInt(),
    0xFF4CAF50.toInt(), 0xFF8BC34A.toInt(), 0xFFCDDC39.toInt(),
    0xFFFFEB3B.toInt(), 0xFFFFC107.toInt(), 0xFFFF9800.toInt()
)

private val PASTEL_PALETTE = listOf(
    0xFFFFB3BA.toInt(), 0xFFFFDFBA.toInt(), 0xFFFFFFBA.toInt(),
    0xFFBAFFC9.toInt(), 0xFFBAE1FF.toInt(), 0xFFD4BAFF.toInt(),
    0xFFFFB3F7.toInt(), 0xFFF1F5F9.toInt(), 0xFFE2E8F0.toInt()
)

private val NEON_PALETTE = listOf(
    0xFF0EE936.toInt(),
    0xFF00F5FF.toInt(), 0xFF7000FF.toInt(), 0xFFFF007F.toInt(),
    0xFFFF5500.toInt(), 0xFFFFFF00.toInt(), 0xFF00FFCC.toInt()
)

@Composable
fun MaterialColorPickerDialog(
    title: String = "Color Picker",
    subtitle: String = "",
    initialColor: Int,
    onDismissRequest: () -> Unit,
    onColorConfirmed: (Int) -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismissRequest),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = Color(0xFF1E1E1E),
                tonalElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = false) {}
            ) {
                ImageToolboxColorPickerContent(
                    initialColor = initialColor,
                    onDismissRequest = onDismissRequest,
                    onColorConfirmed = onColorConfirmed
                )
            }
        }
    }
}

@Composable
fun ImageToolboxColorPickerContent(
    initialColor: Int,
    onDismissRequest: () -> Unit,
    onColorConfirmed: (Int) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current

    val initialHsv = remember(initialColor) { HsvColorUtils.colorToHsv(initialColor) }
    var hue by remember(initialColor) { mutableFloatStateOf(initialHsv.first) }
    var saturation by remember(initialColor) { mutableFloatStateOf(initialHsv.second) }
    var value by remember(initialColor) { mutableFloatStateOf(initialHsv.third) }
    var alpha by remember(initialColor) { mutableIntStateOf(AndroidColor.alpha(initialColor)) }

    var paletteIndex by remember { mutableIntStateOf(0) }
    val savedSwatches = remember { mutableStateListOf<Int>() }

    val activePalette = when (paletteIndex % 4) {
        0 -> BASIC_PALETTE
        1 -> MATERIAL_PALETTE
        2 -> PASTEL_PALETTE
        else -> NEON_PALETTE
    }
    val activePaletteName = PALETTE_NAMES[paletteIndex % 4]

    val currentColor = remember(hue, saturation, value, alpha) {
        HsvColorUtils.hsvToColor(hue, saturation, value, alpha)
    }

    var hexText by remember { mutableStateOf(HsvColorUtils.formatHex(currentColor)) }
    var isTypingHex by remember { mutableStateOf(false) }

    LaunchedEffect(currentColor) {
        if (!isTypingHex) {
            hexText = HsvColorUtils.formatHex(currentColor)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(36.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.25f))
        )

        Spacer(Modifier.height(14.dp))

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF262626),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { paletteIndex++ }
                        .padding(bottom = 12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF333333),
                        modifier = Modifier.size(30.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Palette,
                                contentDescription = "Palette",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = activePaletteName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }

                val allSwatches = if (savedSwatches.isNotEmpty()) savedSwatches + activePalette else activePalette
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    allSwatches.forEach { swatchColor ->
                        val isSelected = (0x00FFFFFF and swatchColor) == (0x00FFFFFF and currentColor)
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(swatchColor))
                                .border(
                                    width = if (isSelected) 2.5.dp else 1.dp,
                                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.2f),
                                    shape = CircleShape
                                )
                                .clickable {
                                    val (h, s, v) = HsvColorUtils.colorToHsv(swatchColor)
                                    hue = h
                                    saturation = s
                                    value = v
                                    alpha = AndroidColor.alpha(swatchColor)
                                }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(currentColor),
                modifier = Modifier
                    .size(56.dp)
                    .clickable {
                        hue = Random.nextFloat() * 360f
                        saturation = Random.nextFloat() * 0.45f + 0.55f
                        value = Random.nextFloat() * 0.40f + 0.60f
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val iconTint = if (HsvColorUtils.isColorDark(currentColor)) Color.White else Color.Black
                    Icon(
                        imageVector = Icons.Filled.Shuffle,
                        contentDescription = "Random Color",
                        tint = iconTint,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF262626),
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    BasicTextField(
                        value = hexText,
                        onValueChange = { input ->
                            val clean = if (input.startsWith("#")) input else "#$input"
                            hexText = clean
                            val parsed = HsvColorUtils.parseHex(clean)
                            if (parsed != null) {
                                val (h, s, v) = HsvColorUtils.colorToHsv(parsed)
                                hue = h
                                saturation = s
                                value = v
                            }
                        },
                        textStyle = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            isTypingHex = false
                            focusManager.clearFocus()
                        }),
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { state -> isTypingHex = state.isFocused }
                    )

                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(hexText))
                            Toast.makeText(context, "Copied $hexText", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "Copy Hex",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            val clipText = clipboardManager.getText()?.text?.trim() ?: ""
                            val parsed = HsvColorUtils.parseHex(clipText)
                            if (parsed != null) {
                                val (h, s, v) = HsvColorUtils.colorToHsv(parsed)
                                hue = h
                                saturation = s
                                value = v
                                Toast.makeText(context, "Pasted $clipText", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "No valid hex in clipboard", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentPaste,
                            contentDescription = "Paste Hex",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val ringRadius = 11.dp.toPx()
                                val usableW = size.width - 2 * ringRadius
                                val usableH = size.height - 2 * ringRadius
                                if (usableW > 0 && usableH > 0) {
                                    saturation = ((offset.x - ringRadius) / usableW).coerceIn(0f, 1f)
                                    value = 1f - ((offset.y - ringRadius) / usableH).coerceIn(0f, 1f)
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                val ringRadius = 11.dp.toPx()
                                val usableW = size.width - 2 * ringRadius
                                val usableH = size.height - 2 * ringRadius
                                if (usableW > 0 && usableH > 0) {
                                    saturation = ((change.position.x - ringRadius) / usableW).coerceIn(0f, 1f)
                                    value = 1f - ((change.position.y - ringRadius) / usableH).coerceIn(0f, 1f)
                                }
                            }
                        }
                ) {
                    val pureHueColor = Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                    drawRect(pureHueColor)

                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.White, Color.Transparent)
                        )
                    )

                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black)
                        )
                    )

                    val ringRadius = 11.dp.toPx()
                    val usableW = size.width - 2 * ringRadius
                    val usableH = size.height - 2 * ringRadius
                    val thumbX = ringRadius + saturation * usableW
                    val thumbY = ringRadius + (1f - value) * usableH

                    drawScallopedCircle(
                        center = Offset(thumbX, thumbY),
                        baseRadius = ringRadius,
                        amplitude = 2.dp.toPx(),
                        lobes = 12
                    )
                }
            }

            Box(
                modifier = Modifier
                    .width(28.dp)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val pillH = 12.dp.toPx()
                                val usableH = size.height - pillH
                                if (usableH > 0) {
                                    hue = (((offset.y - pillH / 2) / usableH).coerceIn(0f, 1f)) * 360f
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                val pillH = 12.dp.toPx()
                                val usableH = size.height - pillH
                                if (usableH > 0) {
                                    hue = (((change.position.y - pillH / 2) / usableH).coerceIn(0f, 1f)) * 360f
                                }
                            }
                        }
                ) {
                    val hueColors = listOf(
                        Color.Red,
                        Color.Yellow,
                        Color.Green,
                        Color.Cyan,
                        Color.Blue,
                        Color.Magenta,
                        Color.Red
                    )
                    drawRect(
                        brush = Brush.verticalGradient(hueColors)
                    )

                    val pillH = 12.dp.toPx()
                    val usableH = size.height - pillH
                    val thumbY = (hue / 360f).coerceIn(0f, 1f) * usableH

                    drawRoundRect(
                        color = Color(0x66000000),
                        topLeft = Offset(0f, thumbY - 0.5.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(size.width, pillH + 1.dp.toPx()),
                        cornerRadius = CornerRadius(pillH / 2, pillH / 2),
                        style = Stroke(width = 3.dp.toPx())
                    )
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(0f, thumbY),
                        size = androidx.compose.ui.geometry.Size(size.width, pillH),
                        cornerRadius = CornerRadius(pillH / 2, pillH / 2),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF333333),
                modifier = Modifier
                    .height(42.dp)
                    .clickable { paletteIndex++ }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Palette,
                        contentDescription = "Color",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Color",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF333333),
                    modifier = Modifier
                        .size(42.dp)
                        .clickable {
                            if (!savedSwatches.contains(currentColor)) {
                                savedSwatches.add(0, currentColor)
                                Toast.makeText(context, "Saved to swatches", Toast.LENGTH_SHORT).show()
                            }
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Bookmark,
                            contentDescription = "Bookmark",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color.White,
                    modifier = Modifier
                        .height(42.dp)
                        .clickable { onColorConfirmed(currentColor) }
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    ) {
                        Text(
                            text = "OK",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawScallopedCircle(
    center: Offset,
    baseRadius: Float,
    amplitude: Float,
    lobes: Int
) {
    val path = Path()
    val steps = 72
    for (i in 0..steps) {
        val angle = (i.toFloat() / steps) * 2f * PI.toFloat()
        val r = baseRadius + amplitude * cos(lobes * angle)
        val px = center.x + r * cos(angle)
        val py = center.y + r * sin(angle)
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()

    drawPath(
        path = path,
        color = Color(0x88000000),
        style = Stroke(width = 3.5.dp.toPx())
    )
    drawPath(
        path = path,
        color = Color.White,
        style = Stroke(width = 2.dp.toPx())
    )
}
