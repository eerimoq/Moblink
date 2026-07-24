package com.eerimoq.moblink

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable data class Present(val dummy: Boolean? = null)

@Serializable
data class Result(
    val ok: Present? = null,
    val wrongPassword: Present? = null,
    val unknownRequest: Present? = null,
)

@Serializable data class Authentication(val challenge: String, val salt: String)

@Serializable data class StartTunnelRequest(val address: String, val port: Int)

@Serializable data class WebProxyOpenRequest(val id: String, val host: String, val port: Int)

@Serializable data class WebProxyConnectionMessage(val id: String)

@Serializable data class WebProxyDataMessage(val id: String, val data: String)

@Serializable
data class RequestData(
    val startTunnel: StartTunnelRequest? = null,
    val webProxyOpen: WebProxyOpenRequest? = null,
    val status: Present? = null,
)

@Serializable data class Hello(val apiVersion: String, val authentication: Authentication)

@Serializable data class Identified(val result: Result)

@Serializable data class Request(val id: Int, val data: RequestData)

@Serializable data class StartTunnelResponse(val port: Int)

@Serializable data class WebProxyOpenResponse(val id: String)

@Serializable
enum class ThermalState {
    @SerialName("white") WHITE,
    @SerialName("yellow") YELLOW,
    @SerialName("red") RED,
}

@Serializable
data class StatusResponse(
    val batteryPercentage: Int? = null,
    val thermalState: ThermalState? = null,
)

@Serializable
data class ResponseData(
    val startTunnel: StartTunnelResponse? = null,
    val status: StatusResponse? = null,
    val webProxyOpen: WebProxyOpenResponse? = null,
)

@Serializable
enum class Capability {
    @SerialName("webProxy") WEB_PROXY
}

@Serializable
data class Identify(
    val id: String,
    val name: String,
    val authentication: String,
    val capabilities: List<Capability>? = null,
)

@Serializable data class Response(val id: Int, val result: Result, val data: ResponseData?)

@Serializable
data class MessageToRelay(
    val hello: Hello? = null,
    val identified: Identified? = null,
    val request: Request? = null,
    val webProxyData: WebProxyDataMessage? = null,
    val webProxyClose: WebProxyConnectionMessage? = null,
) {
    companion object {
        fun fromJson(text: String): MessageToRelay {
            return Json.decodeFromString(text)
        }
    }
}

@Serializable
data class MessageToStreamer(
    val identify: Identify? = null,
    val response: Response? = null,
    val webProxyData: WebProxyDataMessage? = null,
    val webProxyClose: WebProxyConnectionMessage? = null,
) {
    fun toJson(): String {
        return Json.encodeToString(this)
    }
}
