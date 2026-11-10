package com.meshtalk

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
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), Mesh.Listener {
    private val main = Handler(Looper.getMainLooper())
    private var svc: CallService? = null
    private var bound = false
    private var pending: Triple<String, String, String>? = null
    private lateinit var form: LinearLayout
    private lateinit var call: LinearLayout
    private lateinit var peerList: LinearLayout
    private lateinit var status: TextView
    private lateinit var muteBtn: Button
    private lateinit var urlIn: EditText
    private lateinit var nameIn: EditText
    private lateinit var roomIn: EditText
    private val rows = HashMap<String, Pair<LinearLayout, TextView>>()
    private var speaker = true

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            val s = (b as CallService.LocalBinder).service
            svc = s
            s.mesh.ui = this@MainActivity
            val p = pending
            if (p != null) {
                pending = null
                s.startCall(p.first, p.second, p.third, speaker)
                showCall()
            } else if (s.mesh.active) { // Activity dibuka lagi saat panggilan masih berjalan
                showCall()
                onPeers(s.mesh.snapshot())
                muteBtn.text = if (s.mesh.muted) "Nyalakan mikrofon" else "Matikan mikrofon"
            }
        }
        override fun onServiceDisconnected(n: ComponentName?) { svc = null }
    }

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startJoin()
        else status.text = "Izin mikrofon diperlukan."
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
    private fun field(hint: String, value: String) =
        EditText(this).apply { this.hint = hint; setText(value); setSingleLine() }
    private fun button(label: String, click: (Button) -> Unit) =
        Button(this).apply { text = label; setOnClickListener { click(this) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("mt", MODE_PRIVATE)

        urlIn = field("Alamat server", prefs.getString("url", "http://10.0.2.2:3000")!!)
        nameIn = field("Nama perangkat", prefs.getString("name", Build.MODEL)!!)
        roomIn = field("Ruang", "utama")
        status = TextView(this).apply { setTextColor(0xFFB42318.toInt()) }
        form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(urlIn); addView(nameIn); addView(roomIn)
            addView(button("Gabung ke ruang") {
                prefs.edit().putString("url", urlIn.text.toString()).putString("name", nameIn.text.toString()).apply()
                askAndJoin()
            })
            addView(status)
        }

        muteBtn = button("Matikan mikrofon") { b ->
            val m = !(svc?.mesh?.muted ?: false)
            svc?.mesh?.setMuted(m)
            b.text = if (m) "Nyalakan mikrofon" else "Matikan mikrofon"
        }
        peerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        call = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            addView(peerList)
            addView(muteBtn)
            addView(button("Speaker: nyala") { b ->
                speaker = !speaker; svc?.setSpeaker(speaker)
                b.text = if (speaker) "Speaker: nyala" else "Speaker: mati"
            })
            addView(button("Keluar") { svc?.stopCall() ?: resetUi() })
        }

        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20.dp(), 32.dp(), 20.dp(), 20.dp())
                addView(TextView(this@MainActivity).apply { text = "MeshTalk"; textSize = 26f })
                addView(form); addView(call)
            })
        })
    }

    override fun onStart() {
        super.onStart()
        if (CallService.running && !bound) {
            bound = bindService(Intent(this, CallService::class.java), conn, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        if (bound) {
            svc?.mesh?.ui = null
            unbindService(conn)
            bound = false; svc = null
        }
        super.onStop()
    }

    private fun askAndJoin() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isEmpty()) startJoin() else perms.launch(need.toTypedArray())
    }

    private fun startJoin() {
        status.text = ""
        val args = Triple(
            urlIn.text.toString(),
            roomIn.text.toString().ifBlank { "utama" },
            nameIn.text.toString().ifBlank { "Perangkat" }
        )
        // Mulai sebagai foreground service selagi aplikasi terlihat (syarat Android 14+ untuk mikrofon).
        ContextCompat.startForegroundService(this, Intent(this, CallService::class.java))
        val s = svc
        if (s != null) {
            s.startCall(args.first, args.second, args.third, speaker)
            showCall()
        } else {
            pending = args
            bound = bindService(Intent(this, CallService::class.java), conn, Context.BIND_AUTO_CREATE)
        }
    }

    private fun showCall() { form.visibility = View.GONE; call.visibility = View.VISIBLE }

    private fun resetUi() {
        rows.clear(); peerList.removeAllViews()
        muteBtn.text = "Matikan mikrofon"
        call.visibility = View.GONE; form.visibility = View.VISIBLE
    }

    override fun onPeers(list: List<Mesh.PeerInfo>) {
        main.post {
            val ids = list.map { it.id }.toSet()
            rows.keys.filter { it !in ids }.forEach { rows.remove(it)?.let { r -> peerList.removeView(r.first) } }
            list.forEach { p ->
                val row = rows.getOrPut(p.id) {
                    val title = TextView(this).apply { textSize = 16f }
                    val bar = SeekBar(this).apply {
                        max = 200; progress = (p.volume * 100).toInt() // 100 = volume normal
                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(s: SeekBar?, v: Int, user: Boolean) {
                                if (user) svc?.mesh?.setVolume(p.id, v / 100.0)
                            }
                            override fun onStartTrackingTouch(s: SeekBar?) {}
                            override fun onStopTrackingTouch(s: SeekBar?) {}
                        })
                    }
                    val box = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(0, 12.dp(), 0, 12.dp())
                        addView(title); addView(bar)
                    }
                    peerList.addView(box)
                    box to title
                }
                row.second.text = "${p.name} — ${p.state}"
            }
        }
    }

    override fun onError(msg: String) { main.post { status.text = msg } }
    override fun onEnded() { main.post { resetUi() } }
}
