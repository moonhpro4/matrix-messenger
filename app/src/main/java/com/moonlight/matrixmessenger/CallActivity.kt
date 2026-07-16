package com.moonlight.matrixmessenger

import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.webrtc.*

/**
 * Handles the in-call screen: mic mute, camera on/off (including turning
 * the camera ON mid-call even if the call started as voice-only), speaker
 * vs earpiece audio routing, and screen sharing.
 *
 * IMPORTANT — scope of what this file actually does:
 * This controls LOCAL media (your own mic/camera/screen) and where audio
 * plays. Actually connecting to the other person's audio/video still needs
 * a signaling server to exchange WebRTC session descriptions (SDP) and
 * ICE candidates between the two phones — that server is a separate piece,
 * not yet built. The PeerConnection setup below is structured to be ready
 * for that signaling layer to plug into (see the TODOs).
 *
 * NOT TESTED: this sandbox has no Android SDK and cannot reach Maven
 * Central to even download the WebRTC library, so none of this has been
 * compiled or run. Written carefully against WebRTC's documented Android
 * API — verify in Android Studio on a real device before relying on it.
 */
class CallActivity : AppCompatActivity() {

    private lateinit var eglBase: EglBase
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private lateinit var audioManager: AudioManager

    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var cameraCapturer: CameraVideoCapturer? = null
    private var screenCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var micEnabled = true
    private var cameraEnabled = false // starts false for a voice-only call
    private var speakerOn = true
    private var screenSharing = false

    private var ringtonePlayer: MediaPlayer? = null
    private var callingSoundPlayer: MediaPlayer? = null

    private lateinit var remoteVideoView: SurfaceViewRenderer
    private lateinit var localVideoView: SurfaceViewRenderer
    private lateinit var muteButton: ImageButton
    private lateinit var cameraToggleButton: ImageButton
    private lateinit var speakerToggleButton: ImageButton
    private lateinit var screenShareButton: ImageButton
    private lateinit var hangUpButton: ImageButton
    private lateinit var callStatusText: TextView

    private lateinit var otherUsername: String
    private lateinit var currentUsername: String
    private val callService = CallService(CloudflareKvClient())

    companion object {
        private const val SCREEN_CAPTURE_REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call)

        currentUsername = intent.getStringExtra("username") ?: "unknown"
        otherUsername = intent.getStringExtra("otherUsername") ?: "unknown"
        val startAsVideo = intent.getBooleanExtra("startAsVideo", false)
        val isIncoming = intent.getBooleanExtra("isIncoming", false)
        cameraEnabled = startAsVideo

        bindViews()
        initWebRtc()
        setUpLocalAudio()
        if (startAsVideo) {
            enableCamera()
        }
        wireButtonListeners()

        if (isIncoming) {
            callStatusText.text = "$otherUsername is calling..."
            playRingtone()
        } else {
            callStatusText.text = "Calling $otherUsername..."
            playCallingSound()
            pollForCalleeResponse()
        }
    }

    // ---------------- Call sounds ----------------

    /** Plays on the CALLEE's side while the call is ringing, looping until answered/declined. */
    private fun playRingtone() {
        stopAllCallSounds()
        ringtonePlayer = MediaPlayer.create(this, R.raw.ringtone)?.apply {
            isLooping = true
            start()
        }
    }

    /** Plays on the CALLER's side while waiting for the other person to pick up. */
    private fun playCallingSound() {
        stopAllCallSounds()
        callingSoundPlayer = MediaPlayer.create(this, R.raw.calling_sound)?.apply {
            isLooping = true
            start()
        }
    }

    /** Plays once when a call is declined or hung up by either side. */
    private fun playHangUpSound() {
        stopAllCallSounds()
        MediaPlayer.create(this, R.raw.hang_up)?.apply {
            setOnCompletionListener { it.release() }
            start()
        }
    }

    private fun stopAllCallSounds() {
        ringtonePlayer?.stop()
        ringtonePlayer?.release()
        ringtonePlayer = null

        callingSoundPlayer?.stop()
        callingSoundPlayer?.release()
        callingSoundPlayer = null
    }

    /**
     * Caller-side: periodically checks whether the callee answered or
     * declined, so the calling sound stops and the UI updates accordingly.
     * This is the same lightweight KV-polling pattern used elsewhere in
     * the app (checks every few seconds, not a live push).
     */
    private fun pollForCalleeResponse() {
        val handler = android.os.Handler(mainLooper)
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
                                android.os.Handler(mainLooper).postDelayed({ finish() }, 1500)
                            }
                            else -> {
                                // still ringing — check again shortly
                                handler.postDelayed(this, 3000)
                            }
                        }
                    }
                }.start()
            }
        }
        handler.postDelayed(pollRunnable, 3000)
    }

    private fun bindViews() {
        remoteVideoView = findViewById(R.id.remoteVideoView)
        localVideoView = findViewById(R.id.localVideoView)
        muteButton = findViewById(R.id.muteButton)
        cameraToggleButton = findViewById(R.id.cameraToggleButton)
        speakerToggleButton = findViewById(R.id.speakerToggleButton)
        screenShareButton = findViewById(R.id.screenShareButton)
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

        // TODO: create the actual PeerConnection here using ICE servers
        // (STUN/TURN) and wire its SDP offer/answer + ICE candidates through
        // your signaling server. This is the piece that makes the call
        // actually carry audio/video between the two phones.
    }

    private fun setUpLocalAudio() {
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = speakerOn

        val audioConstraints = MediaConstraints()
        val audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack("audio0", audioSource)
        localAudioTrack?.setEnabled(micEnabled)

        // TODO: add localAudioTrack to the PeerConnection once it's created.
    }

    // ---------------- Mic mute ----------------

    private fun toggleMute() {
        micEnabled = !micEnabled
        localAudioTrack?.setEnabled(micEnabled)
        muteButton.alpha = if (micEnabled) 1.0f else 0.4f
    }

    // ---------------- Camera on/off (works even mid voice call) ----------------

    private fun toggleCamera() {
        if (cameraEnabled) {
            disableCamera()
        } else {
            enableCamera()
        }
    }

    /**
     * Turns the camera ON — this works whether the call started as voice-only
     * or video, satisfying "open your camera during a voice call."
     * Adding a video track mid-call requires renegotiating the PeerConnection
     * (a new SDP offer/answer round-trip through the signaling server) —
     * see the TODO below.
     */
    private fun enableCamera() {
        if (screenSharing) {
            stopScreenShare()
        }

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

        cameraEnabled = true
        cameraToggleButton.alpha = 1.0f

        // TODO: if the PeerConnection already exists (call in progress),
        // add this track and call createOffer() again to renegotiate,
        // sending the new SDP through your signaling server so the other
        // side receives your video.
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

    // ---------------- Screen share ----------------

    private fun toggleScreenShare() {
        if (screenSharing) {
            stopScreenShare()
        } else {
            requestScreenCapturePermission()
        }
    }

    private fun requestScreenCapturePermission() {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            startScreenShare(resultCode, data)
        }
    }

    private fun startScreenShare(resultCode: Int, data: Intent) {
        // Screen capture requires a running foreground service while active
        // (Android 10+ requirement) — see ScreenShareService.kt
        startService(Intent(this, ScreenShareService::class.java))

        if (cameraEnabled) {
            disableCamera()
        }

        val newVideoSource = peerConnectionFactory.createVideoSource(true) // true = screencast
        videoSource = newVideoSource

        screenCapturer = ScreenCapturerAndroid(data, object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() {
                stopScreenShare()
            }
        })

        surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglBase.eglBaseContext)
        screenCapturer?.initialize(surfaceTextureHelper, this, newVideoSource.capturerObserver)
        screenCapturer?.startCapture(1280, 720, 30)

        localVideoTrack = peerConnectionFactory.createVideoTrack("screen0", newVideoSource)
        localVideoTrack?.addSink(localVideoView)
        localVideoTrack?.setEnabled(true)

        screenSharing = true
        screenShareButton.alpha = 1.0f

        // TODO: same as camera — renegotiate the PeerConnection so the
        // other side receives this screen-share video track.
    }

    private fun stopScreenShare() {
        screenCapturer?.stopCapture()
        screenCapturer?.dispose()
        screenCapturer = null
        localVideoTrack?.setEnabled(false)
        localVideoTrack = null
        stopService(Intent(this, ScreenShareService::class.java))

        screenSharing = false
        screenShareButton.alpha = 0.4f
    }

    // ---------------- Buttons ----------------

    private fun wireButtonListeners() {
        muteButton.setOnClickListener { toggleMute() }
        cameraToggleButton.setOnClickListener { toggleCamera() }
        speakerToggleButton.setOnClickListener { toggleSpeaker() }
        screenShareButton.setOnClickListener { toggleScreenShare() }
        hangUpButton.setOnClickListener { hangUp() }
    }

    private fun hangUp() {
        callService.endCall(otherUsername)
        playHangUpSound()
        disableCamera()
        stopScreenShare()
        android.os.Handler(mainLooper).postDelayed({ finish() }, 800)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAllCallSounds()
        audioManager.mode = AudioManager.MODE_NORMAL
        remoteVideoView.release()
        localVideoView.release()
        peerConnectionFactory.dispose()
        eglBase.release()
    }
}
