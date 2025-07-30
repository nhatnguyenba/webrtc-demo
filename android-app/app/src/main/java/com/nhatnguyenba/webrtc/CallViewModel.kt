package com.nhatnguyenba.webrtc

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.IceCandidateErrorEvent
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.IceServer
import org.webrtc.PeerConnection.RTCConfiguration
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.net.URISyntaxException


class CallViewModel : ViewModel() {
    private var audioSource: AudioSource? = null
    private var videoSource: VideoSource? = null
    private var socket: Socket? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private val remoteAudioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null
    var remoteView: VideoSink? = null
    var eglBase: EglBase? = null

    val roomId = mutableStateOf("")
    val userName = mutableStateOf("")
    val isJoined = mutableStateOf(false)
    val isCameraOn = mutableStateOf(true)
    val isMicOn = mutableStateOf(true)
    val remoteStreamAvailable = mutableStateOf(false)

    val hasCameraPermission = mutableStateOf(false)
    val hasAudioPermission = mutableStateOf(false)
    val shouldShowPermissionDialog = mutableStateOf(false)

    fun checkPermissions(context: Context) {
        hasCameraPermission.value = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        hasAudioPermission.value = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        shouldShowPermissionDialog.value = !(hasCameraPermission.value && hasAudioPermission.value)
    }

    fun initWebRTC(context: Context) {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                .createInitializationOptions()
        )

        eglBase = EglBase.create()
        peerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    fun setupSocket() {
        try {
            Log.d("NHAT", "setUpSocket")
            socket = IO.socket("http://10.0.2.2:3000")
            socket?.on(Socket.EVENT_CONNECT) {
                Log.d("Socket", "Connected")
                Log.d("NHAT", "Socket Connected")
            }?.on("room_users") { args ->
                viewModelScope.launch {
                    if (args[0] != null) {
                        val users = args[0] as JSONArray
                        Log.d("NHAT", "Users list: $users")
                        if (peerConnection == null) createPeerConnection()
                        if (users.length() > 0) createOffer()
                    }
                }
            }?.on("getOffer") { args ->
                viewModelScope.launch {
                    val offerJson = args[0] as JSONObject
                    Log.d("NHAT", "getOffer: $offerJson")
                    val sdp = SessionDescription(
                        SessionDescription.Type.OFFER,
                        offerJson.getString("sdp")
                    )
                    handleOffer(sdp)
                }
            }?.on("getAnswer") { args ->
                viewModelScope.launch {
                    val answerJson = args[0] as JSONObject
                    Log.d("NHAT", "getAnswer: $answerJson")
                    val sdp = SessionDescription(
                        SessionDescription.Type.ANSWER,
                        answerJson.getString("sdp")
                    )
                    handleAnswer(sdp)
                }
            }?.on("getCandidate") { args ->
                viewModelScope.launch {
                    val candidateJson = args[0] as JSONObject
                    Log.d("NHAT", "getCandidate: $candidateJson")
                    val iceCandidate = IceCandidate(
                        candidateJson.getString("id"),
                        candidateJson.getInt("label"),
                        candidateJson.getString("candidate")
                    )
                    addIceCandidate(iceCandidate)
                }
            }?.on("user_exit") { args ->
                viewModelScope.launch {
                    val userExitJson = args[0] as JSONObject
                    val userId = userExitJson.getString("id")
                    Log.d("UserExit", "User $userId exited")
                    peerConnection?.close()
                    peerConnection = null
                    remoteVideoTrack = null
                    remoteStreamAvailable.value = false
                }
            }
            Log.d("NHAT", "setUpSocket socket=$socket")
            socket?.connect()
        } catch (e: URISyntaxException) {
            e.printStackTrace()
        }
    }

    fun joinRoom() {
        if (roomId.value.isEmpty() || userName.value.isEmpty()) return

        socket?.emit("join", JSONObject().apply {
            put("room", roomId.value)
            put("name", userName.value)
        })
        isJoined.value = true
    }

    fun leaveRoom() {
        socket?.disconnect()
        peerConnection?.close()
        peerConnection = null
        videoCapturer?.stopCapture()
        videoCapturer = null
        localVideoTrack = null
        remoteVideoTrack = null
        isJoined.value = false
        remoteStreamAvailable.value = false
    }

    fun toggleCamera() {
        if (!hasCameraPermission.value) {
            Log.e("Permission", "Camera permission not granted")
            return
        }

        isCameraOn.value = !isCameraOn.value
        localVideoTrack?.setEnabled(isCameraOn.value)
    }

    fun toggleMic() {
        if (!hasAudioPermission.value) {
            Log.e("Permission", "Microphone permission not granted")
            return
        }

        isMicOn.value = !isMicOn.value
    }

    private fun createPeerConnection() {
        Log.d("NHAT", "createPeerConnection")
        val rtcConfig = RTCConfiguration(ArrayList())
        rtcConfig.iceServers.add(
            IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        rtcConfig.iceServers.add(
            IceServer.builder("turn:a.relay.metered.ca:443?transport=tcp")
                .setUsername("83eebabf8b4cce9d5dbcb649")
                .setPassword("2D7JvfkOQtBdYW3R").createIceServer()
        )
        rtcConfig.iceServers.add(
            IceServer.builder("turn:relay1.expressturn.com:3480?transport=tcp")
                .setUsername("000000002068945177")
                .setPassword("xm2eE12fIB3S8kAe+FF7jeZE+M8=").createIceServer()
        )
        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnectionObserver() {
                override fun onIceCandidate(iceCandidate: IceCandidate?) {
                    super.onIceCandidate(iceCandidate)
                    iceCandidate?.let {
                        val candidateJson = JSONObject().apply {
                            put("id", it.sdpMid)
                            put("label", it.sdpMLineIndex)
                            put("candidate", it.sdp)
                        }
                        socket?.emit("candidate", candidateJson)
                    }
                }

                override fun onAddStream(mediaStream: MediaStream?) {
                    super.onAddStream(mediaStream)
                    Log.d("NHAT", "onAddStream 1: $mediaStream")
                    mediaStream?.videoTracks?.firstOrNull()?.let {
                        Log.d("NHAT", "onAddStream 2: $it")
                        remoteVideoTrack = it
                        remoteVideoTrack?.setEnabled(true)
                        remoteView?.let { view -> remoteVideoTrack?.addSink(view) }
                        remoteStreamAvailable.value = true
                    }
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                    super.onConnectionChange(newState)
                    Log.d("NHAT", "onConnectionChange: $newState")
                    if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                        Log.d("NHAT", "onConnectionChange: peerConnection=$peerConnection")
                        Log.d("NHAT", "onConnectionChange: localVideoTrack=$localVideoTrack")
                    }
                }

                override fun onSignalingChange(signalingState: PeerConnection.SignalingState?) {
                    super.onSignalingChange(signalingState)
                    Log.d("NHAT", "onSignalingChange: $signalingState")
                }

                override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState?) {
                    super.onIceConnectionChange(iceConnectionState)
                    Log.d("NHAT", "onIceConnectionChange: $iceConnectionState")
                }

                override fun onIceCandidateError(event: IceCandidateErrorEvent?) {
                    super.onIceCandidateError(event)
                    Log.d("NHAT", "onIceCandidateError: ${event?.errorText}")
                }

                override fun onTrack(transceiver: RtpTransceiver?) {
                    super.onTrack(transceiver)
                    // có thể lấy data từ remote ở đây và set vào SurfaceViewRenderer
                    Log.d("NHAT", "onTrack sender: ${transceiver?.sender?.streams}")
//                    Log.d("NHAT", "onTrack receiver: ${transceiver?.receiver?.}")
                }
            })
    }

    fun startLocalVideoCapture(surfaceViewRenderer: SurfaceViewRenderer) {
        if (!hasCameraPermission.value) {
            Log.e("Permission", "Camera permission not granted")
            return
        }

        try {
//            surfaceViewRenderer.init(eglBase?.eglBaseContext, null)

            videoCapturer = createCameraCapturer(surfaceViewRenderer.context)
            videoCapturer?.let { capturer ->
                val surfaceTextureHelper =
                    SurfaceTextureHelper.create("CaptureThread", eglBase?.eglBaseContext)
                videoSource = peerConnectionFactory?.createVideoSource(capturer.isScreencast)
                audioSource = peerConnectionFactory?.createAudioSource(MediaConstraints())
                capturer.initialize(
                    surfaceTextureHelper,
                    surfaceViewRenderer.context,
                    videoSource?.capturerObserver
                )
                capturer.startCapture(1280, 720, 30)

                localVideoTrack =
                    peerConnectionFactory?.createVideoTrack("local_video_track", videoSource)
                localAudioTrack = peerConnectionFactory?.createAudioTrack("local_audio_track", audioSource)
                localVideoTrack?.addSink(surfaceViewRenderer)
                if (peerConnection == null) createPeerConnection()
                Log.d("NHAT", "startLocalVideoCapture peerConnection: $peerConnection")
                Log.d("NHAT", "startLocalVideoCapture localVideoTrack: $localVideoTrack")
                peerConnection?.addTrack(localVideoTrack)
                peerConnection?.addTrack(localAudioTrack)
                Log.d("NHAT", "startLocalVideoCapture hasVideoTrack=${hasTrack(peerConnection!!)}")
            }
        } catch (e: Exception) {
            Log.e("CameraCapture", "Error starting camera: ${e.message}")
        }
    }

    private fun createCameraCapturer(context: Context): VideoCapturer? {
        val cameraEnumerator = if (Camera2Enumerator.isSupported(context)) {
            Camera2Enumerator(context)
        } else {
            Camera1Enumerator(true)
        }

        return cameraEnumerator.run {
            deviceNames.find { isFrontFacing(it) }?.let {
                createCapturer(it, null)
            } ?: deviceNames.firstOrNull()?.let {
                createCapturer(it, null)
            }
        }
    }

    private fun createOffer() {
        Log.d("NHAT", "createOffer")
        Log.d("NHAT", "createOffer hasVideoTrack: ${hasTrack(peerConnection!!)}")
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {
                    }

                    override fun onSetSuccess() {
                        desc?.let {
                            val offerJson = JSONObject().apply {
                                put("type", it.type.canonicalForm())
                                put("sdp", it.description)
                            }
                            socket?.emit("offer", offerJson)
                        }
                    }

                    override fun onCreateFailure(p0: String?) {
                    }

                    override fun onSetFailure(s: String?) {}
                }, desc)
            }

            override fun onSetFailure(p0: String?) {}
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
        }, constraints)
    }

    private fun handleOffer(offer: SessionDescription) {
        Log.d("NHAT", "handleOffer: $offer")
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {
                Log.d("NHAT", "handleOffer onCreateSuccess")
            }

            override fun onSetSuccess() {
                Log.d("NHAT", "handleOffer onSetSuccess")
                localVideoTrack =
                    peerConnectionFactory?.createVideoTrack("local_video_track", videoSource)
                localAudioTrack = peerConnectionFactory?.createAudioTrack("local_audio_track", audioSource)
                peerConnection?.addTrack(localVideoTrack)
                peerConnection?.addTrack(localAudioTrack)
                Log.d("NHAT", "handleOffer hasVideoTrack=${hasTrack(peerConnection!!)}")
                createAnswer()
            }

            override fun onCreateFailure(p0: String?) {
                Log.d("NHAT", "handleOffer onCreateFailure: $p0")
            }

            override fun onSetFailure(p0: String?) {
                Log.d("NHAT", "handleOffer onSetFailure: $p0")
            }
        }, offer)
    }

    private fun hasTrack(peerConnection: PeerConnection): Boolean {
        // Lấy danh sách tất cả các RtpSender
        val senders = peerConnection.senders

        // Tìm xem có sender nào đang quản lý một VideoTrack không
        return senders.any { sender ->
            sender.track() is VideoTrack
        } && senders.any { sender ->
            sender.track() is AudioTrack
        }
    }

    private fun createAnswer() {
        Log.d("NHAT", "createAnswer")
        Log.d("NHAT", "createAnswer peerConnection: $peerConnection")
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                Log.d("NHAT", "createAnswer onCreateSuccess: $desc")
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {
                        Log.d("NHAT", "createAnswer setLocalDescription onCreateSuccess")
                    }

                    override fun onSetSuccess() {
                        Log.d("NHAT", "createAnswer setLocalDescription onSetSuccess")
                        desc?.let {
                            val answerJson = JSONObject().apply {
                                put("type", it.type.canonicalForm())
                                put("sdp", it.description)
                            }
                            Log.d("NHAT", "answerJson: $answerJson")
                            socket?.emit("answer", answerJson)
                        }
                    }

                    override fun onCreateFailure(p0: String?) {
                        Log.d("NHAT", "createAnswer setLocalDescription onCreateFailure")
                    }

                    override fun onSetFailure(p0: String?) {
                        Log.d("NHAT", "createAnswer setLocalDescription onSetFailure")
                    }
                }, desc)
            }

            override fun onSetFailure(p0: String?) {
                Log.d("NHAT", "createAnswer onSetFailure: $p0")
            }

            override fun onSetSuccess() {
                Log.d("NHAT", "createAnswer onSetSuccess")
            }

            override fun onCreateFailure(p0: String?) {
                Log.d("NHAT", "createAnswer onCreateFailure: $p0")
            }
        }, constraints)
    }

    private fun handleAnswer(answer: SessionDescription) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {
                Log.d("NHAT", "handleAnswer onCreateSuccess")
            }

            override fun onSetSuccess() {
                Log.d("NHAT", "handleAnswer onSetSuccess")
            }

            override fun onCreateFailure(p0: String?) {
                Log.d("NHAT", "handleAnswer onCreateFailure")
            }

            override fun onSetFailure(p0: String?) {
                Log.d("NHAT", "handleAnswer onSetFailure")
            }
        }, answer)
    }

    private fun addIceCandidate(candidate: IceCandidate) {
        Log.d("NHAT", "addIceCandidate: $candidate")
        peerConnection?.addIceCandidate(candidate)
    }

    abstract class PeerConnectionObserver : PeerConnection.Observer {
        override fun onSignalingChange(signalingState: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(iceCandidate: IceCandidate?) {}
        override fun onIceCandidatesRemoved(iceCandidates: Array<out IceCandidate>?) {}
        override fun onAddStream(mediaStream: MediaStream?) {}
        override fun onRemoveStream(mediaStream: MediaStream?) {}
        override fun onDataChannel(dataChannel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(rtpReceiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
    }
}