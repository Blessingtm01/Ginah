package zw.co.accisafe.rdc.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import zw.co.accisafe.rdc.data.net.MapIncident
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** An open incident near the user. [ahead] is true when the user is travelling towards it. */
data class Nearby(val inc: MapIncident, val metres: Int, val ahead: Boolean)

private const val WATCH_M = 1000f     // incidents further than this are ignored (and may be announced again later)
private const val MOVING_MPS = 2.5f   // ~9 km/h: below this the user is treated as standing still
private const val AHEAD_DEGREES = 50f

/** Short spoken names for the map key types. */
fun spokenType(inc: MapIncident) = when (inc.type) {
    "FATAL" -> inc.category.lowercase().let { c ->
        if ("accident" in c || "collision" in c || "crash" in c) "fatal accident" else "$c with deaths reported"
    }
    "CRASH_INJURY" -> "road accident with injuries"
    "CRASH" -> "road accident"
    "FIRE" -> "fire"
    "FLOOD_STORM" -> "flooding"
    "STRUCTURE" -> "collapsed structure"
    "ROAD_HAZARD" -> "road hazard"
    "WATER_ENV" -> "water or environmental hazard"
    else -> inc.category.lowercase()
}

fun distanceText(m: Int) = if (m < 1000) "${(m / 10.0).roundToInt() * 10} m" else "%.1f km".format(m / 1000.0)

/**
 * Watches the user's position against open incidents and warns once at about 500 m and again at about 100 m,
 * by voice (when enabled) and vibration, much like a navigation app.
 */
class ProximityAlerts(context: Context) : TextToSpeech.OnInitListener {
    private val app = context.applicationContext
    private val tts = TextToSpeech(app, this)
    private var ttsReady = false
    private val announced = mutableMapOf<Int, Int>()   // incident id -> smallest threshold already announced
    var voice = true

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts.language = Locale.UK
    }

    fun check(loc: Location, incidents: List<MapIncident>): Nearby? {
        val moving = loc.hasSpeed() && loc.speed >= MOVING_MPS && loc.hasBearing()
        val res = FloatArray(2)
        // The user's own reports are not announced back to them.
        val near = incidents.filter { it.active && !it.mine }.mapNotNull { inc ->
            Location.distanceBetween(loc.latitude, loc.longitude, inc.latitude, inc.longitude, res)
            val d = res[0]
            if (d > WATCH_M) { announced.remove(inc.id); return@mapNotNull null }
            val diff = abs((((res[1] + 360f) % 360f) - loc.bearing + 540f) % 360f - 180f)
            val ahead = moving && diff <= AHEAD_DEGREES
            // While travelling, ignore what is behind or beside the road unless it is right here.
            if (moving && !ahead && d > 60f) null else Nearby(inc, d.roundToInt(), ahead)
        }
        val nearest = near.minByOrNull { it.metres } ?: return null
        val threshold = when {
            nearest.metres <= 150 -> 100
            nearest.metres <= 500 -> 500
            else -> null
        }
        if (threshold != null && (announced[nearest.inc.id] ?: Int.MAX_VALUE) > threshold) {
            announced[nearest.inc.id] = threshold
            announce(nearest)
        }
        return nearest
    }

    private fun announce(n: Nearby) {
        val where = if (n.ahead) "ahead" else "from you"
        val text = "Caution. ${spokenType(n.inc).replaceFirstChar { it.uppercase() }}, ${distanceText(n.metres).replace(" m", " metres").replace(" km", " kilometres")} $where."
        if (voice && ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "proximity-${n.inc.id}")
        runCatching {
            @Suppress("DEPRECATION")
            app.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
        }
    }

    fun shutdown() { tts.stop(); tts.shutdown() }
}

/** The "you are here" overlay, reporting each new GPS fix on the main thread. */
class TrackingOverlay(context: Context, private val mv: MapView, private val onFix: (Location) -> Unit) :
    MyLocationNewOverlay(GpsMyLocationProvider(context).apply {
        locationUpdateMinTime = 1000
        locationUpdateMinDistance = 2f
    }, mv) {
    private val main = Handler(Looper.getMainLooper())

    init {
        val density = context.resources.displayMetrics.density
        setDirectionArrow(youAreHereDot(density), youAreHereArrow(density))
        setPersonAnchor(.5f, .5f)
        setDirectionAnchor(.5f, .5f)
        isDrawAccuracyEnabled = true
    }

    override fun onLocationChanged(location: Location?, source: IMyLocationProvider?) {
        super.onLocationChanged(location, source)
        location?.let { main.post { onFix(it) } }
    }
}

private const val BLUE = 0xFF1A73E8.toInt()

private fun youAreHereDot(density: Float): Bitmap {
    val size = (26 * density).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val r = size / 2f
    p.color = 0x55000000; c.drawCircle(r, r + density, r - 2 * density, p)
    p.color = android.graphics.Color.WHITE; c.drawCircle(r, r, r - 2 * density, p)
    p.color = BLUE; c.drawCircle(r, r, r - 5 * density, p)
    return bmp
}

private fun youAreHereArrow(density: Float): Bitmap {
    val size = (40 * density).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val s = size.toFloat()
    val arrow = Path().apply {
        moveTo(s / 2, s * .08f); lineTo(s * .85f, s * .88f); lineTo(s / 2, s * .68f); lineTo(s * .15f, s * .88f); close()
    }
    p.color = android.graphics.Color.WHITE
    p.style = Paint.Style.STROKE; p.strokeWidth = 4 * density; p.strokeJoin = Paint.Join.ROUND
    c.drawPath(arrow, p)
    p.style = Paint.Style.FILL; p.color = BLUE
    c.drawPath(arrow, p)
    return bmp
}
