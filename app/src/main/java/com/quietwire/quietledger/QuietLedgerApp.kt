package com.quietwire.quietledger

import android.app.Application
import com.quietwire.quietledger.data.backup.AutoBackupTrigger
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class QuietLedgerApp : Application() {

    /**
     * Watches for the app leaving the foreground, to write a backup.
     *
     * Injected into the Application rather than a screen because it has to
     * outlive every screen. An observer owned by an Activity would stop
     * watching the moment that Activity went away, which is exactly when it is
     * needed.
     */
    @Inject
    lateinit var autoBackupTrigger: AutoBackupTrigger

    override fun onCreate() {
        super.onCreate()
        autoBackupTrigger.start()
    }
}
