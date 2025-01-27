package com.example.lightsensorlogger_2

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.*
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.fragment.app.Fragment
import java.io.File
import java.io.FileInputStream

class ExportCSVFragment : Fragment() {

    private val csvFileName = "light_sensor_data.csv"

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_export_csv, container, false)

        // Explanation and data types text (Static content already defined in XML)
        val exportExplanationText = view.findViewById<TextView>(R.id.export_explanation_text)
        val dataTypesText = view.findViewById<TextView>(R.id.data_types_text)

        val exportButton = view.findViewById<Button>(R.id.export_button)
        exportButton.setOnClickListener {
            saveCSVToDownloads()
        }

        return view
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveCSVToDownloads() {
        val context = requireContext()
        val file = File(context.filesDir, csvFileName)

        if (!file.exists()) {
            Toast.makeText(context, "CSV file not found", Toast.LENGTH_SHORT).show()
            return
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, csvFileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

        uri?.let {
            resolver.openOutputStream(it)?.use { outputStream ->
                FileInputStream(file).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
                Toast.makeText(context, "CSV file saved to Downloads", Toast.LENGTH_SHORT).show()
            } ?: run {
                Toast.makeText(context, "Failed to save CSV file", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
