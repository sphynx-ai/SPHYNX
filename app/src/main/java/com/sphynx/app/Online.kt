package com.sphynx.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

// Is there a working internet connection? Offline-first: everything else keeps working without one; a button that
// needs it is disabled with the reason (state matrix, "Offline / needs network"; backlog item 8).
object Online {
    fun available(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Exception) {
        true    // when the phone cannot tell, let the button try (it reports its own error)
    }
}
