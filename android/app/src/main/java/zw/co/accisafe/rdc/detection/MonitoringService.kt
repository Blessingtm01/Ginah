package zw.co.accisafe.rdc.detection

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import zw.co.accisafe.rdc.MainActivity
import zw.co.accisafe.rdc.R
import zw.co.accisafe.rdc.data.SessionStore
import javax.inject.Inject
import kotlin.math.sqrt

data class MonitorStatus(
    val active: Boolean = false,
    val accelG: Float = 0f,
    val speedKmh: Float? = null,
    val gyroAvailable: Boolean = true,
    val lastEvent: String? = null,
    val impactThresholdG: Float = 4f,
)

/**
 * Accident monitoring. Runs only while the user has switched it on, as a disclosed foreground
 * service with a permanent notification.
 */
@AndroidEntryPoint
class MonitoringService : Service(), SensorEventListener, LocationListener {

    @Inject lateinit var coordinator: DetectionCoordinator
    @Inject lateinit var session: SessionStore

    private lateinit var sensors: SensorManager
    private lateinit var locations: LocationManager
    private var detector = AccidentDetector()
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastPublish = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sensors = getSystemService(SensorManager::class.java)
        locations = getSystemService(LocationManager::class.java)
    }

    @SuppressLint("MissingPermission", "WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (_status.value.active) return START_NOT_STICKY
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Accident monitoring", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, MonitoringService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle("AcciSafe RDC accident monitoring is ON")
            .setContentText("Motion sensors and GPS are in use. Tap Stop to switch off.")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
        val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ServiceCompat.startForeground(this, NOTIF, notification,
            if (hasLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)

        val settings = runBlocking { session.currentDeviceSettings() }
        detector = AccidentDetector(AccidentDetector.Config(
            impactG = settings.impactG, strongImpactG = maxOf(6f, settings.impactG + 2f), cooldownMs = settings.cooldownSeconds * 1000L,
        ))
        val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        sensors.registerListener(this, sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_GAME)
        gyro?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        if (hasLocation) runCatching { locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper()) }
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AcciSafeRDC:monitor").apply { acquire() }
        _status.value = MonitorStatus(active = true, gyroAvailable = gyro != null, impactThresholdG = settings.impactG)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        sensors.unregisterListener(this)
        locations.removeUpdates(this)
        wakeLock?.takeIf { it.isHeld }?.release()
        _status.value = MonitorStatus(active = false)
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val t = event.timestamp / 1_000_000
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            detector.onGyro(t, x, y, z)
            return
        }
        detector.onAccel(t, x, y, z)?.let { verdict ->
            val text = (if (verdict.isAccident) "POSSIBLE ACCIDENT: " else "Ignored: ") + verdict.reasons.joinToString(", ")
            _status.value = _status.value.copy(lastEvent = text)
            if (verdict.isAccident) coordinator.start(verdict, simulated = false)
        }
        if (t - lastPublish > 300) {
            lastPublish = t
            _status.value = _status.value.copy(accelG = sqrt(x * x + y * y + z * z) / AccidentDetector.GRAVITY)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onLocationChanged(location: Location) {
        if (!location.hasSpeed()) return
        val kmh = location.speed * 3.6f
        detector.onSpeed(location.elapsedRealtimeNanos / 1_000_000, kmh)
        _status.value = _status.value.copy(speedKmh = kmh)
    }

    companion object {
        private const val CHANNEL = "monitoring"
        private const val NOTIF = 7
        private const val ACTION_STOP = "zw.co.accisafe.rdc.STOP_MONITORING"
        private val _status = MutableStateFlow(MonitorStatus())
        val status: StateFlow<MonitorStatus> = _status

        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, MonitoringService::class.java))
        fun stop(context: Context) = context.startService(Intent(context, MonitoringService::class.java).setAction(ACTION_STOP))
    }
}
