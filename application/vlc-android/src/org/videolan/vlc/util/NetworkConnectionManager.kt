/*
 * ************************************************************************
 *  NetworkConnectionManager.kt
 * *************************************************************************
 * Copyright © 2024 VLC authors and VideoLAN
 * Author: Nicolas POMEPUY
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 * **************************************************************************
 *
 *
 */

package org.videolan.vlc.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.MutableLiveData

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
object NetworkConnectionManager {
    val isMetered = MutableLiveData(true)
    val hasConnection = MutableLiveData(false)

    private val activeNetworks = mutableSetOf<Network>()
    private var connectivityManager: ConnectivityManager? = null

    private fun updateConnection(network: Network, available: Boolean) {
        val connected = synchronized(activeNetworks) {
            if (available) activeNetworks.add(network) else activeNetworks.remove(network)
            activeNetworks.isNotEmpty()
        }
        hasConnection.postValue(connected)
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            updateConnection(network, true)
            connectivityManager?.let { isMetered.postValue(it.isActiveNetworkMetered) }
        }

        // Network capabilities have changed for the network
        override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
        ) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            val metered = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            } else {
                connectivityManager?.isActiveNetworkMetered ?: isMetered.value ?: true
            }
            if (metered != isMetered.value) isMetered.postValue(metered)
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            updateConnection(network, false)
            connectivityManager?.let { isMetered.postValue(it.isActiveNetworkMetered) }
        }
    }
    @Volatile private var started = false

    @Synchronized
    fun start(context: Context) {
        if (started) return
        val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = manager
        try {
            // Observe connectivity instead of requesting that Android maintain a network.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                manager.registerDefaultNetworkCallback(networkCallback)
            } else {
                manager.registerNetworkCallback(networkRequest, networkCallback)
            }
            started = true
        } catch (e: SecurityException) {

        }
    }
}