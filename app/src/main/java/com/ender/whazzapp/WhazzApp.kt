package com.ender.whazzapp

import android.app.Application

class WhazzApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AccountStore.init(this)
        Notifications.init(this)
        SessionManager.init(this)
    }
}
