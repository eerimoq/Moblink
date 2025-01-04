package com.eerimoq.moblink

import android.content.Context
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.sp
import com.eerimoq.moblink.ui.theme.MoblinkTheme
import com.google.gson.Gson
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.MessageDigest
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.encodeUtf8

class MainActivity : ComponentActivity() {
    private val relay = Relay()
    private var uiSettings: Settings? = null
    private var uiStarted = false
    private var uiVersion = "?"
    private val uiButtonText = mutableStateOf("Start")
    private val uiStatus = mutableStateOf("")
    private val uiWakeLock = WakeLock()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setup()
        setContent { MoblinkTheme { Surface { Main() } } }
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    private fun setup() {
        requestNetwork(NetworkCapabilities.TRANSPORT_CELLULAR)
        requestNetwork(NetworkCapabilities.TRANSPORT_WIFI)
        relay.handlerThread.start()
        relay.handler = Handler(relay.handlerThread.looper)
        uiSettings = Settings(getSharedPreferences("settings", Context.MODE_PRIVATE))
        uiSettings!!.load()
        relay.streamerUrl = uiSettings!!.streamerUrl
        relay.password = uiSettings!!.password
        relay.relayId = uiSettings!!.relayId
        relay.name = uiSettings!!.name
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            uiVersion = packageInfo.versionName
        } catch (_: Exception) {}
        relay.handler?.post { updateStatus() }
    }

    private fun updateStatus() {
        val status =
            if (relay.cellularNetwork == null) {
                "Waiting for cellular"
            } else if (relay.wiFiNetwork == null) {
                "Waiting for WiFi"
            } else if (relay.connected) {
                "Connected to streamer"
            } else if (relay.wrongPassword) {
                "Wrong password"
            } else if (relay.started) {
                "Connecting to streamer"
            } else {
                "Disconnected from streamer"
            }
        runOnUiThread { uiStatus.value = status }
    }

    private fun saveSettings() {
        uiSettings!!.store()
        val streamerUrl = uiSettings!!.streamerUrl
        val password = uiSettings!!.password
        val name = uiSettings!!.name
        relay.handler?.post {
            this.relay.streamerUrl = streamerUrl
            this.relay.password = password
            this.relay.name = name
        }
    }

    private fun start() {
        if (uiStarted) {
            return
        }
        Log.i("Moblink", "Start")
        uiStarted = true
        startService(this)
        uiWakeLock.acquire(this)
        relay.handler?.post {
            if (!relay.started) {
                relay.started = true
                startInternal()
            }
        }
    }

    private fun stop() {
        if (!uiStarted) {
            return
        }
        Log.i("Moblink", "Stop")
        uiStarted = false
        stopService(this)
        uiWakeLock.release()
        relay.handler?.post {
            if (relay.started) {
                relay.started = false
                stopInternal()
            }
        }
    }

    private fun startInternal() {
        stopInternal()
        if (!relay.started) {
            return
        }
        val request =
            try {
                Request.Builder().url(relay.streamerUrl).build()
            } catch (e: Exception) {
                Log.i("Moblink", "Failed to build URL: $e")
                return
            }
        relay.webSocket =
            relay.okHttpClient.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        super.onMessage(webSocket, text)
                        relay.handler?.post {
                            if (webSocket === getWebSocket()) {
                                handleMessage(text)
                            }
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        super.onClosed(webSocket, code, reason)
                        relay.handler?.post {
                            if (webSocket === getWebSocket()) {
                                Log.i("Moblink", "Websocket closed $reason (code $code)")
                                reconnectSoon()
                            }
                        }
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        super.onFailure(webSocket, t, response)
                        relay.handler?.post {
                            if (webSocket === getWebSocket()) {
                                Log.i("Moblink", "Websocket failure $t")
                                reconnectSoon()
                            }
                        }
                    }
                },
            )
    }

    private fun stopInternal() {
        relay.webSocket?.cancel()
        relay.webSocket = null
        relay.connected = false
        relay.wrongPassword = false
        updateStatus()
        relay.streamerSocket?.close()
        relay.destinationSocket?.close()
    }

    private fun reconnectSoon() {
        stopInternal()
        relay.handler?.postDelayed({ startInternal() }, 5000)
    }

    private fun getWebSocket(): WebSocket? {
        return relay.webSocket
    }

    private fun handleMessage(text: String) {
        try {
            val message = Gson().fromJson(text, MessageToClient::class.java)
            if (message.hello != null) {
                handleMessageHello(message.hello)
            } else if (message.identified != null) {
                handleMessageIdentified(message.identified)
            } else if (message.request != null) {
                handleMessageRequest(message.request)
            }
        } catch (e: Exception) {
            Log.i("Moblink", "Message handling failed: $e")
            reconnectSoon()
        }
    }

    private fun handleMessageHello(hello: Hello) {
        var concatenated = "${relay.password}${hello.authentication.salt}"
        val sha256 = MessageDigest.getInstance("SHA-256")
        sha256.reset()
        var hash: ByteArray = sha256.digest(concatenated.encodeUtf8().toByteArray())
        concatenated =
            "${Base64.encodeToString(hash, Base64.NO_WRAP)}${hello.authentication.challenge}"
        sha256.reset()
        hash = sha256.digest(concatenated.encodeUtf8().toByteArray())
        val authentication = Base64.encodeToString(hash, Base64.NO_WRAP)
        val identify = Identify(relay.relayId, relay.name, authentication)
        send(MessageToServer(identify, null))
    }

    private fun handleMessageIdentified(identified: Identified) {
        if (identified.result.ok != null) {
            relay.connected = true
        } else if (identified.result.wrongPassword != null) {
            relay.wrongPassword = true
        }
        updateStatus()
    }

    private fun handleMessageRequest(request: MessageRequest) {
        if (request.data.startTunnel != null) {
            handleMessageStartTunnelRequest(request.id, request.data.startTunnel)
        }
    }

    private fun handleMessageStartTunnelRequest(id: Int, startTunnel: StartTunnelRequest) {
        relay.streamerSocket?.close()
        relay.destinationSocket?.close()
        if (relay.wiFiNetwork == null || relay.cellularNetwork == null) {
            reconnectSoon()
            return
        }
        relay.streamerSocket = DatagramSocket()
        relay.wiFiNetwork?.bindSocket(relay.streamerSocket)
        relay.destinationSocket = DatagramSocket()
        relay.cellularNetwork?.bindSocket(relay.destinationSocket)
        startStreamerReceiver(
            relay.streamerSocket!!,
            relay.destinationSocket!!,
            InetAddress.getByName(startTunnel.address),
            startTunnel.port,
        )
        val data = ResponseData(StartTunnelResponseData(relay.streamerSocket!!.localPort))
        val response = MessageResponse(id, Result(Empty(true), null), data)
        send(MessageToServer(null, response))
    }

    private fun send(message: MessageToServer) {
        relay.webSocket?.send(Gson().toJson(message))
    }

    private fun requestNetwork(transportType: Int) {
        val networkRequest =
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(transportType)
                .build()
        val networkCallback =
            object : NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    relay.handler?.post {
                        if (transportType == NetworkCapabilities.TRANSPORT_CELLULAR) {
                            relay.cellularNetwork = network
                        } else {
                            relay.wiFiNetwork = network
                        }
                        updateStatus()
                    }
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    relay.handler?.post {
                        if (transportType == NetworkCapabilities.TRANSPORT_CELLULAR) {
                            relay.cellularNetwork = null
                        } else {
                            relay.wiFiNetwork = null
                        }
                        updateStatus()
                    }
                }
            }
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.requestNetwork(networkRequest, networkCallback)
    }

    @Composable
    fun Main() {
        var streamerUrlInput by remember { mutableStateOf(uiSettings!!.streamerUrl) }
        var passwordInput by remember { mutableStateOf(uiSettings!!.password) }
        var nameInput by remember { mutableStateOf(uiSettings!!.name) }
        val text by uiButtonText
        val status by uiStatus
        val focusManager = LocalFocusManager.current
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Moblink", fontSize = 30.sp)
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = null,
            )
            OutlinedTextField(
                value = streamerUrlInput,
                onValueChange = { streamerUrlInput = it },
                label = { Text("Streamer URL") },
                placeholder = { Text("ws://192.168.0.10:7777") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions =
                    KeyboardActions(
                        onDone = {
                            uiSettings!!.streamerUrl = streamerUrlInput
                            saveSettings()
                            focusManager.clearFocus()
                        }
                    ),
            )
            OutlinedTextField(
                value = passwordInput,
                onValueChange = { passwordInput = it },
                label = { Text("Password") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions =
                    KeyboardActions(
                        onDone = {
                            uiSettings!!.password = passwordInput
                            saveSettings()
                            focusManager.clearFocus()
                        }
                    ),
            )
            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions =
                    KeyboardActions(
                        onDone = {
                            uiSettings!!.name = nameInput
                            saveSettings()
                            focusManager.clearFocus()
                        }
                    ),
            )
            Text(status)
            Button(
                onClick = {
                    if (!uiStarted) {
                        uiButtonText.value = "Stop"
                        start()
                    } else {
                        uiButtonText.value = "Start"
                        stop()
                    }
                }
            ) {
                Text(text)
            }
            Text("Version $uiVersion")
        }
    }
}
