package com.nexusai.app

import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.IceCandidate
import org.webrtc.SurfaceTextureHelper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small peer wrapper shared by the Target foreground service and Controller viewer.
 * Only SDP/ICE metadata uses the backend. Audio/video media is carried by WebRTC.
 */
internal class NexusWebRtcPeer(
    context: Context,
    iceConfig: JSONObject,
    private val onLocalIce: (IceCandidate) -> Unit,
    private val onRemoteVideo: (VideoTrack) -> Unit,
    private val onState: (String) -> Unit
) {
    private val appContext = context.applicationContext
    val eglBase: EglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private val peer: PeerConnection
    private var cameraCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private val disposed = AtomicBoolean(false)
    private val remoteDescriptionSet = AtomicBoolean(false)
    private val pendingRemoteIce = mutableListOf<IceCandidate>()

    init {
        synchronized(FACTORY_LOCK) {
            if (!factoryInitialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions()
                )
                factoryInitialized = true
            }
        }
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        val servers = parseIceServers(iceConfig.optJSONArray("iceServers") ?: JSONArray())
        val rtcConfig = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        peer = factory.createPeerConnection(rtcConfig, observer)
            ?: throw IllegalStateException("WEBRTC_PEER_CREATE_FAILED")
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            onState("ICE_${state.name}")
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidate(candidate: IceCandidate) { onLocalIce(candidate) }
        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) {
            stream.videoTracks.firstOrNull()?.let(onRemoteVideo)
        }
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {
            (receiver.track() as? VideoTrack)?.let(onRemoteVideo)
        }
        override fun onTrack(transceiver: RtpTransceiver) {
            (transceiver.receiver.track() as? VideoTrack)?.let(onRemoteVideo)
        }
    }

    fun startLocalCapture(useFrontCamera: Boolean, includeMicrophone: Boolean) {
        check(!disposed.get()) { "WEBRTC_PEER_CLOSED" }
        val enumerator = Camera2Enumerator(appContext)
        val names = enumerator.deviceNames
        val selected = names.firstOrNull {
            if (useFrontCamera) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
        } ?: names.firstOrNull() ?: throw IllegalStateException("CAMERA_NOT_AVAILABLE")
        val capturer = enumerator.createCapturer(selected, null) as? CameraVideoCapturer
            ?: throw IllegalStateException("CAMERA2_CAPTURER_UNAVAILABLE")
        cameraCapturer = capturer
        surfaceTextureHelper = SurfaceTextureHelper.create("NEXUS-WebRTC-Camera", eglBase.eglBaseContext)
        val source = factory.createVideoSource(false)
        videoSource = source
        capturer.initialize(surfaceTextureHelper, appContext, source.capturerObserver)
        capturer.startCapture(1280, 720, 30)
        val track = factory.createVideoTrack("nexus-camera-video", source)
        localVideoTrack = track
        peer.addTrack(track, listOf("nexus-camera-stream"))

        if (includeMicrophone &&
            appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            }
            val sourceAudio = factory.createAudioSource(constraints)
            audioSource = sourceAudio
            val trackAudio = factory.createAudioTrack("nexus-camera-audio", sourceAudio)
            localAudioTrack = trackAudio
            peer.addTrack(trackAudio, listOf("nexus-camera-stream"))
        }
    }

    fun addReceiveOnlyVideo() {
        peer.addTransceiver(
            org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        )
        peer.addTransceiver(
            org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        )
    }

    fun createOffer(onCreated: (String) -> Unit, onError: (String) -> Unit) {
        peer.createOffer(descriptionObserver(onCreated, onError), MediaConstraints())
    }

    fun createAnswer(onCreated: (String) -> Unit, onError: (String) -> Unit) {
        peer.createAnswer(descriptionObserver(onCreated, onError), MediaConstraints())
    }

    fun setLocalDescription(type: SessionDescription.Type, sdp: String, onDone: () -> Unit, onError: (String) -> Unit) {
        peer.setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription?) = Unit
            override fun onSetSuccess() { onDone() }
            override fun onCreateFailure(error: String?) { onError(error ?: "SDP_CREATE_FAILED") }
            override fun onSetFailure(error: String?) { onError(error ?: "SDP_LOCAL_SET_FAILED") }
        }, SessionDescription(type, sdp))
    }

    fun setRemoteDescription(type: SessionDescription.Type, sdp: String, onDone: () -> Unit, onError: (String) -> Unit) {
        peer.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription?) = Unit
            override fun onSetSuccess() {
                remoteDescriptionSet.set(true)
                val buffered = synchronized(pendingRemoteIce) {
                    pendingRemoteIce.toList().also { pendingRemoteIce.clear() }
                }
                buffered.forEach { peer.addIceCandidate(it) }
                onDone()
            }
            override fun onCreateFailure(error: String?) { onError(error ?: "SDP_CREATE_FAILED") }
            override fun onSetFailure(error: String?) { onError(error ?: "SDP_REMOTE_SET_FAILED") }
        }, SessionDescription(type, sdp))
    }

    fun addRemoteIce(candidate: IceCandidate) {
        if (remoteDescriptionSet.get()) peer.addIceCandidate(candidate)
        else synchronized(pendingRemoteIce) { pendingRemoteIce.add(candidate) }
    }

    fun attachRenderer(renderer: SurfaceViewRenderer) {
        renderer.init(eglBase.eglBaseContext, null)
        renderer.setEnableHardwareScaler(true)
        renderer.setMirror(false)
    }

    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        try { cameraCapturer?.stopCapture() } catch (_: Exception) { }
        cameraCapturer?.dispose()
        cameraCapturer = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        localVideoTrack?.dispose()
        localAudioTrack?.dispose()
        videoSource?.dispose()
        audioSource?.dispose()
        peer.close()
        peer.dispose()
        factory.dispose()
        eglBase.release()
    }

    private fun descriptionObserver(onCreated: (String) -> Unit, onError: (String) -> Unit) =
        object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription?) {
                if (description == null) { onError("SDP_EMPTY"); return }
                setLocalDescription(description.type, description.description,
                    { onCreated(description.description) }, onError)
            }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String?) { onError(error ?: "SDP_CREATE_FAILED") }
            override fun onSetFailure(error: String?) { onError(error ?: "SDP_SET_FAILED") }
        }

    private fun parseIceServers(array: JSONArray): List<PeerConnection.IceServer> {
        val output = mutableListOf<PeerConnection.IceServer>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val urls = item.opt("urls")
            val values = when (urls) {
                is String -> listOf(urls)
                is JSONArray -> (0 until urls.length()).mapNotNull { urls.optString(it).takeIf(String::isNotBlank) }
                else -> emptyList()
            }
            values.forEach { url ->
                val builder = PeerConnection.IceServer.builder(url)
                val username = item.optString("username")
                val credential = item.optString("credential")
                if (username.isNotBlank()) builder.setUsername(username)
                if (credential.isNotBlank()) builder.setPassword(credential)
                output.add(builder.createIceServer())
            }
        }
        if (output.isEmpty()) output.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        return output
    }

    companion object {
        private val FACTORY_LOCK = Any()
        @Volatile private var factoryInitialized = false
    }
}
