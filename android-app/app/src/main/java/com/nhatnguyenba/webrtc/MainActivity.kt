package com.nhatnguyenba.webrtc

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import org.webrtc.SurfaceViewRenderer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VideoCallApp()
        }
    }
}

@Composable
fun VideoCallApp() {
    val viewModel: CallViewModel = viewModel()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        viewModel.hasCameraPermission.value =
            permissions[android.Manifest.permission.CAMERA] ?: false
        viewModel.hasAudioPermission.value =
            permissions[android.Manifest.permission.RECORD_AUDIO] ?: false
    }

    LaunchedEffect(Unit) {
        viewModel.checkPermissions(context)
    }

    LaunchedEffect(viewModel.shouldShowPermissionDialog.value) {
        if (viewModel.shouldShowPermissionDialog.value) {
            permissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.CAMERA,
                    android.Manifest.permission.RECORD_AUDIO
                )
            )
        } else {
            viewModel.initWebRTC(context)
            viewModel.setupSocket()
        }
    }

    when {
        viewModel.shouldShowPermissionDialog.value -> {
            PermissionExplanationScreen {
                permissionLauncher.launch(
                    arrayOf(
                        android.Manifest.permission.CAMERA,
                        android.Manifest.permission.RECORD_AUDIO
                    )
                )
            }
        }

        !viewModel.hasCameraPermission.value || !viewModel.hasAudioPermission.value -> {
            PermissionDeniedScreen()
        }

        else -> {
            MainContent(viewModel)
        }
    }
}

@Composable
fun MainContent(viewModel: CallViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!viewModel.isJoined.value) {
            JoinForm(viewModel)
        } else {
            VideoCallUI(viewModel)
        }
    }
}

@Composable
fun PermissionExplanationScreen(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Cần quyền truy cập",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Text(
            text = "Ứng dụng cần quyền sử dụng camera và microphone để thực hiện cuộc gọi video",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        Button(
            onClick = onRequestPermission,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cấp quyền")
        }
    }
}

@Composable
fun PermissionDeniedScreen() {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Quyền bị từ chối",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Text(
            text = "Bạn cần cấp quyền camera và microphone trong cài đặt để sử dụng ứng dụng",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        Button(
            onClick = {
                // Mở cài đặt ứng dụng
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Mở cài đặt")
        }
    }
}

@Composable
fun JoinForm(viewModel: CallViewModel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        TextField(
            value = viewModel.roomId.value,
            onValueChange = { viewModel.roomId.value = it },
            label = { Text("Room ID") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextField(
            value = viewModel.userName.value,
            onValueChange = { viewModel.userName.value = it },
            label = { Text("Your Name") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = {
            viewModel.joinRoom()
        }) {
            Text("Join Room")
        }
    }
}

@Composable
fun VideoCallUI(viewModel: CallViewModel) {
    var localView by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var remoteView by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        // Remote video
        if (viewModel.remoteStreamAvailable.value) {
            AndroidView(
                factory = { context ->
                    SurfaceViewRenderer(context).apply {
                        init(viewModel.eglBase?.eglBaseContext, null)
                        setEnableHardwareScaler(true)
                        setZOrderMediaOverlay(false)
                        remoteView = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Local video (small overlay)
        AndroidView(
            factory = { context ->
                SurfaceViewRenderer(context).apply {
                    init(viewModel.eglBase?.eglBaseContext, null)
                    setEnableHardwareScaler(true)
                    setZOrderMediaOverlay(true)
                    setMirror(true)
                    localView = this
                    viewModel.startLocalVideoCapture(this)
                }
            },
            modifier = Modifier
                .width(120.dp)
                .height(160.dp)
                .align(Alignment.TopEnd)
        )

        // Controls
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IconButton(
                onClick = { viewModel.toggleCamera() },
                modifier = Modifier.size(64.dp)
            ) {
                Icon(
                    if (viewModel.isCameraOn.value) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                    contentDescription = "Toggle Camera"
                )
            }

            IconButton(
                onClick = { viewModel.leaveRoom() },
                modifier = Modifier.size(64.dp)
            ) {
                Icon(Icons.Filled.CallEnd, "End Call", tint = Color.Red)
            }

            IconButton(
                onClick = { viewModel.toggleMic() },
                modifier = Modifier.size(64.dp)
            ) {
                Icon(
                    if (viewModel.isMicOn.value) Icons.Filled.Mic else Icons.Filled.MicOff,
                    contentDescription = "Toggle Mic"
                )
            }
        }
    }
}