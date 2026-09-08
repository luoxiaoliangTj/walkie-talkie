package com.example.walkie

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONObject
import org.webrtc.*

/**
 * Stub for PeerConnector - WebRTC dependency not available in CI.
 * Replace with actual WebRTC implementation when building locally.
 */
class PeerConnector(
    private val context: Context,
    private val onMessageReceived: (String) -> Unit,
    private val onPeerConnected: (String) -> Unit,
    private val onPeerDisconnected: (String) -> Unit
) {
    fun getLocalDescription(remotePeerId: String, callback: (SessionDescription) -> Unit) {}
    fun handleRemoteAnswer(remotePeerId: String, sdp: String) {}
    fun handleRemoteIceCandidate(remotePeerId: String, sdpMid: String, sdpMLineIndex: Int, candidate: String) {}
    fun sendToAll(message: String) {}
    fun disconnect() {}
    fun getPendingCandidates(peerId: String): List<IceCandidate> = emptyList()
    fun clearPendingCandidates(peerId: String) {}
}
