package com.igentico.vitalssync

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.temporal.ChronoUnit

class MainActivity : AppCompatActivity() {

    private val WEBHOOK_URL = "https://api.igentico.com/vitals/update-android?phone=918630164076"

    private lateinit var btnSync: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvHR: TextView
    private lateinit var tvSpO2: TextView
    private lateinit var tvSteps: TextView
    private lateinit var healthConnectClient: HealthConnectClient

    companion object {
        val PERMISSIONS = setOf(
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
        )
    }

    private val requestPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            if (granted.containsAll(PERMISSIONS)) {
                setStatus("✅ Permissions granted. Tap sync.")
            } else {
                setStatus("❌ Permissions denied. Grant in Health Connect settings.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnSync  = findViewById(R.id.btnSync)
        tvStatus = findViewById(R.id.tvStatus)
        tvHR     = findViewById(R.id.tvHR)
        tvSpO2   = findViewById(R.id.tvSpO2)
        tvSteps  = findViewById(R.id.tvSteps)

        val availability = HealthConnectClient.getSdkStatus(this, "com.google.android.apps.healthdata")
        if (availability != HealthConnectClient.SDK_AVAILABLE) {
            setStatus("❌ Health Connect not available on this device.")
            btnSync.isEnabled = false
            return
        }

        healthConnectClient = HealthConnectClient.getOrCreate(this)
        btnSync.setOnClickListener { syncVitals() }

        CoroutineScope(Dispatchers.Main).launch {
            val granted = healthConnectClient.permissionController.getGrantedPermissions()
            if (!granted.containsAll(PERMISSIONS)) {
                requestPermissions.launch(PERMISSIONS)
            } else {
                setStatus("Ready. Tap to sync.")
            }
        }
    }

    private fun syncVitals() {
        btnSync.isEnabled = false
        setStatus("⏳ Reading Health Connect...")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val now = Instant.now()
                val since = now.minus(24, ChronoUnit.HOURS)
                val timeRange = TimeRangeFilter.between(since, now)

                // Heart Rate
                val hrRecords = healthConnectClient.readRecords(
                    ReadRecordsRequest(HeartRateRecord::class, timeRange)
                ).records
                val latestHR = hrRecords.flatMap { it.samples }
                    .maxByOrNull { it.time }?.beatsPerMinute

                // SpO2
                val spo2Records = healthConnectClient.readRecords(
                    ReadRecordsRequest(OxygenSaturationRecord::class, timeRange)
                ).records
                val latestSpO2 = spo2Records.maxByOrNull { it.time }?.percentage?.value

                // Steps
                val stepsResult = healthConnectClient.aggregate(
                    AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), timeRange)
                )
                val totalSteps = stepsResult[StepsRecord.COUNT_TOTAL]

                // Build payload
                val payload = JSONObject().apply {
                    put("heart_rate", JSONArray().apply {
                        if (latestHR != null) put(JSONObject().put("bpm", latestHR))
                    })
                    put("oxygen_saturation", JSONArray().apply {
                        if (latestSpO2 != null) put(JSONObject().put("percentage", latestSpO2))
                    })
                    put("steps", JSONArray().apply {
                        if (totalSteps != null) put(JSONObject().put("count", totalSteps))
                    })
                }

                // POST
                val conn = URL(WEBHOOK_URL).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.outputStream.write(payload.toString().toByteArray())
                val responseCode = conn.responseCode
                conn.disconnect()

                withContext(Dispatchers.Main) {
                    tvHR.text    = if (latestHR != null) "$latestHR bpm" else "--"
                    tvSpO2.text  = if (latestSpO2 != null) "${"%.1f".format(latestSpO2)}%" else "--"
                    tvSteps.text = totalSteps?.toString() ?: "--"
                    setStatus(if (responseCode == 200) "✅ Synced to dashboard!" else "⚠️ Server error $responseCode")
                    btnSync.isEnabled = true
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setStatus("❌ Error: ${e.message}")
                    btnSync.isEnabled = true
                }
            }
        }
    }

    private fun setStatus(msg: String) {
        runOnUiThread { tvStatus.text = msg }
    }
}
