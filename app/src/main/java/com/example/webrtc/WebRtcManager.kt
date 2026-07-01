package com.example.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class WebRtcManager(private val context: Context) {

    companion object {
        private const val TAG = "WebRtcManager"
    }

    // Shared execution context for WebRTC background work
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    // EglBase for sharing OpenGL context across renderers
    val eglContext: EglBase.Context = EglBase.create().eglBaseContext

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    
    // Media sources and tracks
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    
    private var videoCapturer: CameraVideoCapturer? = null
    
    // State indicators
    var isMuted = false
        private set
    var isVideoEnabled = true
        private set
    var isFrontCamera = true
        private set

    init {
        initPeerConnectionFactory()
    }

    private fun initPeerConnectionFactory() {
        executor.execute {
            try {
                // Initialize PeerConnectionFactory globals
                val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
                    .createInitializationOptions()
                PeerConnectionFactory.initialize(initOptions)

                val options = PeerConnectionFactory.Options()
                
                peerConnectionFactory = PeerConnectionFactory.builder()
                    .setOptions(options)
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
                    .createPeerConnectionFactory()

                Log.d(TAG, "WebRTC PeerConnectionFactory created successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize WebRTC factory", e)
            }
        }
    }

    /**
     * Initializes and starts capturing from the device camera.
     */
    fun startLocalVideo(renderer: SurfaceViewRenderer) {
        executor.execute {
            try {
                val factory = peerConnectionFactory ?: return@execute
                
                // Initialize local video renderer
                renderer.init(eglContext, null)
                renderer.setEnableHardwareScaler(true)
                renderer.setMirror(true)

                // Enumerate camera sources (prioritizing front-facing camera)
                val enumerator = Camera2Enumerator(context)
                val deviceNames = enumerator.deviceNames
                var frontCameraName: String? = null
                
                for (deviceName in deviceNames) {
                    if (enumerator.isFrontFacing(deviceName)) {
                        frontCameraName = deviceName
                        break
                    }
                }
                
                val cameraName = frontCameraName ?: deviceNames.firstOrNull() ?: return@execute
                
                // Create camera capturer
                videoCapturer = enumerator.createCapturer(cameraName, object : CameraVideoCapturer.CameraEventsHandler {
                    override fun onCameraError(p0: String?) { Log.e(TAG, "Camera error: $p0") }
                    override fun onCameraDisconnected() { Log.d(TAG, "Camera disconnected") }
                    override fun onCameraFreezed(p0: String?) { Log.w(TAG, "Camera frozen: $p0") }
                    override fun onCameraOpening(p0: String?) { Log.d(TAG, "Opening camera: $p0") }
                    override fun onFirstFrameAvailable() { Log.d(TAG, "First frame available") }
                    override fun onCameraClosed() { Log.d(TAG, "Camera closed") }
                })

                // Setup local video track
                videoSource = factory.createVideoSource(videoCapturer?.isScreencast == true)
                videoCapturer?.initialize(SurfaceTextureHelper.create("CaptureThread", eglContext), context, videoSource?.capturerObserver)
                videoCapturer?.startCapture(1280, 720, 30)

                videoTrack = factory.createVideoTrack("local_video_track", videoSource)
                videoTrack?.addSink(renderer)
                
                Log.d(TAG, "Local video track initiated and bound to renderer.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start local video", e)
            }
        }
    }

    /**
     * Initializes and starts local audio capture.
     */
    fun startLocalAudio() {
        executor.execute {
            try {
                val factory = peerConnectionFactory ?: return@execute
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("echoCancellation", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("noiseSuppression", "true"))
                }
                audioSource = factory.createAudioSource(constraints)
                audioTrack = factory.createAudioTrack("local_audio_track", audioSource)
                Log.d(TAG, "Local audio track initiated successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start local audio", e)
            }
        }
    }

    /**
     * Switches between front-facing and back-facing cameras.
     */
    fun switchCamera() {
        videoCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) {
                isFrontCamera = isFront
                Log.d(TAG, "Camera switched successfully. Front-facing: $isFront")
            }

            override fun onCameraSwitchError(p0: String?) {
                Log.e(TAG, "Camera switch error: $p0")
            }
        })
    }

    /**
     * Toggles local audio mute state.
     */
    fun toggleMute(mute: Boolean) {
        executor.execute {
            audioTrack?.setEnabled(!mute)
            isMuted = mute
            Log.d(TAG, "Audio muted: $mute")
        }
    }

    /**
     * Toggles local video enabled state.
     */
    fun toggleVideo(enable: Boolean) {
        executor.execute {
            videoTrack?.setEnabled(enable)
            isVideoEnabled = enable
            if (enable) {
                videoCapturer?.startCapture(1280, 720, 30)
            } else {
                try {
                    videoCapturer?.stopCapture()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop video capturing", e)
                }
            }
            Log.d(TAG, "Video track enabled: $enable")
        }
    }

    /**
     * Creates peer connection and configures STUN/TURN servers.
     */
    fun createPeerConnection(iceServersList: List<PeerConnection.IceServer>, observer: PeerConnection.Observer) {
        executor.execute {
            val factory = peerConnectionFactory ?: return@execute
            val rtcConfig = PeerConnection.RTCConfiguration(iceServersList).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
            peerConnection = factory.createPeerConnection(rtcConfig, observer)
            
            // Add local tracks to peer connection
            val mediaStreamLabels = listOf("local_stream")
            videoTrack?.let { peerConnection?.addTrack(it, mediaStreamLabels) }
            audioTrack?.let { peerConnection?.addTrack(it, mediaStreamLabels) }
            
            Log.d(TAG, "WebRTC PeerConnection created.")
        }
    }

    /**
     * Stops camera, audio, release renderers, and closes active peer connections cleanly.
     */
    fun cleanUp() {
        executor.execute {
            try {
                videoCapturer?.stopCapture()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping capture during cleanup", e)
            }
            
            videoCapturer?.dispose()
            videoCapturer = null

            videoSource?.dispose()
            videoSource = null

            audioSource?.dispose()
            audioSource = null

            peerConnection?.close()
            peerConnection = null
            
            Log.d(TAG, "WebRTC manager resources released.")
        }
    }
}
