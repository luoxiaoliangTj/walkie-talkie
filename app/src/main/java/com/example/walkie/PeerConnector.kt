package com.example.walkie

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UDP-based P2P audio walkie-talkie connector.
 * No external dependencies — pure Android SDK + UDP broadcast.
 *
 * Protocol (UDP text on DISCOVERY_PORT):
 *   HELLO:peerId:localIp  → broadcast, announce self
 *   HELLO_ACK:peerId:localIp  → unicast to HELLO sender
 *   HEARTBEAT:peerId:localIp → broadcast every 5s
 *   BYE:peerId:localIp  → broadcast on exit
 *
 * Audio (UDP binary on AUDIO_PORT):
 *   Raw PCM 16-bit mono 16kHz chunks, broadcast.
 */
class PeerConnector(
    private val context: Context,
    private val onMessageReceived: (String) -> Unit,
    private val onPeerConnected: (String) -> Unit,
    private val onPeerDisconnected: (String) -> Unit
) {
    companion object {
        private const val DISCOVERY_PORT = 5000
        private const val AUDIO_PORT = 5001
        private const val SAMPLE_RATE = 16000
        private const val HEARTBEAT_INTERVAL_MS = 5000L
        private const val PEER_TIMEOUT_MS = 15000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ---- Audio config ----
    private val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
    private val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
    private val pcmFormat = AudioFormat.ENCODING_PCM_16BIT
    private val audioRecordBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfigIn, pcmFormat)
    private val audioTrackBufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, channelConfigOut, pcmFormat)

    // ---- State ----
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private val isRecording = AtomicBoolean(false)
    private val isTrackPlaying = AtomicBoolean(false)

    // ---- Sockets ----
    private var discoverySocket: DatagramSocket? = null
    private var audioSocket: DatagramSocket? = null

    // ---- Identity ----
    var localPeerId: String = java.util.UUID.randomUUID().toString().take(8)
        private set
    var localIp: String = "127.0.0.1"
        private set

    // ---- Peers ----
    data class PeerInfo(val ip: String, val lastSeen: Long)
    private val peers = ConcurrentHashMap<String, PeerInfo>()

    // ---- Callbacks ----
    var onPttStateChanged: ((Boolean) -> Unit)? = null

    init {
        detectLocalIp()
        initAudioTrack()
    }

    fun start() {
        startDiscoveryListener()
        startHeartbeat()
        startAudioReceiver()
        scope.launch { sendHello() }
    }

    fun stop() {
        scope.launch { sendBye() }
        scope.cancel()
        stopRecording()
        runCatching { discoverySocket?.close() }
        runCatching { audioSocket?.close() }
        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        audioTrack = null
    }

    // ------------------------------------------------------------------ Identity

    private fun detectLocalIp() {
        runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (iface.isLoopback || !iface.isUp || iface.isPointToPoint) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        localIp = addr.hostAddress ?: continue
                        return
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ Discovery

    private fun startDiscoveryListener() {
        scope.launch {
            while (isActive) {
                try {
                    discoverySocket = DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        bind(InetSocketAddress(DISCOVERY_PORT))
                    }
                    listenDiscoveryLoop()
                } catch (_: Exception) {
                    delay(1000)
                }
            }
        }
    }

    private suspend fun listenDiscoveryLoop() {
        val buf = ByteArray(1024)
        val pkt = DatagramPacket(buf, buf.size)
        while (isActive) {
            runCatching { discoverySocket?.receive(pkt) }
                .getOrNull() ?: break
            val msg = String(pkt.data, 0, pkt.length)
            val senderIp = pkt.address.hostAddress ?: continue
            handleDiscovery(msg, senderIp)
        }
    }

    private suspend fun handleDiscovery(msg: String, senderIp: String) {
        if (senderIp == localIp) return
        val parts = msg.split(":", limit = 3)
        if (parts.size < 3) return
        val (type, peerId, peerIp) = parts
        if (peerId == localPeerId) return

        when (type) {
            "HELLO" -> {
                val isNew = !peers.containsKey(peerId)
                peers[peerId] = PeerInfo(peerIp, System.currentTimeMillis())
                sendHelloAck(peerIp)
                if (isNew) onPeerConnected(peerId)
            }
            "HELLO_ACK" -> {
                val isNew = !peers.containsKey(peerId)
                peers[peerId] = PeerInfo(peerIp, System.currentTimeMillis())
                if (isNew) onPeerConnected(peerId)
            }
            "HEARTBEAT" -> {
                peers[peerId] = PeerInfo(peerIp, System.currentTimeMillis())
            }
            "BYE" -> {
                if (peers.remove(peerId) != null) onPeerDisconnected(peerId)
            }
        }
    }

    private suspend fun sendHello() = sendDiscovery("HELLO:$localPeerId:$localIp")
    private suspend fun sendHelloAck(targetIp: String) = sendDiscovery("HELLO_ACK:$localPeerId:$localIp", targetIp)
    private suspend fun sendBye() = sendDiscovery("BYE:$localPeerId:$localIp")

    private suspend fun sendDiscovery(msg: String, targetIp: String? = null) {
        val data = msg.toByteArray()
        withContext(Dispatchers.IO) {
            runCatching {
                val sock = discoverySocket ?: return@runCatching
                if (targetIp != null) {
                    sock.send(DatagramPacket(data, data.size, InetAddress.getByName(targetIp), DISCOVERY_PORT))
                } else {
                    sock.send(DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))
                    val subnetBroadcast = localIp.substringBeforeLast(".") + ".255"
                    sock.send(DatagramPacket(data, data.size, InetAddress.getByName(subnetBroadcast), DISCOVERY_PORT))
                }
            }
        }
    }

    private fun startHeartbeat() {
        scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                sendDiscovery("HEARTBEAT:$localPeerId:$localIp")
                pruneStalePeers()
            }
        }
    }

    private fun pruneStalePeers() {
        val now = System.currentTimeMillis()
        val stale = peers.filterKeys { now - peers.getValue(it).lastSeen > PEER_TIMEOUT_MS }.keys
        stale.forEach {
            peers.remove(it)
            onPeerDisconnected(it)
        }
    }

    // ------------------------------------------------------------------ Audio track

    private fun initAudioTrack() {
        runCatching {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(channelConfigOut)
                        .setEncoding(pcmFormat)
                        .build()
                )
                .setBufferSizeInBytes(audioTrackBufSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }
    }

    private fun startAudioReceiver() {
        scope.launch {
            while (isActive) {
                try {
                    audioSocket = DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        bind(InetSocketAddress(AUDIO_PORT))
                    }
                    receiveAudioLoop()
                } catch (_: Exception) {
                    delay(1000)
                }
            }
        }
    }

    private suspend fun receiveAudioLoop() {
        val buf = ByteArray(audioRecordBufSize)
        val pkt = DatagramPacket(buf, buf.size)
        runCatching { audioTrack?.play() }
        isTrackPlaying.set(true)
        while (isActive) {
            runCatching { audioSocket?.receive(pkt) }.getOrNull() ?: break
            if (!isTrackPlaying.get()) continue
            val data = pkt.data.copyOfRange(0, pkt.length)
            runCatching {
                if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                    audioTrack?.write(data, 0, data.size)
                }
            }
        }
    }

    // ------------------------------------------------------------------ PTT

    fun startPtt() {
        if (!isRecording.compareAndSet(false, true)) return
        scope.launch {
            runCatching {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    channelConfigIn,
                    pcmFormat,
                    audioRecordBufSize * 2
                )
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    audioRecord?.release(); audioRecord = null
                    isRecording.set(false); return@launch
                }
                audioRecord?.startRecording()
                onPttStateChanged?.invoke(true)

                val buf = ByteArray(audioRecordBufSize)
                while (isRecording.get()) {
                    val n = audioRecord?.read(buf, 0, buf.size) ?: -1
                    if (n > 0) sendAudio(buf, n)
                }
            }
            runCatching { audioRecord?.stop() }
            runCatching { audioRecord?.release() }
            audioRecord = null
            isRecording.set(false)
            onPttStateChanged?.invoke(false)
        }
    }

    fun stopPtt() {
        isRecording.set(false)
    }

    private fun sendAudio(data: ByteArray, length: Int) {
        runCatching {
            val sock = audioSocket ?: return
            val pkt = DatagramPacket(data, length, InetAddress.getByName("255.255.255.255"), AUDIO_PORT)
            sock.send(pkt)
            val subnetBroadcast = localIp.substringBeforeLast(".") + ".255"
            val pkt2 = DatagramPacket(data, length, InetAddress.getByName(subnetBroadcast), AUDIO_PORT)
            sock.send(pkt2)
        }
    }

    private fun stopRecording() {
        isRecording.set(false)
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }

    // Compat stubs
    fun getLocalDescription(remotePeerId: String, callback: (String) -> Unit) {}
    fun handleRemoteAnswer(remotePeerId: String, sdp: String) {}
    fun handleRemoteIceCandidate(remotePeerId: String, sdpMid: String, sdpMLineIndex: Int, candidate: String) {}
    fun sendToAll(message: String) {}
    fun getPendingCandidates(peerId: String): List<String> = emptyList()
    fun clearPendingCandidates(peerId: String) {}

    fun disconnect() = stop()
}
