package com.smouha.stock

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
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
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    // Volatile RAM-only DMart JWT Vault (Never persisted to disk, wiped in onDestroy — RT58-03)
    @Volatile
    private var ramDmartJwt: String? = null

    @Volatile
    private var ramWarehouseId: String = "d24a9f96-f6bc-43b0-af78-b7067f0c901c"

    // Lock-free volatile page origin tracker updated on UI thread in onPageStarted/onPageFinished
    @Volatile
    private var activeTrustedPageUrl: String = ""

    companion object {
        private const val CAMERA_PERMISSION_REQ_CODE = 101
        private const val TRUSTED_ASSET_URL = "file:///android_asset/index.html"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS_LICENSE = "smouha_stock_license_aes_v58"
        private const val PREFS_VAULT_NAME = "smouha_secure_vault_v58"
        private const val PREF_KEY_ENC_LICENSE = "enc_license_blob"
        private val ALLOWED_DMART_HOSTS = setOf(
            "im-bff-live-me.deliveryhero.io",
            "opd-live-me.deliveryhero.io"
        )
        private val ALLOWED_HTTP_METHODS = setOf("GET", "POST", "PUT")
        private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }

    private fun isTrustedLocalAssetOrigin(): Boolean {
        return activeTrustedPageUrl.startsWith(TRUSTED_ASSET_URL)
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Notifies the WebView UI whenever Android system camera permission changes (RT58-02).
     */
    private fun notifyWebViewCameraPermission(granted: Boolean) {
        if (!::webView.isInitialized) return
        val boolLiteral = if (granted) "true" else "false"
        webView.post {
            try {
                if (isTrustedLocalAssetOrigin()) {
                    webView.evaluateJavascript(
                        "if(window.__onNativeCameraPermissionResult){window.__onNativeCameraPermissionResult($boolLiteral);}",
                        null
                    )
                }
            } catch (_: Exception) {}
        }
    }

    private fun getOrCreateHardwareAesKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = ks.getEntry(KEY_ALIAS_LICENSE, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS_LICENSE,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    private fun writeEncryptedLicenseToVault(plainKey: String) {
        val prefs = getSharedPreferences(PREFS_VAULT_NAME, Context.MODE_PRIVATE)
        val clean = plainKey.trim()
        if (clean.isEmpty()) {
            prefs.edit().remove(PREF_KEY_ENC_LICENSE).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateHardwareAesKey())
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                     Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
        prefs.edit().putString(PREF_KEY_ENC_LICENSE, packed).apply()
    }

    private fun readEncryptedLicenseFromVault(): String {
        return try {
            val prefs = getSharedPreferences(PREFS_VAULT_NAME, Context.MODE_PRIVATE)
            val packed = prefs.getString(PREF_KEY_ENC_LICENSE, null) ?: return ""
            val parts = packed.split(":")
            if (parts.size != 2) return ""
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipherBytes = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateHardwareAesKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8).trim()
        } catch (_: Exception) {
            ""
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WebView.setWebContentsDebuggingEnabled(false)

        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQ_CODE)
        }

        webView = WebView(this)
        setContentView(webView)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.safeBrowsingEnabled = true
        }
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                activeTrustedPageUrl = url ?: ""
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                activeTrustedPageUrl = url ?: ""
                super.onPageFinished(view, url)
                notifyWebViewCameraPermission(hasCameraPermission())
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val targetUri = request?.url ?: return true
                return handleNavigationUri(targetUri)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val targetUri = url?.let { Uri.parse(it) } ?: return true
                return handleNavigationUri(targetUri)
            }

            private fun handleNavigationUri(uri: Uri): Boolean {
                val urlStr = uri.toString()
                if (urlStr.startsWith(TRUSTED_ASSET_URL)) {
                    return false
                }
                if (uri.scheme == "https" && uri.userInfo == null && (uri.host == "wa.me" || uri.host == "api.whatsapp.com")) {
                    try {
                        val extIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(extIntent)
                    } catch (_: Exception) {}
                }
                return true
            }
        }

        // RT58-02: Verify both file:// origin AND OS camera permission before granting WebChromeClient request
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val originStr = request.origin?.toString() ?: ""
                    val isLocalAsset = originStr.startsWith("file://")
                    val videoOnly = request.resources.filter { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }.toTypedArray()
                    if (isLocalAsset && isTrustedLocalAssetOrigin() && videoOnly.isNotEmpty()) {
                        if (hasCameraPermission()) {
                            request.grant(videoOnly)
                        } else {
                            request.deny()
                            ActivityCompat.requestPermissions(
                                this@MainActivity,
                                arrayOf(Manifest.permission.CAMERA),
                                CAMERA_PERMISSION_REQ_CODE
                            )
                        }
                    } else {
                        request.deny()
                    }
                }
            }
        }

        activeTrustedPageUrl = TRUSTED_ASSET_URL
        webView.addJavascriptInterface(SmouhaNativeBridge(this), "SmouhaNative")
        webView.loadUrl(TRUSTED_ASSET_URL)
    }

    /**
     * RT58-02: Handle runtime camera permission callback and notify WebView immediately.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQ_CODE) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            notifyWebViewCameraPermission(granted)
        }
    }

    /**
     * RT58-02: Synchronize camera permission state when user returns from Android App Settings.
     */
    override fun onResume() {
        super.onResume()
        notifyWebViewCameraPermission(hasCameraPermission())
    }

    /**
     * RT58-03: Zeroize RAM JWT vault and purge WebView caches upon Activity destruction.
     */
    override fun onDestroy() {
        ramDmartJwt = null
        activeTrustedPageUrl = ""
        try {
            webView.clearCache(true)
            webView.clearHistory()
            webView.removeJavascriptInterface("SmouhaNative")
            webView.destroy()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    inner class SmouhaNativeBridge(private val ctx: Context) {

        @JavascriptInterface
        fun getDeviceId(): String {
            if (!isTrustedLocalAssetOrigin()) return "UNAUTHORIZED"
            val rawId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "SMOUHA"
            val digest = MessageDigest.getInstance("SHA-256").digest(rawId.toByteArray())
            val hex = digest.joinToString("") { "%02X".format(it) }
            return "SMP-MOB-${hex.substring(0, 4)}-${hex.substring(4, 8)}"
        }

        @JavascriptInterface
        fun ensureCameraPermission(): Boolean {
            if (!isTrustedLocalAssetOrigin()) return false
            if (hasCameraPermission()) return true
            ActivityCompat.requestPermissions(
                this@MainActivity,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQ_CODE
            )
            return false
        }

        @JavascriptInterface
        fun saveEncryptedLicenseKey(key: String): Boolean {
            if (!isTrustedLocalAssetOrigin()) return false
            return try {
                writeEncryptedLicenseToVault(key)
                true
            } catch (_: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun getEncryptedLicenseKey(): String {
            if (!isTrustedLocalAssetOrigin()) return ""
            return readEncryptedLicenseFromVault()
        }

        @JavascriptInterface
        fun shareWhatsAppText(text: String): Boolean {
            if (!isTrustedLocalAssetOrigin()) return false
            val cleanText = text.trim()
            if (cleanText.isEmpty()) return false
            return try {
                val encoded = Uri.encode(cleanText)
                val waIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=$encoded")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    ctx.startActivity(waIntent)
                } catch (_: Exception) {
                    val fallbackIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, cleanText)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val chooser = Intent.createChooser(fallbackIntent, "Share Shift Summary").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(chooser)
                }
                true
            } catch (_: Exception) {
                false
            }
        }

        /**
         * V511-SEC-01: Idempotently strips any leading "Bearer " prefixes (even if duplicated by upstream)
         * before enforcing strict 8192-char length and zero internal whitespace/CRLF header-injection bounds.
         */
        @JavascriptInterface
        fun setRamToken(jwt: String, warehouseId: String) {
            if (!isTrustedLocalAssetOrigin()) return
            var cleanJwt = jwt.trim()
            while (cleanJwt.startsWith("Bearer ", ignoreCase = true) || cleanJwt.startsWith("Bearer\t", ignoreCase = true)) {
                cleanJwt = cleanJwt.substring(7).trim()
            }
            if (cleanJwt.isNotEmpty() &&
                cleanJwt.length <= 8192 &&
                !cleanJwt.contains(' ') &&
                !cleanJwt.contains('\t') &&
                !cleanJwt.contains('\n') &&
                !cleanJwt.contains('\r')
            ) {
                ramDmartJwt = cleanJwt
            } else if (cleanJwt.isEmpty()) {
                ramDmartJwt = null
            }
            val cleanWh = warehouseId.trim()
            if (UUID_REGEX.matches(cleanWh)) {
                ramWarehouseId = cleanWh
            }
        }

        @JavascriptInterface
        fun wipeRamToken() {
            if (!isTrustedLocalAssetOrigin()) return
            ramDmartJwt = null
        }

        @JavascriptInterface
        fun hasRamToken(): Boolean {
            if (!isTrustedLocalAssetOrigin()) return false
            return !ramDmartJwt.isNullOrBlank()
        }

        @JavascriptInterface
        fun getWarehouseId(): String {
            if (!isTrustedLocalAssetOrigin()) return ""
            return ramWarehouseId
        }

        @JavascriptInterface
        fun vibrate(patternJson: String) {
            if (!isTrustedLocalAssetOrigin()) return
            try {
                val arr = JSONArray(patternJson)
                val len = arr.length().coerceAtMost(8)
                val timings = LongArray(len) { i -> arr.getLong(i).coerceIn(0L, 500L) }
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
                    } else if (timings.isNotEmpty()) {
                        val padded = LongArray(timings.size + 1)
                        padded[0] = 0L
                        System.arraycopy(timings, 0, padded, 1, timings.size)
                        vibrator.vibrate(VibrationEffect.createWaveform(padded, -1))
                    }
                } else if (timings.isNotEmpty()) {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(timings[0])
                }
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun saveAndShareCsv(fileName: String, csvContent: String): String {
            if (!isTrustedLocalAssetOrigin()) {
                return JSONObject().put("ok", false).put("error", "UNAUTHORIZED_ORIGIN").toString()
            }
            return try {
                val baseOnly = File(fileName).name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    .trimStart('.')
                    .ifBlank { "Smouha-Shift.csv" }
                val safeName = if (baseOnly.endsWith(".csv", ignoreCase = true)) baseOnly else "$baseOnly.csv"
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
                    if (!outFile.canonicalPath.startsWith(dlDir.canonicalPath)) {
                        throw SecurityException("PATH_TRAVERSAL_BLOCKED")
                    }
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
         * RT58-04: Pre-URL character rejection (`@`, `\`, `..`, `\r`, `\n`) before constructing URL(...)
         * to defeat any userInfo or backslash host-confusion attack.
         */
        @JavascriptInterface
        fun dmartRequest(method: String, path: String, bodyJson: String): String {
            if (!isTrustedLocalAssetOrigin()) {
                return JSONObject().put("ok", false).put("status", 403).put("error", "UNAUTHORIZED_ORIGIN").toString()
            }
            val token = ramDmartJwt ?: return JSONObject().put("ok", false).put("status", 401).put("error", "NO_RAM_TOKEN").toString()
            val cleanMethod = method.uppercase().trim()
            if (cleanMethod !in ALLOWED_HTTP_METHODS) {
                return JSONObject().put("ok", false).put("status", 405).put("error", "METHOD_NOT_ALLOWED").toString()
            }

            val rawPath = path.trim()
            if (rawPath.isEmpty() ||
                rawPath.contains("@") ||
                rawPath.contains("\\") ||
                rawPath.contains("..") ||
                rawPath.contains("\r") ||
                rawPath.contains("\n")
            ) {
                return JSONObject().put("ok", false).put("status", 400).put("error", "MALFORMED_OR_HOST_CONFUSION_PATH_BLOCKED").toString()
            }

            return try {
                val fullUrl = when {
                    rawPath.startsWith("https://") -> rawPath
                    rawPath.startsWith("/") && !rawPath.startsWith("//") -> "https://im-bff-live-me.deliveryhero.io$rawPath"
                    else -> return JSONObject().put("ok", false).put("status", 400).put("error", "INVALID_PATH").toString()
                }

                val parsedUrl = URL(fullUrl)
                if (parsedUrl.protocol != "https" || parsedUrl.host !in ALLOWED_DMART_HOSTS || parsedUrl.userInfo != null) {
                    return JSONObject().put("ok", false).put("status", 403).put("error", "UNTRUSTED_DMART_HOST_BLOCKED").toString()
                }

                val conn = (parsedUrl.openConnection() as HttpsURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = cleanMethod
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Accept", "application/json, text/plain, */*")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Origin", "https://portal.talabat.com")
                    setRequestProperty("Referer", "https://portal.talabat.com/")
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36")
                }

                if (cleanMethod == "POST" || cleanMethod == "PUT") {
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
