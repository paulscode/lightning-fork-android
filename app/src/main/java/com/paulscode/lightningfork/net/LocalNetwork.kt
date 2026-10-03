package com.paulscode.lightningfork.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether the phone is on a network where the node's home addresses could
 * answer: Wi-Fi or Ethernet. On mobile data they cannot (a home IP hangs
 * until its timeout, a .local name takes seconds to fail), so the onion is
 * tried first there. Unknown counts as local, which keeps the old order.
 */
fun interface LocalNetwork {
    fun likely(): Boolean
}

class AndroidLocalNetwork(context: Context) : LocalNetwork {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun likely(): Boolean {
        val caps = runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return true
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
