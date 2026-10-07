package com.tsetingdms.glasscamera.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

val Accent = Color(0xFFFFD60A)

/**
 * The "liquid glass" surface (same recipe as the Lumo launcher): a dark translucent base so it
 * reads over bright scenes, a diagonal light sheen, and a rim that is brighter along the top edge.
 * No real-time blur: too expensive over a live camera feed on a budget GPU.
 */
fun Modifier.glass(shape: Shape, selected: Boolean = false): Modifier = this
    .clip(shape)
    .background(if (selected) Color.White.copy(alpha = 0.92f) else Color.Black.copy(alpha = 0.30f), shape)
    .background(
        Brush.linearGradient(
            listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.10f))
        ),
        shape,
    )
    .border(
        1.dp,
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.60f), Color.White.copy(alpha = 0.08f))),
        shape,
    )

/**
 * Press feedback like the launcher's tiles: the pressed side sinks into the screen in 3D, the
 * button shrinks a little, then springs back. [label] is read by TalkBack.
 */
fun Modifier.tiltPress(label: String, enabled: Boolean = true, maxDegrees: Float = 14f, onClick: () -> Unit): Modifier = composed {
    val rx = remember { Animatable(0f) }
    val ry = remember { Animatable(0f) }
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onClick)
    this
        .semantics {
            contentDescription = label
            role = Role.Button
            onClick(label) {
                if (enabled) latest()
                enabled
            }
        }
        .graphicsLayer {
            rotationX = rx.value
            rotationY = ry.value
            scaleX = scale.value
            scaleY = scale.value
            cameraDistance = 10f * density
        }
        .pointerInput(enabled) {
            detectTapGestures(
                onPress = { pos ->
                    if (enabled) {
                        val nx = (pos.x / size.width - 0.5f) * 2f
                        val ny = (pos.y / size.height - 0.5f) * 2f
                        scope.launch { ry.animateTo(nx * maxDegrees, spring(stiffness = 900f)) }
                        scope.launch { rx.animateTo(-ny * maxDegrees, spring(stiffness = 900f)) }
                        scope.launch { scale.animateTo(0.92f, spring(stiffness = 900f)) }
                    }
                    tryAwaitRelease()
                    scope.launch { ry.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 320f)) }
                    scope.launch { rx.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 320f)) }
                    scope.launch { scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 320f)) }
                },
                onTap = { if (enabled) latest() },
            )
        }
}

@Composable
fun Label(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    size: TextUnit = 13.sp,
    weight: FontWeight = FontWeight.Medium,
) {
    BasicText(text = text, modifier = modifier, style = TextStyle(color = color, fontSize = size, fontWeight = weight))
}

/** Icon button for the top bar: no own glass (it sits inside a glass capsule). */
@Composable
fun BarIcon(icon: ImageVector, label: String, rotation: Float, active: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .tiltPress(label, maxDegrees = 18f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            imageVector = icon,
            contentDescription = null,
            colorFilter = ColorFilter.tint(if (active) Accent else Color.White),
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer { rotationZ = rotation },
        )
    }
}

/** Round glass button with an icon. */
@Composable
fun GlassCircleButton(
    icon: ImageVector,
    label: String,
    size: Dp,
    rotation: Float,
    modifier: Modifier = Modifier,
    iconFlip: Float = 0f,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            .tiltPress(label, onClick = onClick)
            .glass(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            imageVector = icon,
            contentDescription = null,
            colorFilter = ColorFilter.tint(Color.White),
            modifier = Modifier
                .size(size * 0.45f)
                .graphicsLayer {
                    rotationZ = rotation
                    rotationY = iconFlip
                },
        )
    }
}

/** A selectable chip: glass when off, bright when selected. */
@Composable
fun Chip(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .tiltPress(text, enabled = enabled, maxDegrees = 10f, onClick = onClick)
            .glass(RoundedCornerShape(50), selected = selected)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(
            text = text,
            color = when {
                selected -> Color.Black
                enabled -> Color.White
                else -> Color.White.copy(alpha = 0.4f)
            },
        )
    }
}

@Composable
fun GlassSwitch(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    val track by animateColorAsState(if (checked) Accent else Color.White.copy(alpha = 0.18f), label = "track")
    val knob by animateDpAsState(if (checked) 20.dp else 2.dp, label = "knob")
    Box(
        modifier = Modifier
            .size(width = 46.dp, height = 28.dp)
            .tiltPress(label, maxDegrees = 8f) { onChange(!checked) }
            .clip(RoundedCornerShape(50))
            .background(track)
            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(50)),
    ) {
        Box(
            modifier = Modifier
                .offset(x = knob, y = 2.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}
