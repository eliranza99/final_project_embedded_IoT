package com.example.udpbridge

import android.app.Application

class UdpBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.init(this)
    }
}
