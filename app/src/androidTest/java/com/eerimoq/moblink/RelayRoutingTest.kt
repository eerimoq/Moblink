package com.eerimoq.moblink

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RelayRoutingTest {
    @Suppress("DEPRECATION")
    @Test
    fun localWifiIsRequiredAndTunnelForwardsBothDirections() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val manager =
            instrumentation.targetContext.getSystemService(ConnectivityManager::class.java)
        val wifi =
            manager.allNetworks.first {
                manager
                    .getNetworkCapabilities(it)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        val address =
            manager
                .getLinkProperties(wifi)!!
                .linkAddresses
                .first { it.address is Inet4Address }
                .address
        val sockets = LinkedBlockingQueue<WebSocket>()
        val identified = LinkedBlockingQueue<Boolean>()
        val responses = LinkedBlockingQueue<MessageToStreamer>()
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                            sockets.add(webSocket)
                            webSocket.send(
                                """{"hello":{"apiVersion":"1","authentication":{"salt":"s","challenge":"c"}}}"""
                            )
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val message = Json.decodeFromString<MessageToStreamer>(text)
                            if (message.identify != null) {
                                webSocket.send("""{"identified":{"result":{"ok":{}}}}""")
                                identified.add(true)
                            } else responses.add(message)
                        }
                    }
                )
        )
        server.start(InetAddress.getByName("0.0.0.0"), 0)
        val relay = Relay()
        DatagramSocket(0, address).use { cloud ->
            DatagramSocket(0, address).use { streamer ->
                wifi.bindSocket(cloud)
                wifi.bindSocket(streamer)
                cloud.soTimeout = 3000
                streamer.soTimeout = 3000
                try {
                    relay.setup(
                        "id",
                        "ws://${address.hostAddress}:${server.port}",
                        "test",
                        "test",
                        {},
                        { it(50, null) },
                    )
                    relay.setDestinationNetwork(wifi)
                    relay.start()
                    assertNull(sockets.poll(300, TimeUnit.MILLISECONDS))
                    relay.setWiFiNetwork(wifi)
                    val socket = sockets.poll(10, TimeUnit.SECONDS) ?: error("No Wi-Fi control")
                    assertTrue(identified.poll(5, TimeUnit.SECONDS) == true)
                    socket.send(
                        """{"request":{"id":1,"data":{"startTunnel":{"address":"${address.hostAddress}","port":${cloud.localPort}}}}}"""
                    )
                    val port =
                        responses.poll(10, TimeUnit.SECONDS)!!.response!!.data.startTunnel!!.port
                    repeat(20) { relay.setWiFiNetwork(wifi) }
                    val payload = byteArrayOf(1, 2, 3)
                    streamer.send(DatagramPacket(payload, payload.size, address, port))
                    val packet = DatagramPacket(ByteArray(2048), 2048)
                    cloud.receive(packet)
                    assertArrayEquals(payload, packet.data.copyOf(packet.length))
                    cloud.send(DatagramPacket(payload, payload.size, packet.socketAddress))
                    streamer.receive(packet)
                    assertArrayEquals(payload, packet.data.copyOf(packet.length))
                    assertEquals(1, server.requestCount)
                } finally {
                    relay.stop()
                    server.close()
                }
            }
        }
    }
}
