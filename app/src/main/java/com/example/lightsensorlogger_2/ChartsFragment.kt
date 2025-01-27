package com.example.lightsensorlogger_2

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.*

// Class to display different real-time charts depending on day or week selected
class ChartsFragment : Fragment() {

    private lateinit var lineChart: LineChart
    private lateinit var database: DatabaseReference
    private lateinit var buttonLightSensorChart: Button
    private lateinit var buttonOpenDataChart: Button
    private lateinit var buttonPastDay: Button
    private lateinit var buttonPastWeek: Button
    private lateinit var textCurrentLocation: TextView

    private var selectedDataType: String? = null
    private var entriesLimit: Int = 24

    @SuppressLint("MissingInflatedId")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {

        val view = inflater.inflate(R.layout.fragment_charts, container, false)

        lineChart = view.findViewById(R.id.line_chart)
        buttonLightSensorChart = view.findViewById(R.id.button_light_sensor_chart)
        buttonOpenDataChart = view.findViewById(R.id.button_open_data_chart)
        buttonPastDay = view.findViewById(R.id.button_past_day)
        buttonPastWeek = view.findViewById(R.id.button_past_week)
        textCurrentLocation = view.findViewById(R.id.text_current_location)

        database = FirebaseDatabase.getInstance(
            "https://light-pollution-app-default-rtdb.europe-west1.firebasedatabase.app/"
        ).reference

        setUpChart()

        // Managing the different chart selection buttons
        buttonLightSensorChart.setOnClickListener {
            selectedDataType = "light_sensor_data"
            fetchChartData(selectedDataType!!)
            lineChart.visibility = View.VISIBLE
            textCurrentLocation.visibility = View.VISIBLE
        }

        buttonOpenDataChart.setOnClickListener {
            selectedDataType = "open_data"
            fetchChartData(selectedDataType!!)
            lineChart.visibility = View.VISIBLE
            textCurrentLocation.visibility = View.VISIBLE
        }

        buttonPastDay.setOnClickListener {
            if (selectedDataType != null) {
                entriesLimit = 24
                fetchChartData(selectedDataType!!)
            }
        }

        buttonPastWeek.setOnClickListener {
            if (selectedDataType != null) {
                entriesLimit = 168
                fetchChartData(selectedDataType!!)
            }
        }

        return view
    }

    private fun setUpChart() {
        lineChart.apply {
            setExtraOffsets(10f, 10f, 10f, 100f)
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                granularity = 1f
                isGranularityEnabled = true
                setDrawGridLines(false)
                textColor = android.graphics.Color.BLACK
                textSize = 12f
                //setAvoidFirstLastClipping(true)
            }

            axisLeft.textColor = android.graphics.Color.BLACK
            axisRight.isEnabled = false
            description.isEnabled = false
            legend.isEnabled = true
        }
    }

    private fun fetchChartData(dataType: String) {
        val dataRef = database.child(dataType)
        dataRef.orderByKey().limitToLast(entriesLimit)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val entries = mutableListOf<Entry>()
                    val xAxisLabels = mutableListOf<String>()
                    val dateFormatter =
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

                    var location: String? = null

                    snapshot.children.forEachIndexed { index, child ->
                        try {
                            // Fetching and validating the datetime field
                            val dateTime = when (dataType) {
                                "light_sensor_data" -> {
                                    val date = child.child("date").getValue(String::class.java)
                                        ?: return@forEachIndexed
                                    val time = child.child("time").getValue(String::class.java)
                                        ?: return@forEachIndexed
                                    "$date $time"
                                }
                                "open_data" -> child.child("UT_datetime")
                                    .getValue(String::class.java)
                                    ?: return@forEachIndexed
                                else -> return@forEachIndexed
                            }

                            val value = when (dataType) {
                                "light_sensor_data" -> child.child("light_level")
                                    .getValue(Float::class.java) ?: return@forEachIndexed
                                "open_data" -> {
                                    val brightnessValue = child.child("Brightness").value
                                    when (brightnessValue) {
                                        is String -> brightnessValue.toFloatOrNull()
                                            ?: return@forEachIndexed
                                        is Number -> brightnessValue.toFloat()
                                        else -> return@forEachIndexed
                                    }
                                }
                                else -> return@forEachIndexed
                            }

                            // Parsing the datetime and adding to chart entries
                            val timestamp =
                                dateFormatter.parse(dateTime)?.time ?: return@forEachIndexed
                            entries.add(Entry(index.toFloat(), value))
                            xAxisLabels.add(dateTime) // Store date-time label

                            // Get location data (if available)
                            if (location == null) {
                                when (dataType) {
                                    "light_sensor_data" -> {
                                        val latitude =
                                            child.child("latitude").getValue(Double::class.java)
                                        val longitude =
                                            child.child("longitude").getValue(Double::class.java)
                                        if (latitude != null && longitude != null) {
                                            location =
                                                "Lat: $latitude, Lon: $longitude"
                                        } else {
                                            location = "Unknown Location"
                                        }
                                    }
                                    "open_data" -> {
                                        val locationName =
                                            child.child("name").getValue(String::class.java)
                                        if (locationName != null) {
                                            location = locationName
                                        } else {
                                            val latitude =
                                                child.child("latitude")
                                                    .getValue(Double::class.java)
                                            val longitude =
                                                child.child("longitude")
                                                    .getValue(Double::class.java)
                                            if (latitude != null && longitude != null) {
                                                location =
                                                    "Lat: $latitude, Lon: $longitude"
                                            } else {
                                                location = "Unknown Location"
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    updateChart(entries, dataType, xAxisLabels)

                    // Update the location TextView
                    if (location != null) {
                        textCurrentLocation.text = "Current Location: $location"
                    } else {
                        textCurrentLocation.text = "Current Location: Unknown"
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("ChartsFragment", "Database error: ${error.message}")
                }
            })
    }

    private fun updateChart(entries: List<Entry>, label: String, xAxisLabels: List<String>) {
        if (entries.isNotEmpty()) {
            val dataSet = LineDataSet(entries, "$label Chart").apply {
                color = android.graphics.Color.BLUE
                valueTextColor = android.graphics.Color.BLACK
                setDrawCircles(false)
                lineWidth = 2f
            }

            lineChart.data = LineData(dataSet)

            // Showing date-time format only for data points
            lineChart.xAxis.valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val index = value.toInt()
                    return if (index in xAxisLabels.indices) xAxisLabels[index] else ""
                }
            }

            lineChart.invalidate() // Refresh the chart
        }
    }
}
