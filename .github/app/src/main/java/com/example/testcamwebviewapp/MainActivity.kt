package com.example.testcamwebviewapp

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private val targetUrl = "https://testcam-ockys-projects-be0f0c62.vercel.app/"

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            // After user decision, just (re)load the page
            webView.reload()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        val ws = webView.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.mediaPlaybackRequiresUserGesture = false
        ws.loadWithOverviewMode = true
        ws.useWideViewPort = true
        ws.builtInZoomControls = false
        ws.allowFileAccess = true
        ws.allowContentAccess = true
        // Geolocation
        try {
            val clazz = Class.forName("android.webkit.WebSettings")
            val method = clazz.getMethod("setGeolocationEnabled", Boolean::class.javaPrimitiveType)
            method.invoke(ws, true)
        } catch (_: Exception) {}

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // Grant camera/mic if runtime permissions already granted
                val resources = request.resources
                val grants = resources.map { res ->
                    when (res) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                            ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                            ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        else -> true
                    }
                }
                if (grants.all { it }) {
                    request.grant(request.resources)
                } else {
                    request.deny()
                    askRuntimePermissions()
                }
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                // If location runtime permission is granted, allow geolocation for WebView
                val granted = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                callback?.invoke(origin, granted, false)
                if (!granted) askRuntimePermissions()
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false // load inside WebView
            }
        }

        askRuntimePermissions()
        webView.loadUrl(targetUrl)
    }

    private fun askRuntimePermissions() {
        val needs = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needs.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needs.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needs.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needs.isNotEmpty()) {
            requestPermissionsLauncher.launch(needs.toTypedArray())
        }
    }

    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}