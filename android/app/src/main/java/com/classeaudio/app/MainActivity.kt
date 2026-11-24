package com.classeaudio.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : ComponentActivity() {
    private lateinit var hostField: EditText
    private lateinit var nameField: EditText
    private lateinit var roleSpinner: Spinner
    private lateinit var joinCodeField: EditText
    private lateinit var teacherPinField: EditText
    private lateinit var teacherPinLabel: TextView
    private lateinit var statusText: TextView
    private lateinit var serverStatusText: TextView
    private lateinit var detailsText: TextView
    private lateinit var routeText: TextView
    private lateinit var participantText: TextView
    private lateinit var teacherPanel: LinearLayout
    private lateinit var requestList: LinearLayout
    private lateinit var joinButton: Button
    private lateinit var discoverButton: Button
    private lateinit var micButton: Button
    private lateinit var requestButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var advancedToggle: TextView
    private lateinit var advancedPanel: LinearLayout

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
    private var discoveredApiBase: String? = null
    private var pendingJoinAction: ((String) -> Unit)? = null
    private var fallbackDiscoveryJob: Job? = null
    private var subnetScanJob: Job? = null
    private var manualPanelExpanded = false
    @Volatile private var discoveryGeneration = 0

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
        setStatus("Prêt. Saisissez le code de classe; l'application cherchera le PC automatiquement.")
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(0xFFF4F7FA.toInt())
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(32))
        }
        scroll.addView(content)

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_classeaudio_logo)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(0xFF0F766E.toInt(), 18)
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(58)).apply { rightMargin = dp(14) }
        }
        brandRow.addView(logo)
        val brandCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brandCopy.addView(text("ClasseAudio", 24f, true, 0xFF123044.toInt()))
        brandCopy.addView(text("LE SON DE LA CLASSE, EN LOCAL", 10f, true, 0xFF627789.toInt()))
        brandRow.addView(brandCopy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val localBadge = text("SANS CLOUD", 10f, true, 0xFF0F766E.toInt()).apply {
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(0xFFE6F4F1.toInt(), 20)
        }
        brandRow.addView(localBadge)
        content.addView(brandRow, matchWrap())

        val intro = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = rounded(0xFF123044.toInt(), 22)
            elevation = dp(3).toFloat()
        }
        intro.addView(text("SONORISATION DE CLASSE", 10f, true, 0xFFA9E4D8.toInt()))
        intro.addView(text("La voix du professeur,\nau cœur de la classe.", 25f, true, 0xFFFFFFFF.toInt()))
        intro.addView(text("Rejoignez le réseau Wi-Fi de la salle. L'application cherche le PC automatiquement; aucun lien à distribuer.", 13f, false, 0xFFD0DEE8.toInt()))
        content.addView(intro, matchWrap())

        val joinCard = card(20)
        joinCard.addView(text("Rejoindre une classe", 20f, true, 0xFF123044.toInt()))
        joinCard.addView(text("Saisissez le code communiqué par le professeur. Le serveur est recherché automatiquement sur le réseau local.", 13f, false, 0xFF627789.toInt()))
        joinCard.addView(spacer(8))

        joinCard.addView(text("CODE DE CLASSE", 10f, true, 0xFF627789.toInt()))
        joinCodeField = edit("000000", InputType.TYPE_CLASS_NUMBER)
        joinCodeField.textSize = 22f
        joinCodeField.filters = arrayOf(InputFilter.LengthFilter(6))
        joinCard.addView(joinCodeField, matchWrap())

        joinCard.addView(text("VOTRE NOM (FACULTATIF)", 10f, true, 0xFF627789.toInt()))
        nameField = edit("Prénom ou pseudonyme", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        joinCard.addView(nameField, matchWrap())

        joinCard.addView(text("JE PARTICIPE COMME", 10f, true, 0xFF627789.toInt()))
        roleSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Élève", "Professeur")
            )
            background = rounded(0xFFFAFCFD.toInt(), 14, 0xFFDCE6EC.toInt())
            setPadding(dp(10), dp(3), dp(10), dp(3))
        }
        joinCard.addView(roleSpinner, matchWrap())

        teacherPinLabel = text("PIN PROFESSEUR · À GARDER PRIVÉ", 10f, true, 0xFF627789.toInt())
        teacherPinField = edit("PIN à 6 chiffres", InputType.TYPE_CLASS_NUMBER)
        teacherPinField.filters = arrayOf(InputFilter.LengthFilter(6))
        joinCard.addView(teacherPinLabel, matchWrap())
        joinCard.addView(teacherPinField, matchWrap())
        roleSpinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
            val visibility = if (position == 1) View.VISIBLE else View.GONE
            teacherPinLabel.visibility = visibility
            teacherPinField.visibility = visibility
        }
        teacherPinLabel.visibility = View.GONE
        teacherPinField.visibility = View.GONE

        serverStatusText = text("Pas encore de salle détectée.", 13f, false, 0xFF496476.toInt()).apply {
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = rounded(0xFFF0F6F7.toInt(), 14)
        }
        joinCard.addView(serverStatusText, matchWrap())
        discoverButton = button("Rechercher la salle", primary = false)
        joinCard.addView(discoverButton, matchWrap())
        discoverButton.setOnClickListener { requestDiscoveryPermissionThenStart() }

        advancedToggle = text("Problème de découverte ?  Saisie manuelle (secours)", 12f, true, 0xFF0F766E.toInt()).apply {
            setPadding(0, dp(6), 0, dp(10))
            isClickable = true
            isFocusable = true
        }
        joinCard.addView(advancedToggle)
        advancedPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(4))
            background = rounded(0xFFF7FAFB.toInt(), 14, 0xFFE2EAF0.toInt())
            visibility = View.GONE
        }
        advancedPanel.addView(text("À utiliser seulement si le réseau bloque la détection automatique.", 12f, false, 0xFF627789.toInt()))
        hostField = edit("Adresse locale du PC, ex. 192.168.1.20:8090", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        advancedPanel.addView(hostField, matchWrap())
        joinCard.addView(advancedPanel, matchWrap())
        advancedToggle.setOnClickListener {
            manualPanelExpanded = !manualPanelExpanded
            advancedPanel.visibility = if (manualPanelExpanded) View.VISIBLE else View.GONE
            advancedToggle.text = if (manualPanelExpanded) "Masquer les options de dépannage" else "Problème de découverte ?  Saisie manuelle (secours)"
        }

        joinButton = button("Rejoindre avec ce code", primary = true)
        joinButton.textSize = 16f
        joinCard.addView(joinButton, matchWrap())
        joinButton.setOnClickListener { onJoinPressed() }
        content.addView(joinCard, matchWrap())

        val connectedCard = card(18)
        statusText = text("Prêt. Saisissez le code de classe pour commencer.", 14f, true, 0xFF173247.toInt()).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFFEAF3F5.toInt(), 14)
        }
        connectedCard.addView(statusText, matchWrap())
        detailsText = text("", 13f, false, 0xFF627789.toInt())
        connectedCard.addView(detailsText, matchWrap())
        routeText = text("Routage audio : en attente", 13f, true, 0xFF0F766E.toInt())
        routeText.setPadding(0, dp(4), 0, dp(4))
        connectedCard.addView(routeText, matchWrap())
        participantText = text("", 13f, false, 0xFF627789.toInt())
        connectedCard.addView(participantText)
        content.addView(connectedCard, matchWrap())

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        requestButton = button("Demander la parole", primary = false)
        micButton = button("Activer le microphone", primary = true)
        disconnectButton = button("Quitter le salon", primary = false)
        controls.addView(requestButton, matchWrap())
        controls.addView(micButton, matchWrap())
        controls.addView(disconnectButton, matchWrap())
        content.addView(controls, matchWrap())
        requestButton.visibility = View.GONE
        micButton.visibility = View.GONE
        disconnectButton.visibility = View.GONE
        requestButton.setOnClickListener { sendSpeakingRequest() }
        micButton.setOnClickListener { toggleMicrophone() }
        disconnectButton.setOnClickListener { disconnectFromRoom() }

        teacherPanel = card(18)
        teacherPanel.addView(text("Demandes de parole", 18f, true, 0xFF123044.toInt()))
        teacherPanel.addView(text("Le professeur contrôle qui peut publier son micro.", 13f, false, 0xFF627789.toInt()))
        requestList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        teacherPanel.addView(requestList)
        content.addView(teacherPanel, matchWrap())
        teacherPanel.visibility = View.GONE

        content.addView(spacer(12))
        content.addView(text("Gardez l'application au premier plan pendant ce premier essai. Bluetooth, écho et réseau scolaire restent à vérifier sur les appareils réels.", 12f, false, 0xFF718596.toInt()))

        setContentView(scroll)
    }

    private fun onJoinPressed() {
        if (room != null) return
        val role = if (roleSpinner.selectedItemPosition == 1) "teacher" else "student"
        val displayName = nameField.text.toString().trim()
        val joinCode = joinCodeField.text.toString().trim()
        val teacherPin = teacherPinField.text.toString().trim()
        val manualText = hostField.text.toString().trim()
        val manualBase = if (manualText.isBlank()) null else normalizeApiBase(manualText)

        if (manualText.isNotBlank() && manualBase == null) {
            setStatus("L'adresse de secours n'est pas valide. Exemple : 192.168.1.20:8090.")
            return
        }
        if (joinCode.length != 6) {
            setStatus("Le code de classe doit contenir 6 chiffres.")
            joinCodeField.requestFocus()
            return
        }
        if (role == "teacher" && teacherPin.length != 6) {
            setStatus("Le PIN professeur doit contenir 6 chiffres.")
            teacherPinField.requestFocus()
            return
        }

        val beginJoin: (String) -> Unit = { base ->
            lifecycleScope.launch {
                joinButton.isEnabled = false
                discoverButton.isEnabled = false
                setStatus("Demande d'accès au relais local…")
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
                    discoveredApiBase = base
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

                    detailsText.text = "Salon : $roomName\nParticipant : ${displayName.ifBlank { if (role == "teacher") "Professeur" else "Élève" }}\nAucun enregistrement audio."
                    serverStatusText.text = "Salle trouvée automatiquement · adresse masquée"
                    joinButton.visibility = View.GONE
                    discoverButton.visibility = View.GONE
                    requestButton.visibility = if (role == "student") View.VISIBLE else View.GONE
                    micButton.visibility = View.VISIBLE
                    disconnectButton.visibility = View.VISIBLE
                    teacherPanel.visibility = if (role == "teacher") View.VISIBLE else View.GONE
                    updateMicButton()
                    refreshAudioRoute()
                    setStatus(if (role == "teacher") "Connecté comme professeur. Votre micro est transmis aux abonnés." else "Connecté comme élève. Vous écoutez la classe; votre micro reste bloqué jusqu'à autorisation.")

                    if (role == "teacher") startTeacherPolling()
                } catch (ex: Exception) {
                    joinButton.isEnabled = true
                    discoverButton.isEnabled = true
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

        val connectUsingDiscovery = {
            val base = manualBase ?: discoveredApiBase
            if (base != null) {
                beginJoin(base)
            } else {
                pendingJoinAction = beginJoin
                joinButton.isEnabled = false
                serverStatusText.text = "Recherche du PC sur le Wi-Fi local…"
                requestDiscoveryPermissionThenStart()
            }
        }

        if (role == "teacher") {
            val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                needed += Manifest.permission.BLUETOOTH_CONNECT
            requestPermissionsThen(needed) {
                if (hasPermission(Manifest.permission.RECORD_AUDIO)) connectUsingDiscovery()
                else {
                    joinButton.isEnabled = true
                    setStatus("Autorisation microphone refusée; le professeur ne peut pas publier sa voix.")
                }
            }
        } else {
            connectUsingDiscovery()
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
        stopDiscovery()
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
        discoveredApiBase = null
        pendingJoinAction = null
        controlToken = null
        localIdentity = null
        activeSpeakerIdentity = null
        studentCanPublish = false
        microphoneEnabled = false
        lastTeacherSnapshot = null
        joinButton.visibility = View.VISIBLE
        joinButton.isEnabled = true
        discoverButton.visibility = View.VISIBLE
        discoverButton.isEnabled = true
        discoverButton.text = "Rechercher la salle"
        serverStatusText.text = "Pas encore de salle détectée."
        requestButton.visibility = View.GONE
        micButton.visibility = View.GONE
        disconnectButton.visibility = View.GONE
        teacherPanel.visibility = View.GONE
        participantText.text = ""
        detailsText.text = ""
        routeText.text = "Routage audio : en attente"
        teacherPinField.text.clear()
        requestList.removeAllViews()
        setStatus("Vous avez quitté la salle. Le code peut être réutilisé si le cours est toujours actif.")
    }

    private suspend fun requestJson(
        method: String,
        url: String,
        body: JSONObject? = null,
        bearerToken: String? = null,
        connectTimeoutMs: Int = 6000,
        readTimeoutMs: Int = 10000
    ): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
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
            val scheme = uri.scheme?.lowercase() ?: "http"
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host ?: return null
            val path = uri.encodedPath.orEmpty()
            if (path.isNotBlank() && path != "/") return null
            val port = if (uri.port == -1) 8090 else uri.port
            if (port !in 1..65535) return null
            val urlHost = if (host.contains(':')) "[$host]" else host
            "$scheme://$urlHost:$port"
        } catch (_: Exception) {
            null
        }
    }

    private fun requestDiscoveryPermissionThenStart() {
        val action = { startDiscovery() }
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.NEARBY_WIFI_DEVICES)) {
            requestPermissionsThen(listOf(Manifest.permission.NEARBY_WIFI_DEVICES)) {
                if (!hasPermission(Manifest.permission.NEARBY_WIFI_DEVICES)) {
                    setStatus("Autorisation non accordée; tentative de découverte réseau quand même.")
                }
                action()
            }
        } else action()
    }

    /** Stage 1: browse the existing DNS-SD/mDNS service. Stage 2: probe this LAN's bounded IPv4 subnets. */
    private fun startDiscovery() {
        stopDiscovery()
        val generation = discoveryGeneration
        discoveredApiBase = null
        resolvingService = false
        discoverButton.isEnabled = false
        discoverButton.text = "Recherche en cours…"
        serverStatusText.text = "Recherche du PC sur le Wi-Fi local…"
        setStatus("Recherche automatique en cours. Vous n'avez pas besoin de saisir l'adresse du PC.")

        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("ClasseAudioDiscovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) { }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = runOnUiThread {
                if (generation == discoveryGeneration) setStatus("Étape 1/2 : recherche mDNS sur le réseau local…")
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                runOnUiThread {
                    if (generation == discoveryGeneration) {
                        serverStatusText.text = "mDNS indisponible · essai d'une autre méthode…"
                        setStatus("La découverte mDNS est bloquée (code $errorCode); balayage local en secours.")
                    }
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                runOnUiThread {
                    if (generation == discoveryGeneration)
                        setStatus("Arrêt mDNS incomplet (code $errorCode); la recherche locale continue.")
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val type = serviceInfo.serviceType?.trimEnd('.')?.lowercase().orEmpty()
                if (!type.startsWith("_classeaudio._tcp") || resolvingService || generation != discoveryGeneration) return
                resolvingService = true
                try {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            resolvingService = false
                            runOnUiThread {
                                if (generation == discoveryGeneration)
                                    setStatus("Service mDNS repéré mais non résolu (code $errorCode); seconde méthode en cours…")
                            }
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val address = info.host?.hostAddress
                            if (address.isNullOrBlank()) {
                                resolvingService = false
                                runOnUiThread {
                                    if (generation == discoveryGeneration)
                                        setStatus("Service mDNS repéré, adresse indisponible; seconde méthode en cours…")
                                }
                                return
                            }
                            val base = apiBaseForAddress(address, info.port)
                            lifecycleScope.launch {
                                try {
                                    val session = requestJson(
                                        "GET",
                                        "$base/api/v1/session",
                                        connectTimeoutMs = 900,
                                        readTimeoutMs = 1200
                                    )
                                    if (isClasseAudioSession(session)) onServerFound(base, "mDNS", generation)
                                    else if (generation == discoveryGeneration) resolvingService = false
                                } catch (_: Exception) {
                                    if (generation == discoveryGeneration) resolvingService = false
                                }
                            }
                        }
                    })
                } catch (_: Exception) {
                    resolvingService = false
                }
            }
        }
        discoveryListener = listener
        try {
            nsdManager.discoverServices("_classeaudio._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            discoveryListener = null
            releaseMulticastLock()
            serverStatusText.text = "mDNS indisponible · essai d'une autre méthode…"
        }

        fallbackDiscoveryJob = lifecycleScope.launch {
            delay(1100)
            if (generation != discoveryGeneration || discoveredApiBase != null) return@launch
            serverStatusText.text = "mDNS sans réponse · recherche directe du PC…"
            setStatus("Étape 2/2 : recherche directe sur le même réseau (sans envoyer votre code).")
            subnetScanJob = launch {
                val result = try { scanLocalSubnetsForServer(generation) } catch (_: Exception) { null }
                if (generation != discoveryGeneration || discoveredApiBase != null) return@launch
                if (result != null) onServerFound(result, "réseau local", generation)
                else onDiscoveryFailed(generation)
            }
        }
    }

    private suspend fun scanLocalSubnetsForServer(generation: Int): String? {
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val subnets = connectivity.allNetworks
            .filter { network ->
                val capabilities = connectivity.getNetworkCapabilities(network) ?: return@filter false
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            }
            .flatMap { network ->
                connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().mapNotNull { linkAddress ->
                    val address = linkAddress.address as? Inet4Address ?: return@mapNotNull null
                    if (address.isLoopbackAddress || address.isLinkLocalAddress) return@mapNotNull null
                    val prefix = linkAddress.prefixLength
                    if (prefix !in 16..30) return@mapNotNull null
                    LanIPv4Interface(ipv4ToLong(address), prefix)
                }
            }
            .distinct()
            .take(3)

        val targets = linkedSetOf<Long>()
        for (subnet in subnets) {
            val mask = (0xFFFFFFFFL shl (32 - subnet.prefixLength)) and 0xFFFFFFFFL
            val network = subnet.address and mask
            val broadcast = network or (0xFFFFFFFFL xor mask)
            val hostCount = broadcast - network - 1
            if (hostCount <= 0 || hostCount > 512) continue
            var candidate = network + 1
            while (candidate < broadcast) {
                if (candidate != subnet.address) targets += candidate
                candidate++
            }
            if (targets.size > 512) return null
        }
        if (targets.isEmpty()) return null

        val semaphore = Semaphore(32)
        return coroutineScope {
            val result = CompletableDeferred<String?>()
            val remaining = AtomicInteger(targets.size)
            val probes = targets.map { address ->
                launch(Dispatchers.IO) {
                    try {
                        semaphore.withPermit {
                            if (!result.isCompleted && generation == discoveryGeneration) {
                                val base = "http://${longToIPv4(address)}:8090"
                                val session = requestJson(
                                    "GET",
                                    "$base/api/v1/session",
                                    connectTimeoutMs = 450,
                                    readTimeoutMs = 550
                                )
                                if (isClasseAudioSession(session)) result.complete(base)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Most candidate hosts have no ClasseAudio API listening on port 8090.
                    }

                    if (remaining.decrementAndGet() == 0 && !result.isCompleted)
                        result.complete(null)
                }
            }

            val found = result.await()
            if (found != null || generation != discoveryGeneration)
                probes.forEach { it.cancel() }
            found
        }
    }

    private fun onServerFound(base: String, method: String, generation: Int) {
        if (generation != discoveryGeneration || discoveredApiBase != null) return
        discoveredApiBase = base
        stopDiscovery()
        discoverButton.isEnabled = true
        discoverButton.text = "Rechercher à nouveau"
        serverStatusText.text = "Salle détectée automatiquement · adresse masquée"
        setStatus("Serveur ClasseAudio trouvé par $method. Saisissez uniquement le code de classe.")
        val join = pendingJoinAction
        pendingJoinAction = null
        join?.invoke(base)
    }

    private fun onDiscoveryFailed(generation: Int) {
        if (generation != discoveryGeneration) return
        stopDiscovery()
        discoverButton.isEnabled = true
        discoverButton.text = "Réessayer la recherche"
        joinButton.isEnabled = true
        pendingJoinAction = null
        serverStatusText.text = "PC non détecté sur ce réseau."
        setStatus("Vérifiez que le PC est démarré sur le même Wi-Fi et que les appareils ne sont pas isolés. Sinon, ouvrez la saisie manuelle de secours.")
    }

    private fun isClasseAudioSession(session: JSONObject): Boolean =
        session.optString("serviceName") == "ClasseAudio"
            && session.optString("serviceType").startsWith("_classeaudio._tcp")

    private fun apiBaseForAddress(address: String, port: Int): String {
        val unwrapped = address.trim().removePrefix("[").removeSuffix("]")
        val host = if (unwrapped.contains(':')) "[${unwrapped.replace("%", "%25")}]" else unwrapped
        return "http://$host:$port"
    }

    private fun ipv4ToLong(address: Inet4Address): Long {
        val bytes = address.address
        return ((bytes[0].toLong() and 0xFF) shl 24) +
            ((bytes[1].toLong() and 0xFF) shl 16) +
            ((bytes[2].toLong() and 0xFF) shl 8) +
            (bytes[3].toLong() and 0xFF)
    }

    private fun longToIPv4(address: Long): String = listOf(
        (address ushr 24) and 0xFF,
        (address ushr 16) and 0xFF,
        (address ushr 8) and 0xFF,
        address and 0xFF
    ).joinToString(".")

    private fun stopDiscovery() {
        discoveryGeneration++
        fallbackDiscoveryJob?.cancel()
        fallbackDiscoveryJob = null
        subnetScanJob?.cancel()
        subnetScanJob = null
        resolvingService = false
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

    private fun card(paddingDp: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
        background = rounded(0xFFFFFFFF.toInt(), 20, 0xFFE3EBF0.toInt())
        elevation = dp(2).toFloat()
        clipToOutline = true
    }

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        strokeColor?.let { setStroke(dp(1), it) }
    }

    private fun text(value: String, size: Float, bold: Boolean, color: Int = 0xFF173247.toInt()) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
            setLineSpacing(dp(2).toFloat(), 1.02f)
        }

    private fun edit(hintText: String, inputTypeValue: Int) =
        EditText(this).apply {
            hint = hintText
            inputType = inputTypeValue
            textSize = 15f
            setTextColor(0xFF173247.toInt())
            setHintTextColor(0xFF8799A6.toInt())
            setSingleLine(true)
            minHeight = dp(52)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(0xFFFAFCFD.toInt(), 14, 0xFFDCE6EC.toInt())
            backgroundTintList = null
        }

    private fun button(label: String, primary: Boolean = true) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(if (primary) 0xFFFFFFFF.toInt() else 0xFF0F766E.toInt())
        minHeight = dp(50)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = rounded(
            if (primary) 0xFF0F766E.toInt() else 0xFFF1F7F6.toInt(),
            14,
            if (primary) null else 0xFFD4E8E2.toInt()
        )
        backgroundTintList = null
        elevation = if (primary) dp(2).toFloat() else 0f
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

private data class LanIPv4Interface(
    val address: Long,
    val prefixLength: Int
)

private class SimpleItemSelectedListener(
    private val callback: (Int) -> Unit
) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = callback(position)
    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}
