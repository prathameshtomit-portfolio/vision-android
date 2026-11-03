package ai.vision.connect

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

private val Ink = Color(0xFF080D18)
private val Panel = Color(0xFF111B2C)
private val Panel2 = Color(0xFF17243A)
private val Cyan = Color(0xFF65E6F0)
private val Blue = Color(0xFF648BFF)
private val Lavender = Color(0xFFA99BFF)
private val Muted = Color(0xFFAAB8CC)
private val Good = Color(0xFF73E0AE)

/** Single-file networking implementation. A LAN QR pin is checked against the
 *  exact DER certificate fingerprint before pairing or sending the bearer token. */
object PinnedTransport {
    private fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

    fun client(pin: String? = null, websocket: Boolean = false): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(if (websocket) 0 else 15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(if (websocket) 0 else 20, TimeUnit.SECONDS)
        val expected = pin?.trim()?.lowercase().orEmpty()
        if (expected.isNotEmpty()) {
            require(expected.matches(Regex("[0-9a-f]{64}"))) { "Invalid VISION certificate fingerprint" }
            val trust = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    val cert = chain?.firstOrNull() ?: throw CertificateException("VISION server certificate is missing")
                    try { cert.checkValidity() } catch (e: Exception) { throw CertificateException("VISION certificate is not currently valid", e) }
                    if (fingerprint(cert) != expected) throw CertificateException("VISION certificate fingerprint does not match the QR code")
                }
            }
            val ssl = SSLContext.getInstance("TLS")
            ssl.init(null, arrayOf<javax.net.ssl.TrustManager>(trust), SecureRandom())
            builder.sslSocketFactory(ssl.socketFactory, trust)
            // The QR pins the certificate itself. This permits a LAN IP address
            // to change without weakening peer identity validation.
            val pinnedHostVerifier = HostnameVerifier { _, session ->
                try {
                    val peer = session.peerCertificates.firstOrNull() as? X509Certificate
                    peer != null && fingerprint(peer) == expected
                } catch (_: Exception) { false }
            }
            builder.hostnameVerifier(pinnedHostVerifier)
        }
        return builder.build()
    }
}

object SecureStore {
    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context, "vision_connect_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    fun save(context: Context, base: String, token: String, pin: String) {
        prefs(context).edit().putString("base_url", base).putString("token", token)
            .putString("certificate_pin", pin).apply()
    }
    fun base(context: Context) = prefs(context).getString("base_url", null)
    fun token(context: Context) = prefs(context).getString("token", null)
    fun pin(context: Context) = prefs(context).getString("certificate_pin", null)
    fun notificationForwarding(context: Context) = prefs(context).getBoolean("notification_forwarding", false)
    fun setNotificationForwarding(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean("notification_forwarding", enabled).apply()
    fun allowedChats(context: Context): Set<String> =
        (prefs(context).getStringSet("allowed_chats", emptySet()) ?: emptySet()).toSet()
    fun setAllowedChats(context: Context, names: Set<String>) {
        prefs(context).edit().putStringSet("allowed_chats", names.map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it.length <= 80 }.toSet()).apply()
    }
    fun clear(context: Context) = prefs(context).edit().clear().apply()
}

class MainActivity : ComponentActivity() {
    private var scanMessage by mutableStateOf("")
    private val scanner = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) scanMessage = "QR scan cancelled." else pairFromQr(contents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val paired = SecureStore.token(this) != null && SecureStore.base(this) != null
            var status by remember { mutableStateOf(if (paired) "Paired · ready to connect" else "Not connected") }
            var forwarding by remember { mutableStateOf(SecureStore.notificationForwarding(this)) }
            var chats by remember { mutableStateOf(SecureStore.allowedChats(this).joinToString(", ")) }
            val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                status = if (granted) "Connection notification permission granted" else "Android notification permission not granted"
            }
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Cyan, secondary = Lavender, background = Ink, surface = Panel,
                onBackground = Color.White, onSurface = Color.White
            )) {
                Surface(Modifier.fillMaxSize(), color = Ink) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 22.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(48.dp).background(Brush.linearGradient(listOf(Cyan, Blue, Lavender)), CircleShape), contentAlignment = Alignment.Center) {
                                Text("V", color = Ink, fontSize = 27.sp, fontWeight = FontWeight.Black)
                            }
                            Column(Modifier.weight(1f)) {
                                Text("VISION", fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                                Text("CONNECT", fontSize = 12.sp, color = Cyan, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                            }
                            StatusPill(if (paired) "PAIRED" else "OFFLINE", paired)
                        }
                        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF142A43), Color(0xFF1A1D3A), Color(0xFF111B2C))), RoundedCornerShape(24.dp)).padding(20.dp)) {
                            Text("YOUR PHONE.\nCONNECTED TO VISION.", fontSize = 25.sp, lineHeight = 30.sp, fontWeight = FontWeight.ExtraBold)
                            Spacer(Modifier.height(8.dp))
                            Text("One QR scan over the same Wi-Fi. No USB cable, no ADB setup, no IP address to type.", color = Muted, fontSize = 14.sp)
                            Spacer(Modifier.height(18.dp))
                            Button(
                                onClick = {
                                    val options = ScanOptions().apply {
                                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                        setPrompt("Scan the QR shown in VISION on your PC")
                                        setBeepEnabled(false)
                                        setOrientationLocked(false)
                                    }
                                    scanner.launch(options)
                                },
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
                                shape = RoundedCornerShape(16.dp)
                            ) { Text("SCAN VISION QR", fontWeight = FontWeight.Black, letterSpacing = 1.2.sp) }
                            Text("Scan once · secure pairing · automatic reconnect", Modifier.fillMaxWidth().padding(top = 10.dp), color = Color(0xFFB7DCE6), fontSize = 12.sp, textAlign = TextAlign.Center)
                        }
                        if (scanMessage.isNotBlank()) InfoLine(scanMessage)
                        InfoLine(status)
                        if (paired) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(onClick = {
                                    val endpoint = SecureStore.base(this@MainActivity).orEmpty()
                                    ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, VisionLinkService::class.java).putExtra("base_url", endpoint))
                                    status = "Reconnecting to VISION…"
                                }, modifier = Modifier.weight(1f)) { Text("RECONNECT") }
                                OutlinedButton(onClick = {
                                    stopService(Intent(this@MainActivity, VisionLinkService::class.java))
                                    SecureStore.clear(this@MainActivity)
                                    status = "This phone has been unpaired."
                                }, modifier = Modifier.weight(1f)) { Text("UNPAIR") }
                            }
                        }
                        SectionTitle("PHONE CONTROL PERMISSIONS", "Android asks you to approve these controls on the phone.")
                        ActionCard("Accessibility control", "Navigate, tap visible buttons, scroll and enter text in the focused field.", "OPEN SETTINGS") {
                            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                        ActionCard("Notification access", "Optional read-only previews from the chats you select below. No auto-replies or auto-sending.", "OPEN SETTINGS") {
                            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                        }
                        SectionTitle("OPTIONAL MESSAGE PREVIEWS", "Read-only · selected chats only · no group WhatsApp previews")
                        OutlinedTextField(
                            value = chats, onValueChange = { chats = it }, modifier = Modifier.fillMaxWidth(),
                            label = { Text("Allowed chat names, separated by commas") },
                            placeholder = { Text("e.g. Mom, Rahul, @username") },
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan, unfocusedBorderColor = Color(0xFF46556C), focusedLabelColor = Cyan)
                        )
                        OutlinedButton(onClick = {
                            SecureStore.setAllowedChats(this@MainActivity, chats.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet())
                            status = "Selected chat list saved on this phone."
                        }, modifier = Modifier.fillMaxWidth()) { Text("SAVE SELECTED CHATS") }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = forwarding, onCheckedChange = {
                                forwarding = it
                                SecureStore.setNotificationForwarding(this@MainActivity, it)
                            })
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Forward selected notification previews", fontWeight = FontWeight.SemiBold)
                                Text("Disabled by default. Never sends or replies to messages.", color = Muted, fontSize = 12.sp)
                            }
                        }
                        if (Build.VERSION.SDK_INT >= 33) {
                            OutlinedButton(onClick = {
                                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else status = "Android notifications are already allowed."
                            }, modifier = Modifier.fillMaxWidth()) { Text("ALLOW CONNECTION NOTIFICATIONS") }
                        }
                        SectionTitle("WHAT VISION CAN CONTROL", "Actions run only when requested from your VISION desktop.")
                        Text("Open installed apps · YouTube search and playback · play/pause/next/previous · volume · camera launch · dialer and SMS drafts · Back/Home/Recents · notifications and quick settings · click visible controls · scroll · type into a focused field.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
                        Text("Messages are never sent automatically. WhatsApp and Instagram support here is notification preview only; no message reading API, auto-reply, or auto-send is included.", color = Color(0xFFFFD49A), fontSize = 12.sp, lineHeight = 17.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("VISION CONNECT 1.0  ·  ANDROID 10+  ·  SAME WI-FI", Modifier.fillMaxWidth(), color = Color(0xFF61718A), fontSize = 10.sp, textAlign = TextAlign.Center, letterSpacing = 1.sp)
                    }
                }
            }
        }
        intent?.dataString?.let(::pairFromQr)
    }

    private fun pairFromQr(raw: String) {
        val uri = try { Uri.parse(raw) } catch (_: Exception) { null }
        if (uri == null || uri.scheme != "visionlink" || uri.host != "pair") {
            scanMessage = "This is not a VISION Connect QR. Scan the QR displayed by VISION on your PC."
            return
        }
        val base = uri.getQueryParameter("base_url")?.trim().orEmpty()
        val code = uri.getQueryParameter("code")?.trim().orEmpty()
        val pin = uri.getQueryParameter("pin")?.trim()?.lowercase().orEmpty()
        if (!base.startsWith("https://") || code.length != 6 || !code.all(Char::isDigit) || !pin.matches(Regex("[0-9a-f]{64}"))) {
            scanMessage = "QR incomplete or invalid. Generate a fresh same-Wi-Fi QR in VISION."
            return
        }
        scanMessage = "QR verified. Pairing with your PC over encrypted Wi-Fi…"
        val label = "${Build.MANUFACTURER} ${Build.MODEL}".take(80)
        Thread {
            val result = LinkApi(base, pin, this).completePairing(code, label)
            runOnUiThread {
                scanMessage = result
                if (result.startsWith("Paired")) {
                    ContextCompat.startForegroundService(this, Intent(this, VisionLinkService::class.java).putExtra("base_url", base))
                    scanMessage = "Paired. VISION is connecting; approve any Android permission prompts you choose to enable."
                }
            }
        }.start()
    }
}

@Composable private fun StatusPill(label: String, active: Boolean) {
    Box(Modifier.background(if (active) Color(0xFF153D35) else Color(0xFF332A31), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 7.dp)) {
        Text(label, color = if (active) Good else Color(0xFFFFC5B8), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable private fun InfoLine(text: String) {
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(Cyan, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}

@Composable private fun ActionCard(title: String, detail: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) { Text(action, fontSize = 10.sp) }
    }
}

class LinkApi(private val base: String, private val pin: String, private val context: Context) {
    fun completePairing(code: String, label: String): String {
        val endpoint = try { URI(base.trim()) } catch (_: Exception) { return "Pairing failed: invalid VISION address." }
        if (!endpoint.scheme.equals("https", true) || endpoint.host.isNullOrBlank() || endpoint.userInfo != null || endpoint.query != null || endpoint.fragment != null) {
            return "Pairing failed: the QR does not contain a valid secure VISION address."
        }
        if (!pin.matches(Regex("[0-9a-f]{64}"))) return "Pairing failed: the QR certificate fingerprint is invalid."
        if (code.length != 6 || !code.all(Char::isDigit)) return "Pairing failed: the one-time code is invalid."
        return try {
            val json = JSONObject().put("code", code).put("device_label", label.take(80))
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(base.trimEnd('/') + "/api/vision-link/pairing/complete").post(body).build()
            PinnedTransport.client(pin).newCall(request).execute().use { response ->
                val payload = JSONObject(response.body?.string().orEmpty().ifBlank { "{}" })
                if (!response.isSuccessful) return "Pairing failed (${response.code}): ${payload.optString("detail", "Check that VISION is open and both devices share Wi-Fi.")}"
                val token = payload.getString("session_token")
                SecureStore.save(context, base.trimEnd('/'), token, pin)
                "Paired successfully · device ${payload.optString("device_id", "connected") }"
            }
        } catch (e: Exception) {
            val message = e.message?.take(180) ?: e.javaClass.simpleName
            "Pairing failed: $message. Keep both devices on the same Wi-Fi and scan a fresh QR."
        }
    }
}

class VisionLinkService : Service() {
    companion object { const val ACTION_PUSH_NOTIFICATION = "ai.vision.connect.PUSH_NOTIFICATION" }
    private var socket: WebSocket? = null
    private var receiverRegistered = false
    private val handler = Handler(Looper.getMainLooper())
    private val channelId = "vision_connect_connection"
    private var activeBase = ""
    private var activeToken = ""
    private var activePin = ""
    private var retryDelay = 1500L
    private var client: OkHttpClient? = null
    private val retry = Runnable { if (activeBase.isNotBlank() && activeToken.isNotBlank()) connect() }
    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_PUSH_NOTIFICATION || !SecureStore.notificationForwarding(this@VisionLinkService)) return
            val packet = JSONObject()
                .put("type", "notification")
                .put("source_package", intent.getStringExtra("source_package")?.take(160) ?: "")
                .put("title", intent.getStringExtra("title")?.take(120) ?: "")
                .put("message", intent.getStringExtra("message")?.take(2000) ?: "")
                .put("posted_at", intent.getLongExtra("posted_at", 0L))
            socket?.send(packet.toString())
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ContextCompat.registerReceiver(this, notificationReceiver, IntentFilter(ACTION_PUSH_NOTIFICATION), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        val notification = notification("Starting secure phone link…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(71, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(71, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        activeBase = intent?.getStringExtra("base_url") ?: SecureStore.base(this).orEmpty()
        activeToken = SecureStore.token(this).orEmpty()
        activePin = SecureStore.pin(this).orEmpty()
        if (activeBase.isBlank() || activeToken.isBlank() || activePin.isBlank()) {
            updateNotification("Not paired · scan a VISION QR")
            stopSelf()
            return START_NOT_STICKY
        }
        connect()
        return START_STICKY
    }

    private fun connect() {
        handler.removeCallbacks(retry)
        if (!activeBase.startsWith("https://") || !activePin.matches(Regex("[0-9a-f]{64}"))) {
            updateNotification("Invalid pairing · scan a fresh VISION QR")
            stopSelf()
            return
        }
        socket?.close(1000, "Reconnect")
        client?.dispatcher?.executorService?.shutdown()
        val http = PinnedTransport.client(activePin, websocket = true)
        client = http
        val wsUrl = "wss://" + activeBase.removePrefix("https://").trimEnd('/') + "/ws/vision-link"
        socket = http.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                retryDelay = 1500L
                webSocket.send(JSONObject().put("type", "authenticate").put("token", activeToken).toString())
                updateNotification("Secure link online · authenticating")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val packet = JSONObject(text)
                    when (packet.optString("type")) {
                        "connected" -> updateNotification("Connected to VISION over Wi-Fi")
                        "command" -> handleCommand(packet)
                    }
                } catch (_: Exception) { }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response?.code == 401 || response?.code == 403) {
                    SecureStore.clear(this@VisionLinkService)
                    updateNotification("Pairing rejected · scan a fresh QR")
                    stopSelf()
                    return
                }
                updateNotification("Wi-Fi link unavailable · reconnecting")
                handler.postDelayed(retry, retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(30000L)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (code == 4401 || code == 4403) {
                    SecureStore.clear(this@VisionLinkService)
                    updateNotification("Phone unpaired by VISION")
                    stopSelf()
                    return
                }
                updateNotification("Disconnected · reconnecting")
                handler.postDelayed(retry, retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(30000L)
            }
        })
    }

    private fun handleCommand(packet: JSONObject) {
        val id = packet.optString("command_id")
        if (packet.optDouble("expires_at", 0.0) < System.currentTimeMillis() / 1000.0) {
            socket?.send(JSONObject().put("type", "result").put("command_id", id).put("ok", false).put("message", "Command expired").toString())
            return
        }
        val action = packet.optString("action")
        val args = packet.optJSONObject("args") ?: JSONObject()
        var ok = false
        var message = "Unsupported action"
        try {
            when (action) {
                "GET_DEVICE_INFO" -> { ok = true; message = "${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE}" }
                "LAUNCH_APP" -> {
                    val pkg = args.optString("package_name")
                    val appName = args.optString("app_name").trim()
                    val intent = if (pkg.isNotBlank()) packageManager.getLaunchIntentForPackage(pkg) else {
                        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        packageManager.queryIntentActivities(launcher, 0).firstOrNull { it.loadLabel(packageManager).toString().equals(appName, true) }?.activityInfo?.let {
                            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(it.packageName, it.name)
                        }
                    }
                    if (intent != null) { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(intent); ok = true; message = "App opened" }
                    else message = "Installed app not found or cannot be launched"
                }
                "SEARCH_YOUTUBE" -> {
                    val uri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(args.optString("query"))}")
                    startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ok = true; message = "YouTube search opened"
                }
                "PLAY_YOUTUBE_VIDEO" -> {
                    val idValue = args.optString("video_id")
                    if (idValue.matches(Regex("[A-Za-z0-9_-]{11}"))) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$idValue")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ok = true; message = "YouTube video opened"
                    } else message = "Invalid YouTube video ID"
                }
                "OPEN_CAMERA" -> ok = openCapture(MediaStore.ACTION_IMAGE_CAPTURE).also { message = if (it) "Camera opened; shutter remains under your control" else "No camera app available" }
                "OPEN_CAMERA_VIDEO" -> ok = openCapture(MediaStore.ACTION_VIDEO_CAPTURE).also { message = if (it) "Video camera opened; recording remains under your control" else "No video camera available" }
                "OPEN_URL" -> {
                    val uri = Uri.parse(args.optString("url"))
                    if (uri.scheme == "https" || uri.scheme == "http") { startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ok = true; message = "Link opened" }
                    else message = "Only HTTP(S) links are supported"
                }
                "OPEN_DIALER" -> {
                    val uri = Uri.parse("tel:${Uri.encode(args.optString("number"))}")
                    startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ok = true; message = "Dialer opened; you confirm the call"
                }
                "OPEN_SMS_COMPOSER" -> {
                    val uri = Uri.parse("smsto:${Uri.encode(args.optString("number"))}")
                    val intent = Intent(Intent.ACTION_SENDTO, uri).putExtra("sms_body", args.optString("body")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent); ok = true; message = "SMS draft opened; you confirm sending"
                }
                "MEDIA_PLAY_PAUSE", "MEDIA_NEXT", "MEDIA_PREVIOUS" -> {
                    val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val key = when (action) { "MEDIA_NEXT" -> KeyEvent.KEYCODE_MEDIA_NEXT; "MEDIA_PREVIOUS" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE }
                    val now = SystemClock.uptimeMillis()
                    audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0)); audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0))
                    ok = true; message = "Media key sent to the active media session"
                }
                "VOLUME" -> {
                    val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (args.optString("direction") == "down") AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    ok = true; message = "Media volume adjusted"
                }
                "CLICK_TEXT", "SCROLL_SCREEN", "TYPE_TEXT", "GLOBAL_BACK", "GLOBAL_HOME", "GLOBAL_RECENTS", "GLOBAL_NOTIFICATIONS", "GLOBAL_QUICK_SETTINGS", "GLOBAL_POWER_DIALOG" -> {
                    val service = VisionAccessibilityService.instance
                    if (service == null) message = "Enable VISION Connect Accessibility in Android Settings"
                    else {
                        ok = when (action) {
                            "CLICK_TEXT" -> service.clickVisibleText(args.optString("text"))
                            "SCROLL_SCREEN" -> service.scrollScreen(args.optString("direction"))
                            "TYPE_TEXT" -> service.typeIntoFocusedField(args.optString("text"))
                            else -> service.performGlobal(action)
                        }
                        message = if (ok) "Phone UI action performed" else "Action unavailable on the current screen"
                    }
                }
            }
        } catch (e: Exception) { message = "${e.javaClass.simpleName}: ${e.message?.take(160)}" }
        socket?.send(JSONObject().put("type", "result").put("command_id", id).put("ok", ok).put("message", message).toString())
    }

    private fun openCapture(action: String): Boolean {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(packageManager) == null) return false
        startActivity(intent); return true
    }
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, "VISION Connect", NotificationManager.IMPORTANCE_LOW)
        )
    }
    private fun notification(text: String) = Notification.Builder(this, channelId)
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("VISION CONNECT")
        .setContentText(text).setOngoing(true).build()
    private fun updateNotification(text: String) = getSystemService(NotificationManager::class.java).notify(71, notification(text))
    override fun onDestroy() {
        handler.removeCallbacks(retry)
        if (receiverRegistered) try { unregisterReceiver(notificationReceiver) } catch (_: Exception) { }
        socket?.close(1000, "Service stopped")
        client?.dispatcher?.executorService?.shutdown()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

class VisionNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || !SecureStore.notificationForwarding(this) || sbn.isOngoing || sbn.packageName == packageName) return
        if (sbn.packageName !in setOf("com.whatsapp", "com.instagram.android")) return
        val allowed = SecureStore.allowedChats(this)
        if (allowed.isEmpty()) return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString()?.trim().orEmpty()
        val conversationTitle = extras.getCharSequence("android.conversationTitle")?.toString()?.trim().orEmpty()
        if (sbn.packageName == "com.whatsapp") {
            // Fail closed: require Android's explicit group flag and reject group notifications.
            if (!extras.containsKey("android.isGroupConversation") || extras.getBoolean("android.isGroupConversation", true)) return
        }
        val chat = conversationTitle.ifBlank { title }
        if (chat.isBlank() || chat.lowercase() !in allowed) return
        val text = (extras.getCharSequence("android.bigText") ?: extras.getCharSequence("android.text"))?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        sendBroadcast(Intent(VisionLinkService.ACTION_PUSH_NOTIFICATION).setPackage(packageName)
            .putExtra("source_package", sbn.packageName.take(160)).putExtra("title", chat.take(120))
            .putExtra("message", text.take(2000)).putExtra("posted_at", sbn.postTime))
    }
}

class VisionAccessibilityService : AccessibilityService() {
    companion object { @Volatile var instance: VisionAccessibilityService? = null }
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no screen text is uploaded */ }
    override fun onInterrupt() { }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    fun performGlobal(action: String): Boolean {
        val value = when (action) {
            "GLOBAL_BACK" -> GLOBAL_ACTION_BACK
            "GLOBAL_HOME" -> GLOBAL_ACTION_HOME
            "GLOBAL_RECENTS" -> GLOBAL_ACTION_RECENTS
            "GLOBAL_NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
            "GLOBAL_QUICK_SETTINGS" -> GLOBAL_ACTION_QUICK_SETTINGS
            "GLOBAL_POWER_DIALOG" -> GLOBAL_ACTION_POWER_DIALOG
            else -> return false
        }
        return performGlobalAction(value)
    }
    fun clickVisibleText(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = label.trim().lowercase()
        return target.isNotEmpty() && findAndClick(root, target)
    }
    private fun findAndClick(node: AccessibilityNodeInfo, target: String): Boolean {
        val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
        if ((text == target || desc == target || text.contains(target) || desc.contains(target)) && (text.isNotEmpty() || desc.isNotEmpty())) {
            var current: AccessibilityNodeInfo? = node
            var depth = 0
            while (current != null && depth < 8) {
                if (current.isClickable && current.isEnabled && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                current = current.parent; depth++
            }
        }
        for (i in 0 until node.childCount) { val child = node.getChild(i) ?: continue; if (findAndClick(child, target)) return true }
        return false
    }
    fun scrollScreen(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return scrollFirst(root, if (direction == "down") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }
    private fun scrollFirst(node: AccessibilityNodeInfo, action: Int): Boolean {
        if (node.isScrollable && node.isEnabled && node.performAction(action)) return true
        for (i in 0 until node.childCount) { val child = node.getChild(i) ?: continue; if (scrollFirst(child, action)) return true }
        return false
    }
    fun typeIntoFocusedField(value: String): Boolean {
        if (value.isEmpty() || value.length > 2000) return false
        val root = rootInActiveWindow ?: return false
        val focused = findFocusedEditable(root) ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.isEditable && node.isEnabled) return node
        for (i in 0 until node.childCount) { val child = node.getChild(i) ?: continue; val found = findFocusedEditable(child); if (found != null) return found }
        return null
    }
}
