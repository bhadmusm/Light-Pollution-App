package com.example.lightsensorlogger_2

import android.os.Bundle
import android.text.util.Linkify
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.fragment.app.Fragment

class MapFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_map, container, false)

        // Set up the WebView for the map
        val webView = view.findViewById<WebView>(R.id.map_webview)
        webView.settings.apply {
            javaScriptEnabled = true // Enable JavaScript
            domStorageEnabled = true // Enable DOM storage
            cacheMode = WebSettings.LOAD_NO_CACHE // Avoid caching issues
            setSupportZoom(true) // Allow zooming
            builtInZoomControls = true // Add zoom controls
            displayZoomControls = false // Hide zoom buttons for a clean UI
        }

        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient() // For handling advanced web features
        webView.loadUrl("https://www.lightpollutionmap.info/")

        // Set up the disclaimer text
        val disclaimerText = view.findViewById<TextView>(R.id.map_disclaimer)
        disclaimerText.text =
            "Disclaimer: This Light Pollution Map is provided by LightPollutionMap.info. For more information, visit the website."
        Linkify.addLinks(disclaimerText, Linkify.WEB_URLS) // Make the link clickable

        return view
    }
}
