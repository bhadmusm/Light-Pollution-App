package com.example.lightsensorlogger_2

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.firebase.database.FirebaseDatabase
import com.google.android.gms.tasks.Tasks
import com.opencsv.CSVReader
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

// class to fetch data directly from websites for testing
class DataFetchWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {

    private val database = FirebaseDatabase.getInstance("https://light-pollution-app-default-rtdb.europe-west1.firebasedatabase.app/").reference
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun doWork(): Result {
        try {

            //removeAllOpenDataEntries()


            //fetchHistoricalData("2024-11-20 00:00:00", "2024-11-27 23:00:00")

            // fetching the CSV data
            val csvData = fetchCSVData()
            if (csvData.isNotEmpty()) {
                // processing the CSV data
                processCSVData(csvData)
            } else {
                Log.e("DataFetchWorker", "Fetched CSV data is empty.")
            }
            return Result.success()
        } catch (e: Exception) {
            Log.e("DataFetchWorker", "Error in doWork(): ${e.message}")
            e.printStackTrace()
            return Result.retry()
        }
    }

    private fun removeAllOpenDataEntries() {
        val task = database.child("open_data").removeValue()
        Tasks.await(task)
        Log.d("DataFetchWorker", "All entries under 'open_data' have been removed.")
    }

    private fun fetchCSVData(): String {
        val request = Request.Builder()
            .url("http://www.unihedron.com/projects/darksky/database/index.php?csv=true")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Unexpected code $response")

            val htmlContent = response.body?.string() ?: ""
            val doc = Jsoup.parse(htmlContent)
            val preElement = doc.selectFirst("pre")

            if (preElement != null) {
                val csvData = preElement.text()
                return csvData
            } else {
                throw Exception("CSV data not found in the page content.")
            }
        }
    }

    private fun processCSVData(csvData: String) {
        try {
            val reader = CSVReader(StringReader(csvData))
            val entries = reader.readAll()
            reader.close()

            if (entries.isNotEmpty()) {
                val headers = entries[0]
                val dataRows = entries.subList(1, entries.size)

                // fetch existing data from Firebase to check for duplicates
                val existingData = fetchExistingData()

                // taking X most top rows
                val topDataRows = dataRows.take(96)

                Log.d("DataFetchWorker", "Processing ${topDataRows.size} entries.")

                // adding entries to firebase
                for (row in topDataRows) {
                    val dataMap = parseRow(headers, row).toMutableMap()

                    // check for duplicates
                    val uniqueIdentifier = dataMap["UT_datetime"] ?: ""
                    if (uniqueIdentifier.isNotEmpty() && existingData.contains(uniqueIdentifier)) {
                        Log.d("DataFetchWorker", "Skipping duplicate entry with timestamp: $uniqueIdentifier")
                        continue
                    }

                    // negative timestamp to order in reverse
                    val timestamp = System.currentTimeMillis()
                    dataMap["negative_timestamp"] = (-timestamp).toString()


                    val key = "key_${-timestamp}"
                    database.child("open_data").child(key).setValue(dataMap)
                    Log.d("DataFetchWorker", "Added data with timestamp: ${dataMap["UT_datetime"]}")
                }
            } else {
                Log.e("DataFetchWorker", "No entries found in CSV data.")
            }
        } catch (e: Exception) {
            Log.e("DataFetchWorker", "Error parsing CSV data: ${e.message}")
            e.printStackTrace()
        }
    }

    // function to fetch existing data from Firebase
    private fun fetchExistingData(): Set<String> {
        val existingData = mutableSetOf<String>()

        try {
            val task = database.child("open_data").get()
            val snapshot = Tasks.await(task)

            if (snapshot.exists()) {
                for (child in snapshot.children) {
                    val utDatetime = child.child("UT_datetime").value as? String
                    if (utDatetime != null) {
                        existingData.add(utDatetime)
                    }
                }
            }

            Log.d("DataFetchWorker", "Fetched ${existingData.size} existing entries from Firebase.")
        } catch (e: Exception) {
            Log.e("DataFetchWorker", "Error fetching existing data: ${e.message}")
            e.printStackTrace()
        }

        return existingData
    }



    private fun parseRow(headers: Array<String>, row: Array<String>): Map<String, String> {
        val dataMap = mutableMapOf<String, String>()
        for (i in headers.indices) {
            val key = headers[i]
            val value = if (i < row.size) row[i].trim() else ""
            dataMap[key] = value
        }
        return dataMap
    }

    private fun fetchHistoricalData(startDate: String, endDate: String) {
        try {
            val startDateTime = parseDate(startDate)
            val endDateTime = parseDate(endDate)

            var currentDateTime = startDateTime
            val intervalHours = 1

            while (currentDateTime <= endDateTime) {
                val formattedTime = formatDateTime(currentDateTime)
                val apiUrl = "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=53.3498&lon=-6.2603"


                val request = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", "LightSensorLogger2/1.0 (bhadmusm@tcd.ie)")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e("DataFetchWorker", "Error fetching data for $formattedTime: ${response.code}")
                        return@use
                    }

                    val responseBody = response.body?.string()
                    if (!responseBody.isNullOrEmpty()) {
                        val data = parseWeatherData(responseBody, formattedTime)
                        saveDataToFirebase(data)
                    } else {
                        Log.e("DataFetchWorker", "No data received for $formattedTime")
                    }
                }

                // Increment the time by 1 hour
                currentDateTime += 3600000
            }
        } catch (e: Exception) {
            Log.e("DataFetchWorker", "Error in fetchHistoricalData(): ${e.message}")
            e.printStackTrace()
        }
    }

    private fun parseWeatherData(responseBody: String, time: String): Map<String, Any> {
        val dataMap = mutableMapOf<String, Any>()
        val jsonObject = JSONObject(responseBody)

        val timeseries = jsonObject.getJSONObject("properties").getJSONArray("timeseries")
        for (i in 0 until timeseries.length()) {
            val entry = timeseries.getJSONObject(i)
            val entryTime = entry.getString("time")
            if (entryTime.startsWith(time)) {
                val cloudCover = entry.getJSONObject("data").getJSONObject("instant").getJSONObject("details").optDouble("cloud_area_fraction", 0.0)

                dataMap["time"] = time
                dataMap["cloud_area_fraction"] = cloudCover
                dataMap["timestamp"] = System.currentTimeMillis()
                break
            }
        }
        return dataMap
    }

    private fun saveDataToFirebase(data: Map<String, Any>) {
        val uniqueKey = "key_${-System.currentTimeMillis()}"
        database.child("cloud_data").child(uniqueKey).setValue(data)
        Log.d("DataFetchWorker", "Saved data to Firebase: $data")
    }

    private fun parseDate(dateString: String): Long {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.parse(dateString)?.time ?: 0L
    }

    private fun formatDateTime(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(timestamp))
    }

}

