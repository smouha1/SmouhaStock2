package com.smouha.stock

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    // Volatile RAM-only DMart JWT Vault (Never persisted to disk)
    @Volatile
    private var ramDmartJwt: String? = null

    @Volatile
    private var ramWarehouseId: String = "d24a9f96-f6bc-43b0-af78-b7067f0c901c"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        }

        webView = WebView(this)
        setContentView(webView)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false

        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    request.grant(request.resources)
                }
            }
        }

        webView.addJavascriptInterface(SmouhaNativeBridge(this), "SmouhaNative")
        webView.loadUrl("file:///android_asset/index.html")
    }

    inner class SmouhaNativeBridge(private val ctx: Context) {

        @JavascriptInterface
        fun getDeviceId(): String {
            val rawId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "SMOUHA"
            val digest = MessageDigest.getInstance("SHA-256").digest(rawId.toByteArray())
            val hex = digest.joinToString("") { "%02X".format(it) }
            return "SMP-MOB-${hex.substring(0, 4)}-${hex.substring(4, 8)}"
        }

        @JavascriptInterface
        fun setRamToken(jwt: String, warehouseId: String) {
            ramDmartJwt = jwt.ifBlank { null }
            if (warehouseId.isNotBlank()) {
                ramWarehouseId = warehouseId
            }
        }

        @JavascriptInterface
        fun wipeRamToken() {
            ramDmartJwt = null
        }

        @JavascriptInterface
        fun hasRamToken(): Boolean {
            return !ramDmartJwt.isNullOrBlank()
        }

        @JavascriptInterface
        fun getWarehouseId(): String {
            return ramWarehouseId
        }

        @JavascriptInterface
        fun vibrate(patternJson: String) {
            try {
                val arr = JSONArray(patternJson)
                val timings = LongArray(arr.length()) { i -> arr.getLong(i) }
                val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vm.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (timings.size == 1) {
                        vibrator.vibrate(VibrationEffect.createOneShot(timings[0], VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        val padded = LongArray(timings.size + 1)
                        padded[0] = 0L
                        System.arraycopy(timings, 0, padded, 1, timings.size)
                        vibrator.vibrate(VibrationEffect.createWaveform(padded, -1))
                    }
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(timings[0])
                }
            } catch (_: Exception) {}
        }

        /**
         * Saves CSV directly into Android Downloads folder via MediaStore and opens native Share Sheet.
         */
        @JavascriptInterface
        fun saveAndShareCsv(fileName: String, csvContent: String): String {
            return try {
                val safeName = if (fileName.endsWith(".csv")) fileName else "$fileName.csv"
                var savedPath = "Downloads/$safeName"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                        put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        ctx.contentResolver.openOutputStream(uri)?.use { os ->
                            os.write(csvContent.toByteArray(Charsets.UTF_8))
                            os.flush()
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dlDir.mkdirs()
                    val outFile = File(dlDir, safeName)
                    outFile.writeText(csvContent, Charsets.UTF_8)
                    savedPath = outFile.absolutePath
                }

                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, safeName)
                    putExtra(Intent.EXTRA_TEXT, csvContent)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val chooser = Intent.createChooser(sendIntent, "Share Shift CSV ($safeName)").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(chooser)

                JSONObject()
                    .put("ok", true)
                    .put("savedPath", savedPath)
                    .toString()
            } catch (e: Exception) {
                JSONObject()
                    .put("ok", false)
                    .put("error", e.message ?: "SHARE_ERROR")
                    .toString()
            }
        }

        /**
         * Direct Native HTTPS call to https://im-bff-live-me.deliveryhero.io or opd-live-me.deliveryhero.io
         */
        @JavascriptInterface
        fun dmartRequest(method: String, path: String, bodyJson: String): String {
            val token = ramDmartJwt ?: return JSONObject().put("ok", false).put("status", 401).put("error", "NO_RAM_TOKEN").toString()
            return try {
                val fullUrl = if (path.startsWith("https://")) path else "https://im-bff-live-me.deliveryhero.io$path"
                val conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Accept", "application/json, text/plain, */*")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Origin", "https://portal.talabat.com")
                    setRequestProperty("Referer", "https://portal.talabat.com/")
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36")
                }

                if (method == "POST" || method == "PUT") {
                    conn.doOutput = true
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                        writer.write(bodyJson)
                        writer.flush()
                    }
                }

                val status = conn.responseCode
                val stream = (if (status in 200..299) conn.inputStream else conn.errorStream) ?: conn.inputStream
                val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                JSONObject()
                    .put("ok", status in 200..299)
                    .put("status", status)
                    .put("data", text)
                    .toString()
            } catch (e: Exception) {
                JSONObject()
                    .put("ok", false)
                    .put("status", 0)
                    .put("error", e.message ?: "NETWORK_ERROR")
                    .toString()
            }
        }
    }
}
