package uy.cor.emisor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Servicio en primer plano (foreground service) que sigue transmitiendo la
 * ubicación aunque la app quede en segundo plano o la pantalla apagada.
 * Lee el GPS y hace POST a {baseUrl}/api/position con {busId, lat, lng, speed}.
 */
class LocationService : Service(), LocationListener {

    private lateinit var locationManager: LocationManager
    private val sender = Executors.newSingleThreadExecutor()
    private var baseUrl: String = ""
    private var busId: String = ""

    companion object {
        const val EXTRA_BASE_URL = "base_url"
        const val EXTRA_BUS_ID = "bus_id"
        const val ACTION_STATUS = "uy.cor.emisor.STATUS"
        const val EXTRA_STATUS_TEXT = "status_text"
        const val EXTRA_STATUS_OK = "status_ok"

        private const val CHANNEL_ID = "cor_emisor"
        private const val NOTIF_ID = 1
        private const val MIN_TIME_MS = 5000L   // no más seguido que cada 5 s
        private const val MIN_DIST_M = 0f
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        baseUrl = intent?.getStringExtra(EXTRA_BASE_URL)?.trimEnd('/') ?: ""
        busId = intent?.getStringExtra(EXTRA_BUS_ID) ?: ""

        val notif = buildNotification("Transmitiendo $busId…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        startGps()
        // Si el sistema mata el servicio, que lo reinicie con el último intent.
        return START_REDELIVER_INTENT
    }

    private fun startGps() {
        try {
            // GPS del dispositivo; sin depender de Google Play Services.
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, MIN_TIME_MS, MIN_DIST_M, this
            )
            // Red como respaldo si el GPS todavía no fija (arranque en interior).
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, MIN_TIME_MS, MIN_DIST_M, this
                )
            }
        } catch (e: SecurityException) {
            broadcast("Falta el permiso de ubicación.", false)
            stopSelf()
        }
    }

    override fun onLocationChanged(location: Location) {
        val speed = if (location.hasSpeed()) location.speed else null
        sendPosition(location.latitude, location.longitude, speed)
    }

    private fun sendPosition(lat: Double, lng: Double, speed: Float?) {
        val url = "$baseUrl/api/position"
        sender.execute {
            try {
                val body = JSONObject().apply {
                    put("busId", busId)
                    put("lat", lat)
                    put("lng", lng)
                    if (speed != null) put("speed", speed.toDouble()) else put("speed", JSONObject.NULL)
                }.toString()

                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 10000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { os: OutputStream -> os.write(body.toByteArray()) }
                val code = conn.responseCode
                conn.disconnect()

                val hhmmss = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
                if (code in 200..299) {
                    val txt = "Enviado $busId — ${"%.5f".format(lat)}, ${"%.5f".format(lng)} — $hhmmss"
                    updateNotification("Transmitiendo $busId — $hhmmss")
                    broadcast(txt, true)
                } else {
                    broadcast("El servidor respondió $code. Revisá la URL o el busId.", false)
                }
            } catch (e: Exception) {
                broadcast("Sin conexión con el servidor (se reintenta). ${e.message ?: ""}", false)
            }
        }
    }

    private fun broadcast(text: String, ok: Boolean) {
        val i = Intent(ACTION_STATUS)
            .setPackage(packageName)
            .putExtra(EXTRA_STATUS_TEXT, text)
            .putExtra(EXTRA_STATUS_OK, ok)
        sendBroadcast(i)
    }

    // ---- Notificación persistente ----

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Transmisión GPS", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Envío de ubicación del ómnibus" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("COR Emisor GPS")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        try { locationManager.removeUpdates(this) } catch (_: Exception) {}
        sender.shutdown()
        super.onDestroy()
    }

    // Callbacks vacíos requeridos por LocationListener en APIs viejas.
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
