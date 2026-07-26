package com.eerimoq.moblink

import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.Inet4Address

const val serviceType = "_moblink._tcp"

private class Resolve(var cancelled: Boolean = false)

class Scanner(
    private val nsdManager: NsdManager,
    private val onFound: (String, String, Network?) -> Unit,
    private val onLost: (String) -> Unit,
) {
    private var listener: NsdManager.DiscoveryListener? = null
    private var pendingResolves: MutableMap<String, Resolve> = mutableMapOf()

    fun start() {
        logger.log("Scanner start")
        listener = createNsdListenerCallback()
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        logger.log("Scanner stop")
        if (listener != null) {
            nsdManager.stopServiceDiscovery(listener)
            listener = null
        }
    }

    private fun createNsdListenerCallback(): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                logger.log("Service found: ${service.serviceName}")
                val resolve = Resolve()
                nsdManager.resolveService(service, createResolveCallback(resolve))
                pendingResolves.replace(service.serviceName, resolve)?.cancelled = true
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                logger.log("Service lost ${service.serviceName}")
                pendingResolves.remove(service.serviceName)?.cancelled = true
                onLost(service.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager.stopServiceDiscovery(this)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager.stopServiceDiscovery(this)
            }
        }
    }

    private fun createResolveCallback(resolve: Resolve): NsdManager.ResolveListener {
        return object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {}

            override fun onServiceResolved(service: NsdServiceInfo) {
                if (resolve.cancelled) {
                    logger.log("Service resolve cancelled ${service.serviceName}")
                    return
                }
                val address =
                    if (service.host is Inet4Address) {
                        service.host.hostAddress
                    } else {
                        "[${service.host.hostAddress}]"
                    }
                val url = "ws://${address}:${service.port}"
                onFound(service.serviceName, url, service.network)
            }
        }
    }
}
