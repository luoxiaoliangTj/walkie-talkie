package com.example.walkie

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONObject
import org.webrtc.*

/**
 * Manages WebRTC peer connections with up to 3 members total (2 connections each).
 * Full mesh topology - everyone connected to everyone.
 */
class PeerConnector(
    private val context: Context,
    private val onMessageReceived: (String) -> Unit,
    private val onPeerConnected: (String) -> Unit,
    private val onPeerDisconnected: (String) -> Unit
) {
    private val peerConnections = mutableMapOf<String, PeerConnection>()
    private val dataChannels = mutableMapOf<String, DataChannel>()
    private val pendingIceCandidates = mutableMapOf<String, MutableList<IceCandidate>>()
    private val factory: PeerConnectionFactory
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        val encoderFactory = DefaultVideoEncoderFactory(null, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(null)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun getLocalDescription(remotePeerId: String, callback: (SessionDescription) -> Unit) {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }

        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    pendingIceCandidates.getOrPut(remotePeerId) { mutableListOf() }.add(it)
                }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> onPeerConnected(remotePeerId)
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED -> onPeerDisconnected(remotePeerId)
                    else -> {}
                }
            }

            override fun onDataChannel(channel: DataChannel?) {
                channel?.let { setupDataChannel(remotePeerId, it) }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }

        val peerConnection = factory.createPeerConnection(rtcConfig, observer)!!
        peerConnections[remotePeerId] = peerConnection

        val dataChannelInit = DataChannel.Init().apply {
            ordered = true
            maxRetransmits = -1
        }
        val dataChannel = peerConnection.createDataChannel("walkie", dataChannelInit)
        setupDataChannel(remotePeerId, dataChannel)
        dataChannels[remotePeerId] = dataChannel

        peerConnection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc?.let {
                    peerConnection.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            callback(it)
                        }
                        override fun onSetFailure(error: String?) {
                            callback(it)
                        }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(error: String?) {}
                    }, it)
                }
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
            override fun onCreateFailure(error: String?) {}
        }, MediaConstraints())
    }

    fun handleRemoteAnswer(remotePeerId: String, sdp: String) {
        val peerConnection = peerConnections[remotePeerId] ?: return
        val sessionDescription = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        }, sessionDescription)
    }

    fun handleRemoteIceCandidate(remotePeerId: String, sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        val peerConnection = peerConnections[remotePeerId] ?: return
        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        peerConnection.addIceCandidate(iceCandidate)
    }

    fun sendToAll(message: String) {
        for ((peerId, channel) in dataChannels) {
            if (channel.state() == DataChannel.State.OPEN) {
                val buffer = ByteBuffer.wrap(message.toByteArray())
                val data = DataChannel.Buffer(buffer, false)
                channel.send(data)
            }
        }
    }

    fun disconnect() {
        for (pc in peerConnections.values) {
            pc.close()
        }
        peerConnections.clear()
        dataChannels.clear()
    }

    private fun setupDataChannel(peerId: String, channel: DataChannel) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onMessage(buffer: DataChannel.Buffer?) {
                buffer?.let {
                    val bytes = ByteArray(it.data.remaining())
                    it.data.get(bytes)
                    val message = String(bytes)
                    onMessageReceived(message)
                }
            }

            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                when (channel.state()) {
                    DataChannel.State.OPEN -> onPeerConnected(peerId)
                    DataChannel.State.CLOSED -> onPeerDisconnected(peerId)
                    else -> {}
                }
            }
        })
    }

    fun IceCandidate.toJSONObject(): JSONObject {
        return JSONObject().apply {
            put("sdpMid", sdpMid)
            put("sdpMLineIndex", sdpMLineIndex)
            put("candidate", sdp)
        }
    }

    fun getPendingCandidates(peerId: String): List<IceCandidate> {
        return pendingIceCandidates[peerId] ?: emptyList()
    }

    fun clearPendingCandidates(peerId: String) {
        pendingIceCandidates.remove(peerId)
    }
}
