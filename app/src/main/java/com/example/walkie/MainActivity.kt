package com.example.walkie

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import org.webrtc.SessionDescription
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var peerConnector: PeerConnector
    private lateinit var messagesList: RecyclerView
    private lateinit var inputMessage: EditText
    private lateinit var btnSend: Button
    private lateinit var btnShowQR: Button
    private lateinit var btnScanQR: Button
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    
    private val messages = mutableListOf<WalkieMessage.Broadcast>()
    private val connectedPeers = mutableSetOf<String>()
    private var myPeerId: String = UUID.randomUUID().toString().take(8)
    private var walkieService: WalkieService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initViews()
        initPeerConnector()
        bindService()
    }

    private fun initViews() {
        messagesList = findViewById(R.id.messagesList)
        inputMessage = findViewById(R.id.inputMessage)
        btnSend = findViewById(R.id.btnSend)
        btnShowQR = findViewById(R.id.btnShowQR)
        btnScanQR = findViewById(R.id.btnScanQR)
        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)

        btnSend.setOnClickListener { sendMessage() }
        btnShowQR.setOnClickListener { showMyQR() }
        btnScanQR.setOnClickListener { startScan() }
        
        inputMessage.setOnEditorActionListener { _, _, _ ->
            sendMessage()
            true
        }
    }

    private fun initPeerConnector() {
        peerConnector = PeerConnector(
            context = this,
            onMessageReceived = { json -> handleMessage(json) },
            onPeerConnected = { peerId ->
                runOnUiThread {
                    connectedPeers.add(peerId)
                    updateStatus()
                }
            },
            onPeerDisconnected = { peerId ->
                runOnUiThread {
                    connectedPeers.remove(peerId)
                    updateStatus()
                }
            }
        )
    }

    private fun handleMessage(json: String) {
        val message = WalkieMessage.fromJson(json) ?: return
        when (message) {
            is WalkieMessage.Broadcast -> {
                runOnUiThread {
                    messages.add(message)
                    // Update UI
                }
            }
            is WalkieMessage.Join -> {
                runOnUiThread {
                    connectedPeers.add(message.peerId)
                    updateStatus()
                }
            }
            is WalkieMessage.Leave -> {
                runOnUiThread {
                    connectedPeers.remove(message.peerId)
                    updateStatus()
                }
            }
        }
    }

    private fun sendMessage() {
        val text = inputMessage.text.toString().trim()
        if (text.isEmpty()) return
        
        val message = WalkieMessage.Broadcast(
            id = UUID.randomUUID().toString(),
            senderId = myPeerId,
            content = text
        )
        
        peerConnector.sendToAll(message.toJson())
        inputMessage.setText("")
        
        // Add to own display
        messages.add(message)
    }

    private fun showMyQR() {
        // Generate QR with my peer info
        val qrData = JSONObject().apply {
            put("peerId", myPeerId)
            put("offer", "") // Will be filled when connecting
        }.toString()
        
        // Show QR dialog
        // TODO: implement QR generation
        Toast.makeText(this, "我的ID: $myPeerId", Toast.LENGTH_LONG).show()
    }

    private fun startScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
            return
        }
        // TODO: implement QR scanning
        Toast.makeText(this, "扫码功能开发中...", Toast.LENGTH_SHORT).show()
    }

    private fun updateStatus() {
        val count = connectedPeers.size
        if (count == 0) {
            statusText.text = "未连接"
            statusDot.setBackgroundColor(0xFFFF0000.toInt())
        } else {
            statusText.text = "已连接 $count 人"
            statusDot.setBackgroundColor(0xFF00FF00.toInt())
        }
    }

    private fun bindService() {
        val intent = Intent(this, WalkieService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WalkieService.WalkieBinder
            walkieService = binder.getService()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            walkieService = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        peerConnector.disconnect()
        unbindService(serviceConnection)
    }
}
