package zw.co.accisafe.rdc

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import zw.co.accisafe.rdc.data.SessionStore
import zw.co.accisafe.rdc.sync.SyncScheduler
import javax.inject.Inject
import org.osmdroid.config.Configuration as MapConfiguration

@HiltAndroidApp
class AcciSafeApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var session: SessionStore
    @Inject lateinit var sync: SyncScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        session.restore()
        // OpenStreetMap tile servers require an identifying user agent.
        MapConfiguration.getInstance().userAgentValue = "AcciSafeRDC/${BuildConfig.VERSION_NAME} ($packageName)"
        sync.schedulePeriodic()
    }
}
