package io.heckel.ntfy.app

import android.app.Application
import com.google.android.material.color.DynamicColors
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.worker.KeepAliveWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class Application : Application() {
    val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val repository by lazy {
        val repository = Repository.getInstance(applicationContext)
        if (repository.getRecordLogs()) {
            Log.setRecord(true)
        }
        repository
    }

    override fun onCreate() {
        super.onCreate()
        if (repository.getDynamicColorsEnabled()) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }

        // Keep-alive scheduling is best-effort and should survive process deaths.
        // We (re-)schedule or cancel it on every app start to match the current setting.
        if (repository.getKeepAliveEnabled()) {
            KeepAliveWorker.scheduleOnStartup(this)
        } else {
            KeepAliveWorker.cancel(this)
        }
    }
}
