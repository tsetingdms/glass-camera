package com.tsetingdms.glasscamera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.tsetingdms.glasscamera.camera.CameraController
import com.tsetingdms.glasscamera.ui.CameraScreen
import com.tsetingdms.glasscamera.ui.PermissionScreen

class MainActivity : ComponentActivity() {
    private lateinit var controller: CameraController
    private lateinit var orientation: OrientationEventListener
    private var granted by mutableStateOf(false)

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { controller.onMicResult(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        controller = CameraController(this)
        controller.requestMic = { requestMic.launch(Manifest.permission.RECORD_AUDIO) }
        granted = hasCamera()
        // The screen stays portrait; icons turn and photos are saved upright from the phone's real orientation.
        orientation = object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees != ORIENTATION_UNKNOWN) controller.onDeviceOrientation(degrees)
            }
        }

        setContent {
            if (granted) {
                CameraScreen(controller)
            } else {
                PermissionScreen(
                    onAllow = { requestCamera.launch(Manifest.permission.CAMERA) },
                    onSettings = {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    },
                )
            }
        }
        if (!granted) requestCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        if (!granted && hasCamera()) granted = true
        if (orientation.canDetectOrientation()) orientation.enable()
    }

    override fun onPause() {
        orientation.disable()
        super.onPause()
    }

    override fun onDestroy() {
        controller.release()
        super.onDestroy()
    }

    // Volume buttons work as a shutter, like most camera apps.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (granted && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_CAMERA)) {
            if (event.repeatCount == 0) controller.shutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}
