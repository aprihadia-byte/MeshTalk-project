package com.meshtalk

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.ConcurrentHashMap

open class SdpAdapter : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}

open class PcAdapter : PeerConnection.Observer {
    override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
    override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
    override fun onIceConnectionReceivingChange(b: Boolean) {}
    override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
    override fun onIceCandidate(c: IceCandidate) {}
    override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
    override fun onAddStream(s: MediaStream?) {}
    override fun onRemoveStream(s: MediaStream?) {}
    override fun onDataChannel(d: DataChannel?) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
}

/** Mesh penuh WebRTC. Protokol signaling sama dengan klien web (server.js). */
class Mesh(private val ctx: Context) {
    @Volatile var ui: Listener? = null
    var onDead: (() -> Unit)? = null          // dipanggil bila koneksi server gagal (service ikut berhenti)
    @Volatile var active = false
        private set
    var muted = false
        private set

    interface Listener {
        fun onPeers(list: List<PeerInfo>)
        fun onError(msg: String)
        fun onEnded()
    }
    data class PeerInfo(val id: String, val name: String, val state: String, val volume: Double)

    private class Peer(val name: String) {
        lateinit var pc: PeerConnection
        var state = "menghubungkan…"
        var remote: AudioTrack? = null
        var volume = 1.0
    }

    private val http = OkHttpClient()
    private val peers = ConcurrentHashMap<String, Peer>()
    private var ws: WebSocket? = null
    private var ice = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
    private var factory: PeerConnectionFactory? = null
    private var source: AudioSource? = null
    private var local: AudioTrack? = null

    /** baseUrl contoh: http://10.0.2.2:3000 (emulator) atau https://domainmu.com */
    fun join(baseUrl: String, room: String, name: String) {
        val base = baseUrl.trim().trimEnd('/')
        active = true
        Thread {
            try {
                loadConfig(base)
                setupAudio()
                val req = Request.Builder().url(base.replaceFirst("http", "ws")).build()
                ws = http.newWebSocket(req, object : WebSocketListener() {
                    override fun onOpen(w: WebSocket, r: Response) {
                        w.send(JSONObject().put("type", "join").put("room", room).put("name", name).toString())
                    }
                    override fun onMessage(w: WebSocket, text: String) = handle(JSONObject(text))
                    override fun onFailure(w: WebSocket, t: Throwable, r: Response?) {
                        fail("Koneksi ke server gagal: ${t.message}")
                    }
                })
            } catch (e: Exception) {
                fail("Tidak bisa terhubung: ${e.message}")
            }
        }.start()
    }

    private fun loadConfig(base: String) {
        http.newCall(Request.Builder().url("$base/config").build()).execute().use { r ->
            val arr = JSONObject(r.body!!.string()).getJSONArray("iceServers")
            ice = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val b = PeerConnection.IceServer.builder(o.getString("urls"))
                if (o.has("username")) b.setUsername(o.getString("username")).setPassword(o.getString("credential"))
                b.createIceServer()
            }
        }
    }

    private fun setupAudio() {
        if (factory == null) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions()
            )
            factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        }
        val c = MediaConstraints()
        listOf("googEchoCancellation", "googNoiseSuppression", "googAutoGainControl")
            .forEach { c.mandatory.add(MediaConstraints.KeyValuePair(it, "true")) }
        source = factory!!.createAudioSource(c)
        local = factory!!.createAudioTrack("a0", source)
    }

    private fun handle(m: JSONObject) {
        when (m.getString("type")) {
            "welcome" -> {
                val a = m.getJSONArray("peers")
                for (i in 0 until a.length()) {
                    val p = a.getJSONObject(i)
                    makePeer(p.getString("id"), p.getString("name"), true) // pendatang baru memulai offer
                }
            }
            "peer-joined" -> makePeer(m.getString("id"), m.getString("name"), false)
            "peer-left" -> drop(m.getString("id"))
            "signal" -> signal(m.getString("from"), m.getJSONObject("data"))
        }
    }

    private fun makePeer(id: String, name: String, initiator: Boolean): Peer {
        peers[id]?.let { return it }
        val p = Peer(name)
        peers[id] = p
        val cfg = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        p.pc = factory!!.createPeerConnection(cfg, object : PcAdapter() {
            override fun onIceCandidate(c: IceCandidate) {
                sig(id, JSONObject().put("ice", JSONObject()
                    .put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex)))
            }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {
                p.state = when (s) {
                    PeerConnection.PeerConnectionState.CONNECTED -> "tersambung"
                    PeerConnection.PeerConnectionState.FAILED -> "gagal (coba TURN)"
                    PeerConnection.PeerConnectionState.DISCONNECTED -> "terputus"
                    else -> "menghubungkan…"
                }
                notifyPeers()
            }
            override fun onTrack(t: RtpTransceiver) {
                (t.receiver.track() as? AudioTrack)?.let { p.remote = it; it.setVolume(p.volume) }
            }
        })!!
        p.pc.addTrack(local, listOf("s0"))
        if (initiator) {
            p.pc.createOffer(object : SdpAdapter() {
                override fun onCreateSuccess(d: SessionDescription) {
                    p.pc.setLocalDescription(SdpAdapter(), d)
                    sig(id, JSONObject().put("sdp", JSONObject().put("type", "offer").put("sdp", d.description)))
                }
            }, MediaConstraints())
        }
        notifyPeers()
        return p
    }

    private fun signal(from: String, d: JSONObject) {
        val p = peers[from] ?: makePeer(from, "Perangkat", false)
        if (d.has("sdp")) {
            val s = d.getJSONObject("sdp")
            val desc = SessionDescription(SessionDescription.Type.fromCanonicalForm(s.getString("type")), s.getString("sdp"))
            p.pc.setRemoteDescription(object : SdpAdapter() {
                override fun onSetSuccess() {
                    if (desc.type != SessionDescription.Type.OFFER) return
                    p.pc.createAnswer(object : SdpAdapter() {
                        override fun onCreateSuccess(a: SessionDescription) {
                            p.pc.setLocalDescription(SdpAdapter(), a)
                            sig(from, JSONObject().put("sdp", JSONObject().put("type", "answer").put("sdp", a.description)))
                        }
                    }, MediaConstraints())
                }
            }, desc)
        } else if (d.has("ice")) {
            val i = d.getJSONObject("ice")
            p.pc.addIceCandidate(IceCandidate(i.optString("sdpMid"), i.optInt("sdpMLineIndex"), i.getString("candidate")))
        }
    }

    private fun sig(to: String, data: JSONObject) {
        ws?.send(JSONObject().put("type", "signal").put("to", to).put("data", data).toString())
    }

    private fun drop(id: String) {
        peers.remove(id)?.let { it.pc.close(); it.pc.dispose() }
        notifyPeers()
    }

    fun snapshot() = peers.map { (id, p) -> PeerInfo(id, p.name, p.state, p.volume) }

    private fun notifyPeers() { ui?.onPeers(snapshot()) }

    private fun fail(msg: String) {
        ui?.onError(msg)
        onDead?.invoke()
    }

    /** Volume per perangkat: 0.0 (senyap) sampai 2.0 (dua kali lipat). */
    fun setVolume(id: String, v: Double) {
        peers[id]?.let { it.volume = v; it.remote?.setVolume(v) }
    }

    fun setMuted(m: Boolean) { muted = m; local?.setEnabled(!m) }

    fun leave() {
        ws?.close(1000, null); ws = null
        peers.values.forEach { it.pc.close(); it.pc.dispose() }
        peers.clear()
        local?.dispose(); source?.dispose(); local = null; source = null
        active = false; muted = false
        ui?.onEnded()
    }
}
