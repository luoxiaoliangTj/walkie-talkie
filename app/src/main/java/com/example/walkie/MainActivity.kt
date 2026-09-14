package com.example.walkie

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var peerConnector: PeerConnector
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var peerIdText: TextView
    private lateinit var btnPtt: Button
    private lateinit var pttStatusText: TextView

    private val connectedPeers = mutableSetOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isPttActive = false
    private var walkieService: WalkieService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)
        peerIdText = findViewById(R.id.peerIdText)
        btnPtt = findViewById(R.id.btnPtt)
        pttStatusText = findViewById(R.id.pttStatusText)

        peerIdText.text = ""

        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 200)
        } else {
            startPeerConnector()
        }
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(rc, perms, grants)
        if (rc == 200) {
            val micOk = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (micOk) {
                startPeerConnector()
            } else {
                Toast.makeText(this, "需要麦克风权限才能对讲", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startPeerConnector() {
        peerConnector = PeerConnector(
            context = this,
            onMessageReceived = { /* text messages removed */ },
            onPeerConnected = { peerId ->
                mainHandler.post {
                    connectedPeers.add(peerId)
                    updateStatus()
                }
            },
            onPeerDisconnected = { peerId ->
                mainHandler.post {
                    connectedPeers.remove(peerId)
                    updateStatus()
                }
            }
        )
        peerIdText.text = "ID: ${peerConnector.localPeerId}"
        peerConnector.onPttStateChanged = { active ->
            mainHandler.post { updatePttUI(active) }
        }
        peerConnector.start()
        bindService()
        updateStatus()
        setupPtt()
    }

    private fun setupPtt() {
        btnPtt.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isPttActive) {
                        isPttActive = true
                        peerConnector.startPtt()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isPttActive) {
                        isPttActive = false
                        peerConnector.stopPtt()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun updatePttUI(active: Boolean) {
        if (active) {
            btnPtt.text = "🔴 松开停止"
            btnPtt.setBackgroundColor(0xFFCC0000.toInt())
            pttStatusText.text = "正在广播..."
            pttStatusText.setTextColor(0xFF00FF00.toInt())
        } else {
            btnPtt.text = "按住说话"
            btnPtt.setBackgroundColor(0xFF6200EE.toInt())
            pttStatusText.text = "按住说话"
            pttStatusText.setTextColor(0xFFAAAAAA.toInt())
        }
    }

    private fun updateStatus() {
        val count = connectedPeers.size
        if (count == 0) {
            statusText.text = "未连接 — 等待附近设备..."
            statusDot.setBackgroundColor(0xFFFF0000.toInt())
            btnPtt.isEnabled = true
            btnPtt.alpha = 0.5f
        } else {
            statusText.text = "已连接 $count 个设备"
            statusDot.setBackgroundColor(0xFF00FF00.toInt())
            btnPtt.isEnabled = true
            btnPtt.alpha = 1.0f
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
        runCatching { unbindService(serviceConnection) }
    }
}
