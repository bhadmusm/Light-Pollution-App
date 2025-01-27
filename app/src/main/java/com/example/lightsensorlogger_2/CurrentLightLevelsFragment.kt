package com.example.lightsensorlogger_2

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.database.*
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import android.app.AlertDialog

class CurrentLightLevelsFragment : Fragment(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null
    private lateinit var lightLevelText: TextView
    private lateinit var locationText: TextView
    private lateinit var cloudCoverText: TextView
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var areaName: String = "Unknown area"
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var runnable: Runnable

    private var latestLightLevel: Float = -1f
    private var latestTimestamp: Long = -1L

    private var latitude: Double = 0.0
    private var longitude: Double = 0.0

    private lateinit var averageLightLevelText: TextView

    private lateinit var database: DatabaseReference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // initialize Firebase Database
        database = FirebaseDatabase.getInstance("https://light-pollution-app-default-rtdb.europe-west1.firebasedatabase.app/").reference
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {

        val view = inflater.inflate(R.layout.fragment_current_light_levels, container, false)

        lightLevelText = view.findViewById(R.id.light_level_text)
        locationText = view.findViewById(R.id.location_text)
        cloudCoverText = view.findViewById(R.id.cloud_cover_text)
        averageLightLevelText = view.findViewById(R.id.average_light_level_text)


        sensorManager = requireActivity().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

        // register sensor listener
        if (lightSensor != null) {
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            lightLevelText.text = "Ambient Light Sensor not available"
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        fetchCloudData() // Fetch cloud cover data
        fetchAverageLightLevel()
        return view
    }

    override fun onResume() {
        super.onResume()
        startRepeatingTask()
    }

    override fun onPause() {
        super.onPause()
        stopRepeatingTask()
    }

    private fun startRepeatingTask() {
        runnable = object : Runnable {
            override fun run() {
                getLastLocation {
                    // callback after location is fetched
                    sendDataToFirebase()
                }
                fetchCloudData() // Update cloud data periodically
                handler.postDelayed(this, 60000) // Run every 60 seconds
            }
        }
        handler.post(runnable)
    }

    private fun stopRepeatingTask() {
        handler.removeCallbacks(runnable)
    }

    private fun fetchCloudData() {
        val cloudDataRef = database.child("cloud_data")
        cloudDataRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val cloudCover = snapshot.child("cloud_area_fraction").getValue(Float::class.java) ?: 100f
                val time = snapshot.child("time").getValue(String::class.java) ?: "22:00"

                // Extract time from timestamp (HH:mm:ss format)
                val timePart = if (time.contains(" ")) time.split(" ")[1] else time
                cloudCoverText.text = "Cloud Cover: ${cloudCover.toInt()}% at $timePart"
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("CurrentLightLevelsFragment", "Failed to fetch cloud data: ${error.message}")
            }
        })
    }

    private fun fetchAverageLightLevel() {
        val aggregatedDataRef = database.child("aggregated_data").child("gc7rw")
        aggregatedDataRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    // Find the most recent child based on the key
                    val latestEntry = snapshot.children.maxByOrNull { it.key ?: "" }

                    if (latestEntry != null) {
                        val averageLightLevel = latestEntry.child("averageLightLevel").getValue(Float::class.java) ?: -1f
                        if (averageLightLevel >= 0) {
                            averageLightLevelText.text = "Average light level for area: $averageLightLevel lux"
                        } else {
                            averageLightLevelText.text = "The average light levels for this area: Not available"
                        }
                    } else {
                        averageLightLevelText.text = "The average light levels for this area: No data available"
                    }
                } else {
                    averageLightLevelText.text = "The average light levels for this area: No data available"
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("CurrentLightLevelsFragment", "Failed to fetch average light level: ${error.message}")
            }
        })
    }


    private fun getLastLocation(onLocationUpdated: () -> Unit) {
        if (ActivityCompat.checkSelfPermission(
                requireContext(), android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                requireContext(), android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(requireActivity(),
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 100)
            return
        }

        fusedLocationClient.lastLocation
            .addOnSuccessListener { location: Location? ->
                location?.let {
                    latitude = it.latitude
                    longitude = it.longitude

                    // reverse geocoding to get area name
                    val geocoder = Geocoder(requireContext(), Locale.getDefault())
                    try {
                        val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                        if (addresses != null && addresses.isNotEmpty()) {
                            areaName = addresses[0]?.locality ?: addresses[0].subAdminArea ?: addresses[0].adminArea ?: "Unknown area"
                        } else {
                            areaName = "Unknown area"
                        }
                    } catch (e: IOException) {
                        e.printStackTrace()
                        areaName = "Unknown area"
                    }

                    locationText.text = "Location: $areaName"
                }
                onLocationUpdated() // call callback after location update
            }
    }

    private fun sendDataToFirebase() {
        if (latestTimestamp <= 0 || latestLightLevel < 0f) {
            Log.e("CurrentLightLevelsFragment", "Invalid timestamp or light level. Data not sent.")
            return
        }

        if (latitude == 0.0 && longitude == 0.0) {
            Log.e("CurrentLightLevelsFragment", "Invalid GPS coordinates. Data not sent.")
            return
        }

        val data = mapOf(
            "date" to SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(latestTimestamp)),
            "time" to SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(latestTimestamp)),
            "latitude" to latitude,
            "longitude" to longitude,
            "area_name" to areaName,
            "light_level" to latestLightLevel
        )

        database.child("light_sensor_data").push().setValue(data)
            .addOnSuccessListener {
                Log.d("CurrentLightLevelsFragment", "Data successfully written to Firebase.")
            }
            .addOnFailureListener {
                Log.e("CurrentLightLevelsFragment", "Failed to write data to Firebase: ${it.message}")
            }
    }


    private var lastAlertTimestamp: Long = 0 // To track the last time an alert was shown
    private val alertCooldown: Long = 5000 // Cooldown period in milliseconds (e.g., 1 minute)

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LIGHT) {
            val lightLevel = event.values[0]
            latestLightLevel = lightLevel
            latestTimestamp = System.currentTimeMillis()

            lightLevelText.text = "Current Light Level: $lightLevel lux"

            // Trigger an alert if light level exceeds 200 lux and cooldown has elapsed
            if (lightLevel > 200 && System.currentTimeMillis() - lastAlertTimestamp > alertCooldown) {
                lastAlertTimestamp = System.currentTimeMillis()
                showAlert(lightLevel)
            }
        }
    }

    private fun showAlert(lightLevel: Float) {
        if (!isAdded) return

        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("High Light Level Detected!")
        builder.setMessage(
            "The measured light level is $lightLevel lux, which is unusually high for typical outdoor light levels. This may indicate artificial light pollution or bright environmental conditions."
        )
        builder.setPositiveButton("OK") { dialog, _ ->
            dialog.dismiss()
        }
        builder.create().show()
    }




    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }

    override fun onDestroyView() {
        super.onDestroyView()
        sensorManager.unregisterListener(this)
        stopRepeatingTask()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 100) {
            if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                getLastLocation { }
            } else {
                Toast.makeText(requireContext(), "Location permission is required", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
