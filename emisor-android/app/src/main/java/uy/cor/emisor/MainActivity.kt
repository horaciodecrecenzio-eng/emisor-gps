package uy.cor.emisor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var etUrl: EditText
    private lateinit var spinner: Spinner
    private lateinit var btnLoad: Button
    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView

    private var busIds: List<String> = emptyList()
    private var running = false

    companion object {
        private const val REQ_FOREGROUND = 100
        private const val REQ_BACKGROUND = 101
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(LocationService.EXTRA_STATUS_TEXT) ?: return
            tvStatus.text = text
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("cor_emisor", Context.MODE_PRIVATE)

        etUrl = findViewById(R.id.etUrl)
        spinner = findViewById(R.id.spinner)
        btnLoad = findViewById(R.id.btnLoad)
        btnToggle = findViewById(R.id.btnToggle)
        tvStatus = findViewById(R.id.tvStatus)

        etUrl.setText(prefs.getString("baseUrl", ""))

        btnLoad.setOnClickListener { loadBuses() }
        btnToggle.setOnClickListener { if (running) stopTracking() else startTracking() }

        if (etUrl.text.isNotBlank()) loadBuses()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(LocationService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
    }

    private fun baseUrl(): String = etUrl.text.toString().trim().trimEnd('/')

    private fun loadBuses() {
        val url = baseUrl()
        if (url.isBlank()) { toast("Ingresá la URL del servidor."); return }
        prefs.edit().putString("baseUrl", url).apply()
        tvStatus.text = "Cargando lista de ómnibus…"
        Thread {
            try {
                val conn = (URL("$url/api/config").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000; readTimeout = 10000
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val json = JSONObject(body)
                val linesArr = json.getJSONArray("lines")
                val lineName = HashMap<String, String>()
                for (i in 0 until linesArr.length()) {
                    val l = linesArr.getJSONObject(i)
                    lineName[l.getString("id")] = l.optString("name", l.getString("id"))
                }
                val busesArr = json.getJSONArray("buses")
                val ids = ArrayList<String>()
                val labels = ArrayList<String>()
                for (i in 0 until busesArr.length()) {
                    val b = busesArr.getJSONObject(i)
                    val id = b.getString("id")
                    val plate = b.optString("plate", id)
                    val ln = lineName[b.optString("lineId", "")] ?: ""
                    ids.add(id)
                    labels.add("$id — $plate" + if (ln.isNotBlank()) " ($ln)" else "")
                }
                runOnUiThread {
                    busIds = ids
                    val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    spinner.adapter = adapter
                    val last = prefs.getString("busId", null)
                    val idx = ids.indexOf(last)
                    if (idx >= 0) spinner.setSelection(idx)
                    tvStatus.text = "Listo. Elegí tu ómnibus y tocá Iniciar."
                    btnToggle.isEnabled = ids.isNotEmpty()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "No se pudo cargar la lista. Revisá la URL y la conexión."
                    btnToggle.isEnabled = false
                }
            }
        }.start()
    }

    private fun startTracking() {
        if (busIds.isEmpty()) { toast("Primero cargá la lista de ómnibus."); return }
        val need = ArrayList<String>()
        if (!hasFine()) need.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)

        if (need.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, need.toTypedArray(), REQ_FOREGROUND)
        } else {
            requestBackgroundThenStart()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_FOREGROUND -> {
                if (hasFine()) requestBackgroundThenStart()
                else toast("Sin permiso de ubicación no se puede transmitir.")
            }
            REQ_BACKGROUND -> actuallyStart() // arranca aunque lo nieguen (funciona con la app abierta)
        }
    }

    private fun requestBackgroundThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            toast("Elegí \"Permitir todo el tiempo\" para que transmita en segundo plano.")
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BACKGROUND
            )
        } else actuallyStart()
    }

    private fun actuallyStart() {
        val busId = busIds.getOrNull(spinner.selectedItemPosition) ?: return
        prefs.edit().putString("busId", busId).apply()
        val intent = Intent(this, LocationService::class.java)
            .putExtra(LocationService.EXTRA_BASE_URL, baseUrl())
            .putExtra(LocationService.EXTRA_BUS_ID, busId)
        ContextCompat.startForegroundService(this, intent)
        running = true
        btnToggle.text = "Detener transmisión"
        spinner.isEnabled = false
        etUrl.isEnabled = false
        btnLoad.isEnabled = false
        tvStatus.text = "Transmitiendo $busId…"
    }

    private fun stopTracking() {
        stopService(Intent(this, LocationService::class.java))
        running = false
        btnToggle.text = "Iniciar transmisión"
        spinner.isEnabled = true
        etUrl.isEnabled = true
        btnLoad.isEnabled = true
        tvStatus.text = "Transmisión detenida."
    }

    private fun hasFine(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
