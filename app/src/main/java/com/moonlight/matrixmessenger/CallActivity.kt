package com.moonlight.matrixmessenger

import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.webrtc.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles the in-call screen: mic mute, camera on/off (including turning
 * the camera ON mid-call even if the call started as voice-only), and
 * speaker vs earpiece audio routing.
 *
 * Real signaling: WebRTC needs a one-time exchange of connection info
 * (SDP offer/answer + ICE candidates) before audio/video can flow between
 * the two phones. That exchange happens here via Cloudflare KV polling
 * (see CallService) — a few seconds of setup delay, but after that,
 * media flows directly between the two phones, not through KV.
 *
 * NOT TESTED: this sandbox has no Android SDK and cannot reach Maven
 * Central to even download the WebRTC library, so none of this has been
 * compiled or run. Written carefully against WebRTC's documented Android
 * API — verify in Android Studio on a real device before relying on it.
 */
class CallActivity : AppCompatActivity() {

    private lateinit var eglBase: EglBase
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private lateinit var peerConnection: PeerConnection
    private lateinit var audioManager: AudioManager

    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var cameraCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var micEnabled = true
    private var cameraEnabled = false // starts false for a voice-only call
    private var speakerOn = true

    private var ringtonePlayer: MediaPlayer? = null
    private var callingSoundPlayer: MediaPlayer? = null

    private lateinit var remoteVideoView: SurfaceViewRenderer
    private lateinit var localVideoView: SurfaceViewRenderer
    private lateinit var muteButton: ImageButton
    private lateinit var cameraToggleButton: ImageButton
    private lateinit var speakerToggleButton: ImageButton
    private lateinit var hangUpButton: ImageButton
    private lateinit var callStatusText: TextView

    private lateinit var otherUsername: String
    private lateinit var currentUsername: String
    private var isIncomingCall = false
    private val callService = CallService(CloudflareKvClient())

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val seenRemoteIceCandidates = ConcurrentHashMap.newKeySet<String>()
    private var signalingActive = true

    // caller/callee, in the sense CallService's KV keys use
    private val callerUsername: String get() = if (isIncomingCall) otherUsername else currentUsername
    private val calleeUsername: String get() = if (isIncomingCall) currentUsername else otherUsername

    companion object {
        private val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call)

        currentUsername = intent.getStringExtra("username") ?: "unknown"
        otherUsername = intent.getStringExtra("otherUsername") ?: "unknown"
        val startAsVideo = intent.getBooleanExtra("startAsVideo", false)
        isIncomingCall = intent.getBooleanExtra("isIncoming", false)
        cameraEnabled = startAsVideo

        bindViews()
        initWebRtc()
        setUpLocalAudio()
        if (startAsVideo) {
            enableCamera()
        }
        wireButtonListeners()

        if (isIncomingCall) {
            callStatusText.text = "$otherUsername is calling..."
            playRingtone()
            startAnswererSignaling()
        } else {
            callStatusText.text = "Calling $otherUsername..."
            playCallingSound()
            pollForCalleeResponse()
            startCallerSignaling()
        }

        pollForRemoteIceCandidates()
    }

    private fun bindViews() {
        remoteVideoView = findViewById(R.id.remoteVideoView)
        localVideoView = findViewById(R.id.localVideoView)
        muteButton = findViewById(R.id.muteButton)
        cameraToggleButton = findViewById(R.id.cameraToggleButton)
        speakerToggleButton = findViewById(R.id.speakerToggleButton)
        hangUpButton = findViewById(R.id.hangUpButton)
        callStatusText = findViewById(R.id.callStatusText)
    }

    // ---------------- WebRTC setup ----------------

    private fun initWebRtc() {
        eglBase = EglBase.create()

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                .createInitializationOptions()
        )

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        remoteVideoView.init(eglBase.eglBaseContext, null)
        localVideoView.init(eglBase.eglBaseContext, null)

        val rtcConfig = PeerConnection.RTCConfiguration(ICE_SERVERS)

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null) return
                val json = """{"sdpMid":"${candidate.sdpMid}","sdpMLineIndex":${candidate.sdpMLineIndex},"candidate":"${candidate.sdp.replace("\"", "\\\"")}"}"""
                Thread { callService.addIceCandidate(otherUsername, currentUsername, json) }.start()
            }

            override fun onAddStream(stream: MediaStream?) {
                runOnUiThread {
                    stream?.videoTracks?.firstOrNull()?.addSink(remoteVideoView)
                    // Audio plays automatically once the track is added — no explicit sink needed.
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                runOnUiThread {
                    if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                        callStatusText.text = "Connected"
                    }
                }
            }

            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        })!!
    }

    private fun setUpLocalAudio() {
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = speakerOn

        val audioConstraints = MediaConstraints()
        val audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack("audio0", audioSource)
        localAudioTrack?.setEnabled(micEnabled)
        peerConnection.addTrack(localAudioTrack)
    }

    // ---------------- Signaling: caller side ----------------

    private fun startCallerSignaling() {
        val sdpConstraints = MediaConstraints()
        peerConnection.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                peerConnection.setLocalDescription(SimpleSdpObserver(), desc)
                Thread { callService.sendOffer(otherUsername, desc.description) }.start()
                pollForAnswer()
            }
        }, sdpConstraints)
    }

    private fun pollForAnswer() {
        Thread {
            var answer: String? = null
            while (signalingActive && answer == null) {
                answer = callService.getAnswer(currentUsername)
                if (answer == null) Thread.sleep(2000)
            }
            if (answer != null && signalingActive) {
                val desc = SessionDescription(SessionDescription.Type.ANSWER, answer)
                runOnUiThread {
                    peerConnection.setRemoteDescription(SimpleSdpObserver(), desc)
                }
            }
        }.start()
    }

    // ---------------- Signaling: answerer side ----------------

    private fun startAnswererSignaling() {
        Thread {
            var offer: String? = null
            while (signalingActive && offer == null) {
                offer = callService.getOffer(currentUsername)
                if (offer == null) Thread.sleep(2000)
            }
            if (offer != null && signalingActive) {
                val desc = SessionDescription(SessionDescription.Type.OFFER, offer)
                runOnUiThread {
                    peerConnection.setRemoteDescription(object : SimpleSdpObserver() {
                        override fun onSetSuccess() {
                            val sdpConstraints = MediaConstraints()
                            peerConnection.createAnswer(object : SimpleSdpObserver() {
                                override fun onCreateSuccess(answerDesc: SessionDescription?) {
                                    if (answerDesc == null) return
                                    peerConnection.setLocalDescription(SimpleSdpObserver(), answerDesc)
                                    Thread { callService.sendAnswer(otherUsername, answerDesc.description) }.start()
                                }
                            }, sdpConstraints)
                        }
                    }, desc)
                }
            }
        }.start()
    }

    // ---------------- ICE candidate exchange (both sides) ----------------

    private fun pollForRemoteIceCandidates() {
        Thread {
            while (signalingActive) {
                val candidates = callService.getIceCandidates(currentUsername, otherUsername)
                candidates.forEach { json ->
                    if (seenRemoteIceCandidates.add(json)) {
                        try {
                            val map = SimpleJson.parseObject(json)
                            val candidate = IceCandidate(
                                map["sdpMid"] as String,
                                (map["sdpMLineIndex"] as String).toInt(),
                                map["candidate"] as String
                            )
                            runOnUiThread { peerConnection.addIceCandidate(candidate) }
                        } catch (e: Exception) {
                            // malformed candidate — skip it rather than crash the call
                        }
                    }
                }
                Thread.sleep(2000)
            }
        }.start()
    }

    /** Minimal no-op SdpObserver base so callers only override what they need. */
    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }

    // ---------------- Call sounds ----------------

    private fun playRingtone() {
        stopAllCallSounds()

        val ringtoneService = RingtoneService(CloudflareKvClient())
        Thread {
            val selectedId = ringtoneService.getSelectedRingtoneId(currentUsername)
            val customAudio = if (selectedId != null) ringtoneService.getCustomRingtoneAudio(currentUsername, selectedId) else null

            runOnUiThread {
                if (customAudio != null) {
                    try {
                        val tempFile = java.io.File.createTempFile("ringtone", ".mp3", cacheDir)
                        tempFile.writeBytes(customAudio)
                        ringtonePlayer = MediaPlayer().apply {
                            setDataSource(tempFile.absolutePath)
                            isLooping = true
                            prepare()
                            start()
                        }
                    } catch (e: Exception) {
                        playDefaultRingtone()
                    }
                } else {
                    playDefaultRingtone()
                }
            }
        }.start()
    }

    private fun playDefaultRingtone() {
        ringtonePlayer = MediaPlayer.create(this, R.raw.ringtone)?.apply {
            isLooping = true
            start()
        }
    }

    private fun playCallingSound() {
        stopAllCallSounds()
        callingSoundPlayer = MediaPlayer.create(this, R.raw.calling_sound)?.apply {
            isLooping = true
            start()
        }
    }

    private fun playHangUpSound() {
        stopAllCallSounds()
        MediaPlayer.create(this, R.raw.hang_up)?.apply {
            setOnCompletionListener { it.release() }
            start()
        }
    }

    private fun stopAllCallSounds() {
        ringtonePlayer?.stop(); ringtonePlayer?.release(); ringtonePlayer = null
        callingSoundPlayer?.stop(); callingSoundPlayer?.release(); callingSoundPlayer = null
    }

    private fun pollForCalleeResponse() {
        val pollRunnable = object : Runnable {
            override fun run() {
                Thread {
                    val status = callService.checkCallStatus(otherUsername)
                    runOnUiThread {
                        when (status?.status) {
                            CallStatus.ANSWERED -> {
                                stopAllCallSounds()
                                callStatusText.text = "Connected"
                            }
                            CallStatus.DECLINED, CallStatus.MISSED, CallStatus.ENDED -> {
                                playHangUpSound()
                                callStatusText.text = "Call ended"
                                handler.postDelayed({ finish() }, 1500)
                            }
                            else -> handler.postDelayed(this, 3000)
                        }
                    }
                }.start()
            }
        }
        handler.postDelayed(pollRunnable, 3000)
    }

    // ---------------- Mic mute ----------------

    private fun toggleMute() {
        micEnabled = !micEnabled
        localAudioTrack?.setEnabled(micEnabled)
        muteButton.alpha = if (micEnabled) 1.0f else 0.4f
    }

    // ---------------- Camera on/off (works even mid voice call) ----------------

    private fun toggleCamera() {
        if (cameraEnabled) disableCamera() else enableCamera()
    }

    private fun enableCamera() {
        val cameraEnumerator = Camera2Enumerator(this)
        val frontCameraName = cameraEnumerator.deviceNames.firstOrNull { cameraEnumerator.isFrontFacing(it) }
            ?: cameraEnumerator.deviceNames.firstOrNull()

        if (frontCameraName == null) {
            callStatusText.text = "No camera available"
            return
        }

        cameraCapturer = cameraEnumerator.createCapturer(frontCameraName, null)

        val newVideoSource = peerConnectionFactory.createVideoSource(false)
        videoSource = newVideoSource

        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        cameraCapturer?.initialize(surfaceTextureHelper, this, newVideoSource.capturerObserver)
        cameraCapturer?.startCapture(1280, 720, 30)

        localVideoTrack = peerConnectionFactory.createVideoTrack("video0", newVideoSource)
        localVideoTrack?.addSink(localVideoView)
        localVideoTrack?.setEnabled(true)
        peerConnection.addTrack(localVideoTrack)

        cameraEnabled = true
        cameraToggleButton.alpha = 1.0f

        // Adding a track after the initial offer/answer requires renegotiating —
        // re-running the signaling exchange so the other side receives this video.
        if (::peerConnection.isInitialized) {
            if (isIncomingCall) startAnswererSignaling() else startCallerSignaling()
        }
    }

    private fun disableCamera() {
        cameraCapturer?.stopCapture()
        cameraCapturer?.dispose()
        cameraCapturer = null
        localVideoTrack?.setEnabled(false)
        localVideoTrack = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        cameraEnabled = false
        cameraToggleButton.alpha = 0.4f
    }

    // ---------------- Speaker vs earpiece ----------------

    private fun toggleSpeaker() {
        speakerOn = !speakerOn
        audioManager.isSpeakerphoneOn = speakerOn
        speakerToggleButton.alpha = if (speakerOn) 1.0f else 0.4f
    }

    // ---------------- Buttons ----------------

    private fun wireButtonListeners() {
        muteButton.setOnClickListener { toggleMute() }
        cameraToggleButton.setOnClickListener { toggleCamera() }
        speakerToggleButton.setOnClickListener { toggleSpeaker() }
        hangUpButton.setOnClickListener { hangUp() }
    }

    private fun hangUp() {
        signalingActive = false
        callService.endCall(otherUsername)
        Thread { callService.clearSignaling(callerUsername, calleeUsername) }.start()
        playHangUpSound()
        disableCamera()
        handler.postDelayed({ finish() }, 800)
    }

    override fun onDestroy() {
        super.onDestroy()
        signalingActive = false
        stopAllCallSounds()
        audioManager.mode = AudioManager.MODE_NORMAL
        remoteVideoView.release()
        localVideoView.release()
        if (::peerConnection.isInitialized) peerConnection.close()
        peerConnectionFactory.dispose()
        eglBase.release()
    }
}
