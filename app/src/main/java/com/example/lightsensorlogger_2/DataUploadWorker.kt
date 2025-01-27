package com.example.lightsensorlogger_2

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import androidx.core.app.ActivityCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.*

// class to upload data from sensor app in background

class DataUploadWorker(context: Context, params: WorkerParameters) : Worker(context, params),
    SensorEventListener {

    private val database = FirebaseDatabase.getInstance("https://light-pollution-app-default-rtdb.europe-west1.firebasedatabase.app/").reference
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    private var latestLightLevel: Float = 0f
    private var latitude: Double = 0.0
    private var longitude: Double = 0.0
    private var areaName: String = "Unknown area"

    override fun doWork(): Result {
        // get the latest light level
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        // get last location and update firebase
        getLastLocation {
            uploadDataToFirebase()
        }

        // unregister the sensor listener after data is fetched
        sensorManager.unregisterListener(this)

        return Result.success()
    }

    private fun getLastLocation(onLocationReady: () -> Unit) {
        if (ActivityCompat.checkSelfPermission(
                applicationContext, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != androidx.core.content.PermissionChecker.PERMISSION_GRANTED
        ) {
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            location?.let {
                latitude = it.latitude
                longitude = it.longitude
            }
            onLocationReady()
        }
    }

    private fun uploadDataToFirebase() {
        val timestamp = System.currentTimeMillis()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        val data = mapOf(
            "date" to dateFormat.format(Date(timestamp)),
            "time" to timeFormat.format(Date(timestamp)),
            "latitude" to latitude,
            "longitude" to longitude,
            "area_name" to areaName,
            "light_level" to latestLightLevel
        )

        database.child("light_sensor_data").push().setValue(data)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LIGHT) {
            latestLightLevel = event.values[0]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {

    }
}
