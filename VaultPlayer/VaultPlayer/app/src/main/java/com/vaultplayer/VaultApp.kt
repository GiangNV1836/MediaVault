package com.vaultplayer

import android.app.Application

class VaultApp : Application() {
    lateinit var vault: Vault

    override fun onCreate() {
        super.onCreate()
        vault = Vault(this)
        vault.start()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (vault.state.value == DbState.Ready) vault.thumbs.onTrim(level)
    }
}
