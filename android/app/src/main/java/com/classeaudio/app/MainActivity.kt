package com.classeaudio.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : ComponentActivity() {
    private lateinit var hostField: EditText
    private lateinit var nameField: EditText
    private lateinit var roleSpinner: Spinner
    private lateinit var joinCodeField: EditText
    private lateinit var teacherPinField: EditText
    private lateinit var statusText: TextView
    private lateinit var detailsText: TextView
    private lateinit var routeText: TextView
    private lateinit var participantText: TextView
    private lateinit var teacherPanel: LinearLayout
    private lateinit var requestList: LinearLayout
    private lateinit var joinButton: Button
    private lateinit var micButton: Button
    private lateinit var requestButton: Button
    private lateinit var disconnectButton: Button

    private var room: Room? = null
    private var roomEventsJob: Job? = null
    private var teacherPollJob: Job? = null
    private var localRole: String? = null
    private var apiBase: String? = null
    private var controlToken: String? = null
    private var localIdentity: String? = null
    private var activeSpeakerIdentity: String? = null
    private var studentCanPublish = false
    private var microphoneEnabled = false
    private var lastTeacherSnapshot: JSONObject? = null
    private var pendingPermissionAction: ((Map<String, Boolean>) -> Unit)? = null

    private lateinit var nsdManager: NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var resolvingService = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val action = pendingPermissionAction
        pendingPermissionAction = null
        action?.invoke(results)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        buildUi()
        setStatus("Prêt. Rejoignez le Wi-Fi de la salle, puis découvrez le serveur ou saisissez son adresse.")
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(0xFFF3F6F8.toInt())
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        scroll.addView(content)

        content.addView(text("ClasseAudio", 28f, true))
        content.addView(text("Prototype local · professeur et élèves", 14f, false, 0xFF607286.toInt()))
        content.addView(spacer(18))

        content.addView(text("Serveur de la salle", 17f, true))
        hostField = edit("Adresse de secours, ex. http://192.168.1.20:8090", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        content.addView(hostField, matchWrap())
        val discoveryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val discoverButton = button("Découvrir automatiquement")
        discoveryRow.addView(discoverButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        content.addView(discoveryRow)
        discoverButton.setOnClickListener { requestDiscoveryPermissionThenStart() }
        content.addView(spacer(12))

        content.addView(text("Votre rôle", 17f, true))
        roleSpinner = Spinner(this)
        roleSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Élève", "Professeur")
        )
        content.addView(roleSpinner, matchWrap())
        nameField = edit("Nom affiché (prénom ou pseudonyme)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        content.addView(nameField, matchWrap())
        joinCodeField = edit("Code de classe à 6 chiffres", InputType.TYPE_CLASS_NUMBER)
        content.addView(joinCodeField, matchWrap())
        teacherPinField = edit("PIN professeur — réservé au professeur", InputType.TYPE_CLASS_NUMBER)
        content.addView(teacherPinField, matchWrap())
        roleSpinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
            teacherPinField.visibility = if (position == 1) View.VISIBLE else View.GONE
        }
        teacherPinField.visibility = View.GONE

        joinButton = button("Rejoindre le salon")
        content.addView(joinButton, matchWrap())
        joinButton.setOnClickListener { onJoinPressed() }

        statusText = text("", 14f, false, 0xFF24364B.toInt())
        statusText.setPadding(0, dp(12), 0, dp(8))
        content.addView(statusText)
        detailsText = text("", 13f, false, 0xFF607286.toInt())
        content.addView(detailsText)
        routeText = text("Routage audio : non connecté", 13f, true, 0xFF176B5B.toInt())
        routeText.setPadding(0, dp(6), 0, dp(6))
        content.addView(routeText)
        participantText = text("", 13f, false, 0xFF607286.toInt())
        content.addView(participantText)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        requestButton = button("Demander la parole")
        micButton = button("Activer le microphone")
        disconnectButton = button("Quitter le salon")
        controls.addView(requestButton, matchWrap())
        controls.addView(micButton, matchWrap())
        controls.addView(disconnectButton, matchWrap())
        content.addView(controls)
        requestButton.visibility = View.GONE
        micButton.visibility = View.GONE
        disconnectButton.visibility = View.GONE
        requestButton.setOnClickListener { sendSpeakingRequest() }
        micButton.setOnClickListener { toggleMicrophone() }
        disconnectButton.setOnClickListener { disconnectFromRoom() }

        teacherPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        teacherPanel.addView(text("Demandes de parole", 17f, true))
        requestList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        teacherPanel.addView(requestList)
        content.addView(teacherPanel, matchWrap())
        teacherPanel.visibility = View.GONE

        content.addView(spacer(16))
        content.addView(text("Gardez l'application au premier plan pendant ce premier essai. Le routage Bluetooth et l'écho doivent être testés sur les téléphones réels.", 12f, false, 0xFF607286.toInt()))

        setContentView(scroll)
    }

    private fun onJoinPressed() {
        val role = if (roleSpinner.selectedItemPosition == 1) "teacher" else "student"
        val displayName = nameField.text.toString().trim()
        val joinCode = joinCodeField.text.toString().trim()
        val teacherPin = teacherPinField.text.toString().trim()
        val base = normalizeApiBase(hostField.text.toString())

        if (base == null) {
            setStatus("Entrez l'adresse découverte ou l'adresse manuelle du PC (port 8090).")
            return
        }
        if (joinCode.length != 6) {
            setStatus("Le code de classe doit contenir 6 chiffres.")
            return
        }
        if (role == "teacher" && teacherPin.length != 6) {
            setStatus("Le PIN professeur doit contenir 6 chiffres.")
            return
        }

        val beginJoin = {
            lifecycleScope.launch {
                joinButton.isEnabled = false
                setStatus("Demande de jeton local…")
                try {
                    val body = JSONObject()
                        .put("displayName", displayName.ifBlank { if (role == "teacher") "Professeur" else "Élève" })
                        .put("role", role)
                        .put("joinCode", joinCode)
                    if (role == "teacher") body.put("teacherPin", teacherPin)

                    val response = requestJson("POST", "$base/api/v1/join", body)
                    val liveKitUrl = response.getString("liveKitUrl")
                    val roomName = response.getString("room")
                    val identity = response.getString("identity")
                    val accessToken = response.getString("accessToken")
                    val issuedControlToken = response.getString("controlToken")

                    apiBase = base
                    controlToken = issuedControlToken
                    localIdentity = identity
                    localRole = role
                    activeSpeakerIdentity = null
                    studentCanPublish = role == "teacher"
                    microphoneEnabled = false

                    val audioSwitch = AudioSwitchHandler(applicationContext).apply {
                        loggingEnabled = true
                        preferredDeviceList = listOf(
                            AudioDevice.BluetoothHeadset::class.java,
                            AudioDevice.WiredHeadset::class.java,
                            AudioDevice.Speakerphone::class.java,
                            AudioDevice.Earpiece::class.java
                        )
                    }
                    val newRoom = LiveKit.create(
                        appContext = applicationContext,
                        overrides = LiveKitOverrides(audioOptions = AudioOptions(audioHandler = audioSwitch))
                    )
                    room = newRoom
                    roomEventsJob?.cancel()
                    roomEventsJob = lifecycleScope.launch {
                        newRoom.events.collect { event -> handleRoomEvent(event) }
                    }

                    setStatus("Connexion au relais audio local…")
                    newRoom.connect(liveKitUrl, accessToken)
                    if (role == "teacher") {
                        newRoom.localParticipant.setMicrophoneEnabled(true)
                        microphoneEnabled = true
                    }

                    detailsText.text = "Salon : $roomName\nIdentité temporaire : $identity\nAucune piste audio n'est enregistrée."
                    joinButton.visibility = View.GONE
                    requestButton.visibility = if (role == "student") View.VISIBLE else View.GONE
                    micButton.visibility = View.VISIBLE
                    disconnectButton.visibility = View.VISIBLE
                    teacherPanel.visibility = if (role == "teacher") View.VISIBLE else View.GONE
                    updateMicButton()
                    refreshAudioRoute()
                    setStatus(if (role == "teacher") "Connecté comme professeur. Votre micro est publié aux abonnés." else "Connecté comme élève. Le son distant est lu automatiquement; votre micro reste refusé.")

                    if (role == "teacher") startTeacherPolling()
                } catch (ex: Exception) {
                    joinButton.isEnabled = true
                    setStatus("Connexion impossible : ${ex.message ?: ex.javaClass.simpleName}")
                    roomEventsJob?.cancel()
                    roomEventsJob = null
                    try { room?.disconnect() } catch (_: Exception) { }
                    room = null
                    localRole = null
                    controlToken = null
                    apiBase = null
                    teacherPanel.visibility = View.GONE
                }
            }
        }

        if (role == "teacher") {
            val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                needed += Manifest.permission.BLUETOOTH_CONNECT
            requestPermissionsThen(needed) { result ->
                if (hasPermission(Manifest.permission.RECORD_AUDIO)) beginJoin()
                else setStatus("Autorisation microphone refusée; le professeur ne peut pas publier sa voix.")
            }
        } else {
            beginJoin()
        }
    }

    private fun handleRoomEvent(event: RoomEvent) {
        when (event) {
            is RoomEvent.ParticipantPermissionsChanged -> {
                if (event.participant === room?.localParticipant && localRole == "student") {
                    val allowed = event.newPermissions?.canPublish == true
                    runOnUiThread {
                        studentCanPublish = allowed
                        updateMicButton()
                        setStatus(if (allowed) "Le professeur vous a autorisé à parler. Appuyez sur le bouton micro lorsque vous êtes prêt." else "Le droit de parole est retiré; votre microphone est bloqué par le serveur.")
                    }
                    if (!allowed && microphoneEnabled) {
                        lifecycleScope.launch {
                            try { room?.localParticipant?.setMicrophoneEnabled(false) } catch (_: Exception) { }
                            microphoneEnabled = false
                            runOnUiThread { updateMicButton() }
                        }
                    }
                }
            }
            else -> Unit
        }
    }

    private fun sendSpeakingRequest() {
        val base = apiBase ?: return
        val token = controlToken ?: return
        lifecycleScope.launch {
            requestButton.isEnabled = false
            try {
                val response = requestJson("POST", "$base/api/v1/speaking/request", null, token)
                setStatus(response.optString("message", "Demande envoyée au professeur."))
            } catch (ex: Exception) {
                setStatus("Demande non envoyée : ${ex.message ?: "erreur réseau"}")
            } finally {
                requestButton.isEnabled = true
            }
        }
    }

    private fun toggleMicrophone() {
        val currentRoom = room ?: return
        val role = localRole ?: return
        if (role == "student" && !studentCanPublish) {
            setStatus("Le serveur n'a pas encore accordé votre droit de parole.")
            return
        }

        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            needed += Manifest.permission.BLUETOOTH_CONNECT
        requestPermissionsThen(needed) {
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                setStatus("Autorisation microphone refusée.")
                return@requestPermissionsThen
            }
            lifecycleScope.launch {
                try {
                    val enable = !microphoneEnabled
                    currentRoom.localParticipant.setMicrophoneEnabled(enable)
                    microphoneEnabled = enable
                    updateMicButton()
                    setStatus(if (enable) "Microphone actif." else "Microphone coupé.")
                } catch (ex: Exception) {
                    setStatus("Erreur microphone : ${ex.message ?: "inconnue"}")
                }
            }
        }
    }

    private fun updateMicButton() {
        val connected = room != null
        micButton.isEnabled = connected && (localRole == "teacher" || studentCanPublish)
        micButton.text = if (microphoneEnabled) "Couper le microphone" else if (localRole == "student" && !studentCanPublish) "En attente de l'autorisation du professeur" else "Activer le microphone"
    }

    private fun startTeacherPolling() {
        teacherPollJob?.cancel()
        teacherPollJob = lifecycleScope.launch {
            while (isActive && room != null && localRole == "teacher") {
                try {
                    val base = apiBase ?: break
                    val token = controlToken ?: break
                    val snapshot = requestJson("GET", "$base/api/v1/participants", null, token)
                    lastTeacherSnapshot = snapshot
                    renderTeacherSnapshot(snapshot)
                    refreshAudioRoute()
                } catch (ex: Exception) {
                    setStatus("État du salon indisponible : ${ex.message ?: "réseau"}")
                }
                delay(2500)
            }
        }
    }

    private fun renderTeacherSnapshot(snapshot: JSONObject) {
        val participants = snapshot.optJSONArray("participants") ?: JSONArray()
        val count = snapshot.optInt("participantCount", participants.length())
        participantText.text = "Téléphones connectés : $count"
        val activeId = snapshot.optString("activeSpeakerIdentity").takeUnless { it.isBlank() || it == "null" }
        activeSpeakerIdentity = activeId
        val activeName = if (activeId == null) null else findParticipantName(participants, activeId)

        requestList.removeAllViews()
        if (activeId != null) {
            requestList.addView(text("Orateur actif : ${activeName ?: activeId}", 14f, true, 0xFF176B5B.toInt()))
            val revoke = button("Retirer la parole")
            revoke.setOnClickListener { changeSpeakerPermission(activeId, allow = false) }
            requestList.addView(revoke, matchWrap())
        }

        val requests = snapshot.optJSONArray("speakingRequests") ?: JSONArray()
        if (requests.length() == 0) {
            requestList.addView(text("Aucune demande en attente.", 13f, false, 0xFF607286.toInt()))
        } else {
            for (index in 0 until requests.length()) {
                val item = requests.optJSONObject(index) ?: continue
                val identity = item.optString("identity")
                val name = item.optString("displayName", identity)
                val allow = button("Autoriser $name à parler")
                allow.isEnabled = activeId == null
                allow.setOnClickListener { changeSpeakerPermission(identity, allow = true) }
                requestList.addView(allow, matchWrap())
            }
        }
    }

    private fun findParticipantName(participants: JSONArray, identity: String): String? {
        for (index in 0 until participants.length()) {
            val item = participants.optJSONObject(index) ?: continue
            if (item.optString("identity") == identity) return item.optString("displayName", identity)
        }
        return null
    }

    private fun changeSpeakerPermission(identity: String, allow: Boolean) {
        val base = apiBase ?: return
        val token = controlToken ?: return
        val encodedIdentity = URLEncoder.encode(identity, Charsets.UTF_8.name())
        lifecycleScope.launch {
            try {
                val action = if (allow) "allow" else "revoke"
                val result = requestJson("POST", "$base/api/v1/speakers/$encodedIdentity/$action", null, token)
                setStatus(result.optString("message", if (allow) "Parole accordée." else "Parole retirée."))
            } catch (ex: Exception) {
                setStatus("Action refusée : ${ex.message ?: "erreur réseau"}")
            }
        }
    }

    private fun disconnectFromRoom() {
        teacherPollJob?.cancel()
        teacherPollJob = null
        roomEventsJob?.cancel()
        roomEventsJob = null
        val oldRoom = room
        room = null
        lifecycleScope.launch {
            try { oldRoom?.disconnect() } catch (_: Exception) { }
        }
        localRole = null
        apiBase = null
        controlToken = null
        localIdentity = null
        activeSpeakerIdentity = null
        studentCanPublish = false
        microphoneEnabled = false
        lastTeacherSnapshot = null
        joinButton.visibility = View.VISIBLE
        joinButton.isEnabled = true
        requestButton.visibility = View.GONE
        micButton.visibility = View.GONE
        disconnectButton.visibility = View.GONE
        teacherPanel.visibility = View.GONE
        participantText.text = ""
        detailsText.text = ""
        routeText.text = "Routage audio : non connecté"
        requestList.removeAllViews()
        setStatus("Vous avez quitté le salon.")
    }

    private suspend fun requestJson(
        method: String,
        url: String,
        body: JSONObject? = null,
        bearerToken: String? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 6000
            readTimeout = 10000
            setRequestProperty("Accept", "application/json")
            if (bearerToken != null) setRequestProperty("Authorization", "Bearer $bearerToken")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }

        try {
            if (body != null) {
                connection.outputStream.use { output -> output.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code ${JSONObject(response).optString("error", response)}")
            if (response.isBlank()) JSONObject() else JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeApiBase(value: String): String? {
        var input = value.trim().trimEnd('/')
        if (input.isBlank()) return null
        if (!input.contains("://")) input = "http://$input"
        return try {
            val uri = Uri.parse(input)
            val scheme = uri.scheme ?: "http"
            val host = uri.host ?: return null
            val port = if (uri.port == -1) 8090 else uri.port
            "$scheme://$host:$port"
        } catch (_: Exception) {
            null
        }
    }

    private fun requestDiscoveryPermissionThenStart() {
        val action = { startDiscovery() }
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.NEARBY_WIFI_DEVICES)) {
            requestPermissionsThen(listOf(Manifest.permission.NEARBY_WIFI_DEVICES)) { result ->
                if (hasPermission(Manifest.permission.NEARBY_WIFI_DEVICES)) action()
                else setStatus("Découverte refusée; saisissez l'adresse IP du PC manuellement.")
            }
        } else action()
    }

    private fun startDiscovery() {
        stopDiscovery()
        resolvingService = false
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("ClasseAudioDiscovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) { }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = runOnUiThread { setStatus("Recherche du service ClasseAudio sur le Wi-Fi local…") }
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                runOnUiThread {
                    setStatus("Découverte indisponible (code $errorCode). Utilisez l'adresse manuelle.")
                    stopDiscovery()
                }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                runOnUiThread { setStatus("Arrêt de la découverte signalé (code $errorCode); l'adresse manuelle reste disponible.") }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val type = serviceInfo.serviceType?.trimEnd('.')?.lowercase().orEmpty()
                if (!type.startsWith("_classeaudio._tcp") || resolvingService) return
                resolvingService = true
                try {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            resolvingService = false
                            runOnUiThread { setStatus("Serveur détecté mais non résolu (code $errorCode); saisissez son IP.") }
                        }
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val address = info.host?.hostAddress
                            if (address.isNullOrBlank()) {
                                resolvingService = false
                                runOnUiThread { setStatus("Adresse du serveur introuvable; utilisez la saisie manuelle.") }
                                return
                            }
                            runOnUiThread {
                                hostField.setText("http://$address:${info.port}")
                                setStatus("Serveur découvert : $address:${info.port}. Entrez le code de classe puis rejoignez.")
                                stopDiscovery()
                            }
                        }
                    })
                } catch (ex: Exception) {
                    resolvingService = false
                    runOnUiThread { setStatus("Résolution mDNS impossible : ${ex.message}. Utilisez l'adresse manuelle.") }
                }
            }
        }
        discoveryListener = listener
        try {
            nsdManager.discoverServices("_classeaudio._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (ex: Exception) {
            discoveryListener = null
            releaseMulticastLock()
            setStatus("Découverte locale indisponible : ${ex.message}. Saisissez l'adresse manuelle.")
        }
    }

    private fun stopDiscovery() {
        val listener = discoveryListener
        discoveryListener = null
        if (listener != null) {
            try { nsdManager.stopServiceDiscovery(listener) } catch (_: Exception) { }
        }
        releaseMulticastLock()
    }

    private fun releaseMulticastLock() {
        try { multicastLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        multicastLock = null
    }

    private fun refreshAudioRoute() {
        val selected = room?.audioSwitchHandler?.selectedAudioDevice?.toString() ?: "sélection automatique"
        routeText.text = "Routage audio : $selected"
    }

    private fun requestPermissionsThen(
        permissions: List<String>,
        callback: (Map<String, Boolean>) -> Unit
    ) {
        val missing = permissions.distinct().filterNot(::hasPermission)
        if (missing.isEmpty()) {
            callback(emptyMap())
        } else {
            pendingPermissionAction = callback
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun setStatus(message: String) {
        if (::statusText.isInitialized) statusText.text = message
    }

    private fun text(value: String, size: Float, bold: Boolean, color: Int = 0xFF16263A.toInt()) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun edit(hintText: String, inputTypeValue: Int) =
        EditText(this).apply {
            hint = hintText
            inputType = inputTypeValue
            textSize = 15f
            setSingleLine(true)
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }

    private fun button(label: String) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
    }

    private fun spacer(heightDp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(8) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun startTeacherPollingPlaceholder() = Unit

    override fun onDestroy() {
        stopDiscovery()
        teacherPollJob?.cancel()
        roomEventsJob?.cancel()
        super.onDestroy()
    }
}

private class SimpleItemSelectedListener(
    private val callback: (Int) -> Unit
) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = callback(position)
    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}
