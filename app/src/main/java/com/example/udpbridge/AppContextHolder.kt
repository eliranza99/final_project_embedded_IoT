package com.example.udpbridge

import android.content.Context

object AppContextHolder {
    lateinit var appContext: Context
        private set

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }
}
