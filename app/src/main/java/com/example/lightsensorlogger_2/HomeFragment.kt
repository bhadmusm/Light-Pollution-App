package com.example.lightsensorlogger_2

import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment

class HomeFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)

        // Container to dynamically add styled text
        val containerView = view.findViewById<LinearLayout>(R.id.light_level_info_container)

        // Lux data
        val luxDescriptions = listOf(
            "1 lux" to "Moonlight",
            "50 lux" to "Living room with low lighting",
            "500 lux" to "Bright office lighting",
            "1000 lux or more" to "Direct sunlight"
        )

        // Dynamically create TextViews for each lux-description pair
        for ((lux, description) in luxDescriptions) {
            val textView = TextView(requireContext())
            val spannableString = SpannableString("$lux: $description")

            // Apply bold style to the lux part
            spannableString.setSpan(
                StyleSpan(android.graphics.Typeface.BOLD),
                0,
                lux.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            textView.text = spannableString
            textView.textSize = 14f
            textView.setTextColor(ResourcesCompat.getColor(resources, android.R.color.black, null))
            textView.setPadding(0, 0, 0, 16) // Add spacing below each line

            containerView.addView(textView)
        }

        return view
    }
}
