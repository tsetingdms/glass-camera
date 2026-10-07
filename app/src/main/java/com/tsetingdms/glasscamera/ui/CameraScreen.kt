package com.tsetingdms.glasscamera.ui

import android.app.Activity
import android.hardware.camera2.CaptureRequest
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.FlashAuto
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.GridOff
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.HdrOff
import androidx.compose.material.icons.rounded.HdrOn
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Timer10
import androidx.compose.material.icons.rounded.Timer3
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.tsetingdms.glasscamera.camera.CameraController
import com.tsetingdms.glasscamera.camera.FlashMode
import com.tsetingdms.glasscamera.camera.Mode
import com.tsetingdms.glasscamera.camera.ProSetting
import com.tsetingdms.glasscamera.camera.Status
import com.tsetingdms.glasscamera.camera.evLabel
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun CameraScreen(c: CameraController) {
    val activity = LocalContext.current as Activity
    // Front "flash": full brightness while the white screen is up.
    LaunchedEffect(c.screenFlash) {
        val params = activity.window.attributes
        params.screenBrightness = if (c.screenFlash) 1f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        activity.window.attributes = params
    }
    val rotation by animateFloatAsState(c.uiRotation, tween(320), label = "rotation")

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.statusBarsPadding())
            TopBar(c, rotation)
            Viewfinder(c, rotation)
            Spacer(Modifier.weight(1f))
            ModeSwitcher(c)
            Spacer(Modifier.height(18.dp))
            BottomBar(c, rotation)
            Spacer(Modifier.navigationBarsPadding().height(18.dp))
        }
        if (c.screenFlash) Box(Modifier.fillMaxSize().background(Color(0xFFFFF6EC)))
        c.status?.let { StatusCard(it, rotation, Modifier.align(Alignment.Center)) }
        Toast(c, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 190.dp))
        if (c.settingsOpen) SettingsSheet(c)
    }
}

// region Top bar

@Composable
private fun TopBar(c: CameraController, rotation: Float) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Row(
            modifier = Modifier.glass(RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (c.front) {
                BarIcon(Icons.Rounded.LightMode, if (c.screenLight) "Screen light on" else "Screen light off", rotation, active = c.screenLight) { c.cycleFlash() }
            } else {
                val (icon, text) = when (c.flash) {
                    FlashMode.OFF -> Icons.Rounded.FlashOff to "Flash off"
                    FlashMode.AUTO -> Icons.Rounded.FlashAuto to "Flash auto"
                    FlashMode.ON -> Icons.Rounded.FlashOn to "Flash on"
                }
                BarIcon(icon, text, rotation, active = c.flash != FlashMode.OFF) { c.cycleFlash() }
            }
            if (c.mode == Mode.PHOTO) {
                BarIcon(if (c.hdr) Icons.Rounded.HdrOn else Icons.Rounded.HdrOff, if (c.hdr) "HDR on" else "HDR off", rotation, active = c.hdr) { c.toggleHdr() }
            }
            val timerIcon = when (c.timer) {
                3 -> Icons.Rounded.Timer3
                10 -> Icons.Rounded.Timer10
                else -> Icons.Rounded.TimerOff
            }
            BarIcon(timerIcon, if (c.timer == 0) "Timer off" else "Timer ${c.timer} seconds", rotation, active = c.timer > 0) { c.cycleTimer() }
            BarIcon(if (c.grid) Icons.Rounded.GridOn else Icons.Rounded.GridOff, if (c.grid) "Grid on" else "Grid off", rotation, active = c.grid) { c.toggleGrid() }
            BarIcon(Icons.Rounded.Settings, "Settings", rotation) { c.settingsOpen = true }
        }
    }
}

// endregion

// region Viewfinder

@Composable
private fun Viewfinder(c: CameraController, rotation: Float) {
    var focus by remember { mutableStateOf<Offset?>(null) }
    var focusKey by remember { mutableIntStateOf(0) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(22.dp)),
    ) {
        AndroidView(
            factory = { context ->
                PreviewView(context).apply {
                    // TextureView: clips to the rounded corners and composes under the glass controls.
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    c.attach(this)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        focus = offset
                        focusKey++
                        c.focusAt(offset.x, offset.y)
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoomChange, _ ->
                        if (zoomChange != 1f) c.zoomTo(c.zoom * zoomChange)
                    }
                },
        )
        if (c.grid) GridLines()
        focus?.let { FocusRing(it, focusKey) }
        if (c.mode == Mode.PRO) {
            ProPanel(c, Modifier.align(Alignment.BottomCenter).padding(10.dp))
        } else {
            ZoomChips(c, rotation, Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp))
        }
        if (c.countdown > 0) {
            Label(
                text = c.countdown.toString(),
                size = 96.sp,
                weight = FontWeight.Light,
                modifier = Modifier.align(Alignment.Center).graphicsLayer { rotationZ = rotation },
            )
        }
    }
}

@Composable
private fun GridLines() {
    Canvas(Modifier.fillMaxSize()) {
        val line = Color.White.copy(alpha = 0.35f)
        for (i in 1..2) {
            val x = size.width * i / 3f
            val y = size.height * i / 3f
            drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
    }
}

@Composable
private fun FocusRing(at: Offset, key: Int) {
    val scale = remember(key) { Animatable(1.5f) }
    val alpha = remember(key) { Animatable(1f) }
    LaunchedEffect(key) {
        scale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 400f))
        delay(900)
        alpha.animateTo(0f, tween(400))
    }
    val sizeDp = 72.dp
    val px = with(LocalDensity.current) { sizeDp.toPx() }
    Box(
        modifier = Modifier
            .offset { IntOffset((at.x - px / 2).roundToInt(), (at.y - px / 2).roundToInt()) }
            .size(sizeDp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
            }
            .border(1.5.dp, Accent, CircleShape),
    )
}

@Composable
private fun ZoomChips(c: CameraController, rotation: Float, modifier: Modifier) {
    val presets = listOf(1f, 2f, 4f).filter { it <= c.maxZoom + 0.01f }
    if (presets.size < 2) return
    Row(
        modifier = modifier.glass(RoundedCornerShape(50)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        presets.forEach { value ->
            // The chip below the current (pinched) zoom shows the exact value, like the stock camera.
            val active = presets.lastOrNull { it <= c.zoom + 0.05f } ?: presets.first()
            val selected = value == active
            val showing = if (selected) formatZoom(c.zoom) else formatZoom(value)
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .tiltPress("Zoom ${formatZoom(value)}", maxDegrees = 16f) { c.zoomTo(value) }
                    .clip(CircleShape)
                    .background(if (selected) Color.White.copy(alpha = 0.92f) else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Label(
                    text = showing,
                    color = if (selected) Color.Black else Color.White,
                    size = 12.sp,
                    weight = FontWeight.SemiBold,
                    modifier = Modifier.graphicsLayer { rotationZ = rotation },
                )
            }
        }
    }
}

private fun formatZoom(value: Float): String {
    val rounded = (value * 10).roundToInt() / 10f
    return if (rounded % 1f == 0f) "${rounded.toInt()}×" else "$rounded×"
}

// endregion

// region Pro

@Composable
private fun ProPanel(c: CameraController, modifier: Modifier) {
    val caps = c.caps
    Column(
        modifier = modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ProSetting.entries.forEach { s ->
                Chip(s.label, selected = c.proSetting == s) { c.proSetting = s }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (caps == null) return@Row
            when (c.proSetting) {
                ProSetting.EV -> {
                    if (caps.evRange.upper == caps.evRange.lower) {
                        Unavailable("Exposure can't be changed on this camera")
                    } else {
                        val stride = maxOf(1, ((1f / 3f) / caps.evStep).roundToInt())
                        val steps = (caps.evRange.lower..caps.evRange.upper).filter { it % stride == 0 }
                        steps.forEach { i -> Chip(evLabel(i, caps.evStep), selected = c.proEv == i) { c.chooseEv(i) } }
                    }
                }
                ProSetting.WB -> {
                    val all = listOf(
                        "Auto" to CaptureRequest.CONTROL_AWB_MODE_AUTO,
                        "Daylight" to CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
                        "Cloudy" to CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
                        "Shade" to CaptureRequest.CONTROL_AWB_MODE_SHADE,
                        "Tungsten" to CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
                        "Fluorescent" to CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT,
                    ).filter { it.second == CaptureRequest.CONTROL_AWB_MODE_AUTO || it.second in caps.awbModes }
                    all.forEach { (name, value) -> Chip(name, selected = c.proWb == value) { c.chooseWb(value) } }
                }
                ProSetting.ISO -> {
                    val range = caps.isoRange
                    if (!caps.manualSensor || range == null) {
                        Unavailable("ISO isn't adjustable on this camera (${caps.level})")
                    } else {
                        Chip("Auto", selected = c.proIso == null) { c.chooseIso(null) }
                        listOf(50, 100, 200, 400, 800, 1600, 3200, 6400)
                            .filter { it in range.lower..range.upper }
                            .forEach { iso -> Chip("$iso", selected = c.proIso == iso) { c.chooseIso(iso) } }
                    }
                }
                ProSetting.SHUTTER -> {
                    val range = caps.exposureRange
                    if (!caps.manualSensor || range == null) {
                        Unavailable("Shutter speed isn't adjustable on this camera (${caps.level})")
                    } else {
                        Chip("Auto", selected = c.proShutter == null) { c.chooseShutter(null) }
                        listOf(2000, 1000, 500, 250, 125, 60, 30, 15, 8, 4, 2)
                            .map { 1_000_000_000L / it }
                            .filter { it in range.lower..range.upper }
                            .forEach { ns ->
                                Chip("1/${1_000_000_000L / ns}", selected = c.proShutter == ns) { c.chooseShutter(ns) }
                            }
                    }
                }
                ProSetting.FOCUS -> {
                    if (!caps.manualFocus) {
                        Unavailable(if (caps.minFocus == 0f) "This camera has fixed focus" else "Manual focus isn't available on this camera")
                    } else {
                        Chip("Auto", selected = c.proFocus == null) { c.chooseFocus(null) }
                        listOf("∞" to 0f, "3 m" to 0.33f, "1 m" to 1f, "50 cm" to 2f, "25 cm" to 4f, "10 cm" to 10f)
                            .filter { it.second <= caps.minFocus }
                            .forEach { (name, d) -> Chip(name, selected = c.proFocus == d) { c.chooseFocus(d) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun Unavailable(text: String) {
    Label(text, color = Color.White.copy(alpha = 0.7f), size = 12.sp, modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp))
}

// endregion

// region Bottom

@Composable
private fun ModeSwitcher(c: CameraController) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Row(
            modifier = Modifier.glass(RoundedCornerShape(50)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Mode.entries.forEach { m ->
                val selected = m == c.mode
                Box(
                    modifier = Modifier
                        .tiltPress(m.label, maxDegrees = 10f) { c.selectMode(m) }
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) Color.White.copy(alpha = 0.92f) else Color.Transparent)
                        .padding(horizontal = 15.dp, vertical = 9.dp),
                ) {
                    Label(m.label, color = if (selected) Color.Black else Color.White, size = 14.sp, weight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun BottomBar(c: CameraController, rotation: Float) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 34.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(c, rotation)
        Shutter(c, rotation)
        val flip by animateFloatAsState(if (c.front) 180f else 0f, spring(dampingRatio = 0.6f, stiffness = 300f), label = "flip")
        GlassCircleButton(Icons.Rounded.Cameraswitch, "Switch camera", 58.dp, rotation, iconFlip = flip) { c.toggleFront() }
    }
}

@Composable
private fun Thumbnail(c: CameraController, rotation: Float) {
    Box(
        modifier = Modifier
            .size(58.dp)
            .tiltPress("Open last photo") { c.openGallery() }
            .glass(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val thumb = c.thumbnail
        if (thumb != null) {
            Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(50.dp).clip(CircleShape).graphicsLayer { rotationZ = rotation },
            )
        } else {
            Image(
                imageVector = Icons.Rounded.Photo,
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.7f)),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun Shutter(c: CameraController, rotation: Float) {
    val progress = c.status?.progress
    Box(
        modifier = Modifier
            .size(84.dp)
            .tiltPress("Take photo", enabled = !c.busy, maxDegrees = 12f) { c.shutter() }
            .glass(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val inner = if (c.busy) Color.White.copy(alpha = 0.55f) else Color.White
        Box(Modifier.size(64.dp).clip(CircleShape).background(inner))
        val icon = when (c.mode) {
            Mode.NIGHT -> Icons.Rounded.Bedtime
            Mode.PORTRAIT -> Icons.Rounded.Person
            else -> null
        }
        if (icon != null) {
            Image(
                imageVector = icon,
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color(0xFF1C1C1E)),
                modifier = Modifier.size(26.dp).graphicsLayer { rotationZ = rotation },
            )
        }
        if (progress != null) {
            Canvas(Modifier.fillMaxSize().padding(3.dp)) {
                drawArc(
                    color = Accent,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                    size = Size(size.width, size.height),
                )
            }
        }
    }
}

// endregion

// region Overlays

@Composable
private fun StatusCard(status: Status, rotation: Float, modifier: Modifier) {
    Column(
        modifier = modifier
            .graphicsLayer { rotationZ = rotation }
            .glass(RoundedCornerShape(24.dp))
            .padding(horizontal = 26.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 5.dp.toPx()
                drawArc(Color.White.copy(alpha = 0.18f), 0f, 360f, false, style = Stroke(stroke))
                drawArc(Accent, -90f, 360f * status.progress.coerceIn(0.02f, 1f), false, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Label("${(status.progress * 100).roundToInt()}%", size = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        Label(status.text, size = 15.sp, weight = FontWeight.SemiBold)
    }
}

@Composable
private fun Toast(c: CameraController, modifier: Modifier) {
    val text = c.message ?: return
    LaunchedEffect(text) {
        delay(2600)
        if (c.message == text) c.message = null
    }
    Box(modifier.padding(horizontal = 24.dp).glass(RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 10.dp)) {
        Label(text, size = 13.sp)
    }
}

@Composable
private fun SettingsSheet(c: CameraController) {
    BackHandler { c.settingsOpen = false }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) { detectTapGestures { c.settingsOpen = false } },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(12.dp)
                .pointerInput(Unit) { detectTapGestures { } }
                .glass(RoundedCornerShape(28.dp))
                .background(Color(0xCC14141C))
                .padding(20.dp),
        ) {
            Label("Settings", size = 20.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))
            SettingRow("Mirror front photos", "Save selfies the way the viewfinder shows them", c.mirrorFront) { c.changeMirrorFront(it) }
            SettingRow("Clean selfies", "Front camera takes 4 quick shots and merges them for less grain", c.cleanSelfies) { c.changeCleanSelfies(it) }
            SettingRow("Shutter sound", null, c.shutterSound) { c.changeShutterSound(it) }
            Spacer(Modifier.height(10.dp))
            val caps = c.caps
            if (caps != null) {
                Label("This camera", color = Color.White.copy(alpha = 0.6f), size = 12.sp)
                Spacer(Modifier.height(4.dp))
                Label(
                    "Camera2 level: ${caps.level} · Manual ISO/shutter: ${if (caps.manualSensor) "yes" else "no"} · " +
                        "Focus: ${if (caps.minFocus > 0f) "auto" else "fixed"} · Flash: ${if (caps.hasFlash) "yes" else "no"}",
                    color = Color.White.copy(alpha = 0.85f),
                    size = 12.sp,
                )
                Spacer(Modifier.height(10.dp))
            }
            Label(
                "Tips: volume buttons take photos · pinch to zoom · tap to focus. Photos are saved to DCIM/Glass Camera and never leave your phone.",
                color = Color.White.copy(alpha = 0.6f),
                size = 12.sp,
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Chip("Done", selected = true) { c.settingsOpen = false }
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Label(title, size = 15.sp)
            if (hint != null) Label(hint, color = Color.White.copy(alpha = 0.6f), size = 12.sp, weight = FontWeight.Normal)
        }
        Spacer(Modifier.width(12.dp))
        GlassSwitch(checked, title, onChange)
    }
}

@Composable
fun PermissionScreen(onAllow: () -> Unit, onSettings: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0B1026)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(28.dp).glass(RoundedCornerShape(28.dp)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Label("Glass Camera needs your camera", size = 18.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Label(
                "Photos are processed and saved only on this phone.",
                color = Color.White.copy(alpha = 0.7f),
                size = 13.sp,
                weight = FontWeight.Normal,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("Allow camera", selected = true, onClick = onAllow)
                Chip("Open settings", selected = false, onClick = onSettings)
            }
        }
    }
}

// endregion
