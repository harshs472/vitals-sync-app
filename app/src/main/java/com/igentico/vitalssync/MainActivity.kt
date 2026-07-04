package com.igentico.vitalssync

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.request.AggregateRequest
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

    private val permissionLauncher = registerForActivityResult(
        androidx.health.connect.client.permission.HealthPermission.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(PERMISSIONS)) {
            setStatus("Permissions granted. Tap sync.")
        } else {
            setStatus("❌ Permissions denied. Please grant in Health Connect.")
        }
    }

    companion object {
        val PERMISSIONS = setOf(
            androidx.health.connect.client.permission.HealthPermission.getReadPermission(HeartRateRecord::class),
            androidx.health.connect.client.permission.HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            androidx.health.connect.client.permission.HealthPermission.getReadPermission(StepsRecord::class),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnSync  = findViewById(R.id.btnSync)
        tvStatus = findViewById(R.id.tvStatus)
        tvHR     = findViewById(R.id.tvHR)
        tvSpO2   = findViewById(R.id.tvSpO2)
        tvSteps  = findViewById(R.id.tvSteps)

        val availability = HealthConnectClient.getSdkStatus(this)
        if (availability != HealthConnectClient.SDK_AVAILABLE) {
            setStatus("❌ Health Connect not available on this device.")
            btnSync.isEnabled = false
            return
        }

        healthConnectClient = HealthConnectClient.getOrCreate(this)

        btnSync.setOnClickListener { syncVitals() }

        // Check permissions on launch
        CoroutineScope(Dispatchers.Main).launch {
            val granted = healthConnectClient.permissionController.getGrantedPermissions()
            if (!granted.containsAll(PERMISSIONS)) {
                permissionLauncher.launch(PERMISSIONS)
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

                // Read Heart Rate
                val hrResponse = healthConnectClient.readRecords(
                    ReadRecordsRequest(HeartRateRecord::class, timeRange)
                )
                val latestHR = hrResponse.records
                    .flatMap { it.samples }
                    .maxByOrNull { it.time }
                    ?.beatsPerMinute

                // Read SpO2
                val spo2Response = healthConnectClient.readRecords(
                    ReadRecordsRequest(OxygenSaturationRecord::class, timeRange)
                )
                val latestSpO2 = spo2Response.records
                    .maxByOrNull { it.time }
                    ?.percentage?.value

                // Read Steps (today total)
                val stepsResponse = healthConnectClient.aggregate(
                    AggregateRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = timeRange
                    )
                )
                val totalSteps = stepsResponse[StepsRecord.COUNT_TOTAL]

                // Build JSON payload
                val payload = JSONObject().apply {
                    val hrArray = JSONArray()
                    if (latestHR != null) {
                        hrArray.put(JSONObject().put("bpm", latestHR))
                    }
                    put("heart_rate", hrArray)

                    val spo2Array = JSONArray()
                    if (latestSpO2 != null) {
                        spo2Array.put(JSONObject().put("percentage", latestSpO2))
                    }
                    put("oxygen_saturation", spo2Array)

                    val stepsArray = JSONArray()
                    if (totalSteps != null) {
                        stepsArray.put(JSONObject().put("count", totalSteps))
                    }
                    put("steps", stepsArray)
                }

                // POST to backend
                val url = URL(WEBHOOK_URL)
                val conn = url.openConnection() as HttpURLConnection
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

                    if (responseCode == 200) {
                        setStatus("✅ Synced to dashboard!")
                    } else {
                        setStatus("⚠️ Server returned $responseCode")
                    }
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
        tvStatus.text = msg
    }
}
