package com.example.wifiwalkie

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

@SuppressLint("MissingPermission", "SetTextI18n", "ClickableViewAccessibility")
class MainActivity : AppCompatActivity() {

    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var statusText: TextView
    private lateinit var listView: ListView
    private lateinit var talkButton: Button

    private val peers = mutableListOf<WifiP2pDevice>()
    private lateinit var adapter: ArrayAdapter<String>

    @Volatile private var socket: Socket? = null
    @Volatile private var talking = false
    @Volatile private var running = true

    private val sampleRate = 16000
    private val inCfg = AudioFormat.CHANNEL_IN_MONO
    private val outCfg = AudioFormat.CHANNEL_OUT_MONO
    private val enc = AudioFormat.ENCODING_PCM_16BIT
    private val port = 8888

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    manager.requestPeers(channel) { list ->
                        peers.clear()
                        peers.addAll(list.deviceList)
                        adapter.clear()
                        adapter.addAll(peers.map { it.deviceName })
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    manager.requestConnectionInfo(channel) { info -> onConnection(info) }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(32, 32, 32, 32)

        statusText = TextView(this)
        statusText.text = "Tap Discover, then pick a device"
        statusText.textSize = 16f

        val discoverBtn = Button(this)
        discoverBtn.text = "Discover"

        listView = ListView(this)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        listView.adapter = adapter

        talkButton = Button(this)
        talkButton.text = "Hold to Talk"
        talkButton.isEnabled = false

        root.addView(statusText)
        root.addView(discoverBtn)
        root.addView(listView, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(talkButton, LinearLayout.LayoutParams(-1, 300))
        setContentView(root)

        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)

        requestPerms()

        discoverBtn.setOnClickListener {
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { statusText.text = "Searching..." }
                override fun onFailure(r: Int) { statusText.text = "Discover failed ($r). Turn on Wi-Fi & Location." }
            })
        }

        listView.setOnItemClickListener { _, _, pos, _ ->
            val cfg = WifiP2pConfig()
            cfg.deviceAddress = peers[pos].deviceAddress
            manager.connect(channel, cfg, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { statusText.text = "Connecting..." }
                override fun onFailure(r: Int) { statusText.text = "Connect failed ($r)" }
            })
        }

        talkButton.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { talking = true; true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { talking = false; true }
                else -> false
            }
        }
    }

    private fun requestPerms() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        val need = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), 1)
    }

    private fun onConnection(info: WifiP2pInfo) {
        if (!info.groupFormed || socket != null) return
        statusText.text = if (info.isGroupOwner) "Connected (waiting for peer)" else "Connected"
        Thread {
            try {
                val s: Socket
                if (info.isGroupOwner) {
                    val server = ServerSocket(port)
                    s = server.accept()
                    server.close()
                } else {
                    s = Socket()
                    var ok = false
                    var tries = 0
                    while (!ok && tries < 15) {
                        try {
                            s.connect(InetSocketAddress(info.groupOwnerAddress, port), 3000)
                            ok = true
                        } catch (e: Exception) {
                            tries++
                            Thread.sleep(500)
                        }
                    }
                    if (!ok) throw Exception("connect failed")
                }
                socket = s
                runOnUiThread {
                    statusText.text = "Ready! Hold the button to talk"
                    talkButton.isEnabled = true
                }
                startSender(s)
                startReceiver(s)
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "Socket error: ${e.message}" }
            }
        }.start()
    }

    private fun startSender(s: Socket) {
        Thread {
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, inCfg, enc)
            val rec = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, inCfg, enc, minBuf)
            val buf = ByteArray(minBuf)
            try {
                val out = s.getOutputStream()
                rec.startRecording()
                while (running) {
                    if (talking) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n > 0) out.write(buf, 0, n)
                    } else {
                        Thread.sleep(20)
                    }
                }
            } catch (e: Exception) {
            } finally {
                try { rec.stop(); rec.release() } catch (e: Exception) {}
            }
        }.start()
    }

    private fun startReceiver(s: Socket) {
        Thread {
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, outCfg, enc)
            val track = AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, outCfg, enc, minBuf, AudioTrack.MODE_STREAM)
            val buf = ByteArray(minBuf)
            try {
                val inp = s.getInputStream()
                track.play()
                while (running) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    track.write(buf, 0, n)
                }
            } catch (e: Exception) {
            } finally {
                try { track.stop(); track.release() } catch (e: Exception) {}
                socket = null
                runOnUiThread {
                    statusText.text = "Disconnected"
                    talkButton.isEnabled = false
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        val f = IntentFilter()
        f.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, f)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        try { socket?.close() } catch (e: Exception) {}
    }
}
