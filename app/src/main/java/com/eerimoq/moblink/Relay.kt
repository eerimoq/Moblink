package com.eerimoq.moblink

import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import androidx.compose.runtime.mutableStateOf
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.encodeUtf8

class Relay {
    private val okHttpClient = OkHttpClient.Builder().pingInterval(5, TimeUnit.SECONDS).build()
    private var webSocket: WebSocket? = null
    private var connectivityManager: ConnectivityManager? = null
    private var streamerNetworks: List<Network> = emptyList()
    private var streamerNetwork: Network? = null
    private var destinationNetwork: Network? = null
    private var streamerSocket: DatagramSocket? = null
    private var destinationSocket: DatagramSocket? = null
    private var relayId = ""
    private var streamerUrl = ""
    private var password = ""
    private var name = ""
    private val handlerThread = HandlerThread("Something")
    private var handler: Handler? = null
    private var started = false
    private var connected = false
    private var wrongPassword = false
    private var onStatusUpdated: ((String) -> Unit)? = null
    private var getStatus: (((Int, ThermalState?) -> Unit) -> Unit)? = null
    private var reconnectSoonRunnable: Runnable? = null
    val uiButtonText = mutableStateOf("Start")
    val uiStatus = mutableStateOf("")
    var uiStarted = false
    var uiStreamerName = ""
    var uiStreamerUrl = ""

    fun setup(
        connectivityManager: ConnectivityManager,
        relayId: String,
        streamerUrl: String,
        password: String,
        name: String,
        onStatusUpdated: (String) -> Unit,
        getStatus: ((Int, ThermalState?) -> Unit) -> Unit,
    ) {
        logger.log("$streamerUrl: Setup")
        this.connectivityManager = connectivityManager
        this.onStatusUpdated = onStatusUpdated
        this.getStatus = getStatus
        handlerThread.start()
        handler = Handler(handlerThread.looper)
        handler?.post {
            this.relayId = relayId
            this.streamerUrl = streamerUrl
            this.password = password
            this.name = name
            updateStatusInternal()
        }
    }

    fun start() {
        logger.log("$streamerUrl: Start")
        uiStarted = true
        uiButtonText.value = "Stop"
        handler?.post {
            if (!started) {
                started = true
                startInternal()
            }
        }
    }

    fun stop() {
        logger.log("$streamerUrl: Stop")
        uiStarted = false
        uiButtonText.value = "Start"
        handler?.post {
            if (started) {
                started = false
                stopInternal()
            }
        }
    }

    fun updateSettings(relayId: String, streamerUrl: String, password: String, name: String) {
        handler?.post {
            this.relayId = relayId
            this.streamerUrl = streamerUrl
            this.password = password
            this.name = name
            updateStatusInternal()
        }
    }

    fun setStreamerNetworks(networks: List<Network>) {
        handler?.post {
            streamerNetworks = networks
            if (started && streamerNetwork !in networks) {
                reconnectSoon("Streamer network changed")
            }
            updateStatusInternal()
        }
    }

    fun setDestinationNetwork(network: Network?) {
        handler?.post {
            destinationNetwork = network
            if (destinationSocket != null) {
                if (network != null) {
                    reconnectSoon("Destination network available")
                } else {
                    reconnectSoon("Destination network lost")
                }
            }
            updateStatusInternal()
        }
    }

    fun streamerSocketError(socket: DatagramSocket) {
        handler?.post {
            if (socket == streamerSocket) {
                reconnectSoon("Streamer socket error")
            }
        }
    }

    fun destinationSocketError(socket: DatagramSocket) {
        handler?.post {
            if (socket == destinationSocket) {
                reconnectSoon("Destination socket error")
            }
        }
    }

    private fun startInternal() {
        stopInternal()
        logger.log("$streamerUrl: Start internal when started: $started")
        if (!started) {
            return
        }
        val request =
            try {
                Request.Builder().url(streamerUrl).build()
            } catch (e: Exception) {
                logger.log("$streamerUrl: Failed to build URL: $e")
                return
            }
        streamerNetwork = pickStreamerNetwork(request.url.host)
        val network = streamerNetwork ?: return
        val client =
            okHttpClient
                .newBuilder()
                .socketFactory(network.socketFactory)
                .dns { network.getAllByName(it).toList() }
                .proxy(Proxy.NO_PROXY)
                .build()
        webSocket =
            client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        super.onMessage(webSocket, text)
                        handler?.post {
                            if (webSocket === getWebsocket()) {
                                handleMessage(text)
                            }
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        super.onClosed(webSocket, code, reason)
                        handler?.post {
                            if (webSocket === getWebsocket()) {
                                reconnectSoon("Websocket closed $reason (code $code)")
                            }
                        }
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        super.onFailure(webSocket, t, response)
                        handler?.post {
                            if (webSocket === getWebsocket()) {
                                reconnectSoon("Websocket failure $t")
                            }
                        }
                    }
                },
            )
    }

    private fun stopInternal() {
        logger.log("$streamerUrl: Stop internal")
        webSocket?.cancel()
        webSocket = null
        streamerNetwork = null
        connected = false
        wrongPassword = false
        updateStatusInternal()
        streamerSocket?.close()
        streamerSocket = null
        destinationSocket?.close()
        destinationSocket = null
    }

    private fun updateStatusInternal() {
        val status =
            if (streamerUrl.isEmpty()) {
                "Streamer URL empty"
            } else if (password.isEmpty()) {
                "Password empty"
            } else if (streamerNetworks.isEmpty()) {
                "Waiting for WiFi or Ethernet"
            } else if (destinationNetwork == null) {
                "Waiting for cellular"
            } else if (connected) {
                "Connected to streamer"
            } else if (wrongPassword) {
                "Wrong password"
            } else if (started) {
                "Connecting to streamer"
            } else {
                "Disconnected from streamer"
            }
        logger.log("$streamerUrl: Status: $status")
        onStatusUpdated?.let { it(status) }
    }

    private fun reconnectSoon(reason: String) {
        logger.log("$streamerUrl: Reconnect soon with reason: $reason")
        stopInternal()
        if (reconnectSoonRunnable != null) {
            handler?.removeCallbacks(reconnectSoonRunnable!!)
        }
        reconnectSoonRunnable = Runnable {
            reconnectSoonRunnable = null
            startInternal()
        }
        handler?.postDelayed(reconnectSoonRunnable!!, 5000)
    }

    private fun getWebsocket(): WebSocket? {
        return webSocket
    }

    private fun pickStreamerNetwork(host: String): Network? {
        return streamerNetworks.firstOrNull { network ->
            val routes =
                connectivityManager?.getLinkProperties(network)?.routes ?: return@firstOrNull false
            try {
                network.getAllByName(host).any { address ->
                    routes.any { !it.isDefaultRoute && it.matches(address) }
                }
            } catch (_: Exception) {
                false
            }
        } ?: streamerNetworks.firstOrNull()
    }

    private fun handleMessage(text: String) {
        try {
            val message = MessageToRelay.fromJson(text)
            if (message.hello != null) {
                handleMessageHello(message.hello)
            } else if (message.identified != null) {
                handleMessageIdentified(message.identified)
            } else if (message.request != null) {
                handleMessageRequest(message.request)
            }
        } catch (e: Exception) {
            reconnectSoon("Message handling failed: $e")
        }
    }

    private fun handleMessageHello(hello: Hello) {
        logger.log("$streamerUrl: Got hello: $hello")
        var concatenated = "$password${hello.authentication.salt}"
        concatenated = "${base64Encode(calcSha256(concatenated))}${hello.authentication.challenge}"
        val identify = Identify(relayId, name, base64Encode(calcSha256(concatenated)))
        send(MessageToStreamer(identify, null))
    }

    private fun handleMessageIdentified(identified: Identified) {
        if (identified.result.ok != null) {
            connected = true
        } else if (identified.result.wrongPassword != null) {
            wrongPassword = true
        }
        updateStatusInternal()
    }

    private fun handleMessageRequest(request: com.eerimoq.moblink.Request) {
        if (request.data.startTunnel != null) {
            handleMessageStartTunnelRequest(request.id, request.data.startTunnel)
        } else if (request.data.status != null) {
            handleMessageStatus(request.id)
        }
    }

    private fun handleMessageStartTunnelRequest(id: Int, startTunnel: StartTunnelRequest) {
        logger.log("$streamerUrl: Got start tunnel: $startTunnel")
        streamerSocket?.close()
        streamerSocket = null
        destinationSocket?.close()
        destinationSocket = null
        if (streamerNetwork == null || destinationNetwork == null) {
            reconnectSoon("Start tunnel without streamer or destination network")
            return
        }
        streamerSocket = DatagramSocket()
        streamerNetwork?.bindSocket(streamerSocket)
        destinationSocket = DatagramSocket()
        destinationSocket?.soTimeout = 30 * 1000
        destinationNetwork?.bindSocket(destinationSocket)
        startStreamerReceiver(
            streamerSocket!!,
            destinationSocket!!,
            destinationNetwork!!.getAllByName(startTunnel.address).first(),
            startTunnel.port,
            this,
            streamerUrl,
        )
        val data = ResponseData(StartTunnelResponse(streamerSocket!!.localPort), null)
        val response = Response(id, Result(Present(), null), data)
        send(MessageToStreamer(null, response))
    }

    private fun handleMessageStatus(id: Int) {
        getStatus?.let {
            it { batteryPercentage, thermalState ->
                handler?.post {
                    val data = ResponseData(null, StatusResponse(batteryPercentage, thermalState))
                    val response = Response(id, Result(Present(), null), data)
                    send(MessageToStreamer(null, response))
                }
            }
        }
    }

    private fun send(message: MessageToStreamer) {
        webSocket?.send(message.toJson())
    }
}

private fun startStreamerReceiver(
    streamerSocket: DatagramSocket,
    destinationSocket: DatagramSocket,
    destinationAddress: InetAddress,
    destinationPort: Int,
    relay: Relay?,
    streamerUrl: String,
) {
    thread {
        var destinationReceiverStarted = false
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            while (true) {
                streamerSocket.receive(packet)
                if (!destinationReceiverStarted) {
                    startDestinationReceiver(
                        streamerSocket,
                        destinationSocket,
                        packet.address,
                        packet.port,
                        relay,
                        streamerUrl,
                    )
                    destinationReceiverStarted = true
                }
                packet.address = destinationAddress
                packet.port = destinationPort
                destinationSocket.send(packet)
            }
        } catch (error: Exception) {
            logger.log("$streamerUrl: Streamer receiver error $error")
            relay?.streamerSocketError(streamerSocket)
        }
    }
}

private fun startDestinationReceiver(
    streamerSocket: DatagramSocket,
    destinationSocket: DatagramSocket,
    streamerAddress: InetAddress,
    streamerPort: Int,
    relay: Relay?,
    streamerUrl: String,
) {
    thread {
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            while (true) {
                destinationSocket.receive(packet)
                packet.address = streamerAddress
                packet.port = streamerPort
                streamerSocket.send(packet)
            }
        } catch (error: Exception) {
            logger.log("$streamerUrl: Destination receiver error $error")
            relay?.destinationSocketError(destinationSocket)
        }
    }
}

private fun base64Encode(input: ByteArray): String {
    return Base64.encodeToString(input, Base64.NO_WRAP)
}

private fun calcSha256(data: String): ByteArray {
    val sha256 = MessageDigest.getInstance("SHA-256")
    return sha256.digest(data.encodeUtf8().toByteArray())
}
