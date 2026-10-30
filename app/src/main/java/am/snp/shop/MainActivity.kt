package am.snp.shop

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File

/**
 * BYME.AM — приложение-оболочка сайта (WebView).
 * Всё, что умеет сайт (каталог, поиск по фото и голосу, корзина, заказы, оплата, чат, кабинеты), работает здесь же;
 * приложение добавляет то, чего WebView не умеет сам: камеру и выбор файлов, микрофон (распознавание речи Android),
 * геолокацию, скачивание счетов/PDF (в т.ч. blob:), внешние ссылки (телефон, WhatsApp, Telegram, карты), экран «нет сети».
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var offline: View
    private lateinit var voice: VoiceBridge

    private val baseUrl: String = BuildConfig.BASE_URL.trimEnd('/')
    private val baseHost: String by lazy { Uri.parse(baseUrl).host ?: "" }

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null
    private var mainFrameFailed = false

    // ---------------------------------------------------------------- разрешения
    private var pendingPerms: Array<String> = emptyArray()
    private var afterPerms: ((Map<String, Boolean>) -> Unit)? = null
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val cb = afterPerms
        val asked = pendingPerms
        afterPerms = null
        pendingPerms = emptyArray()
        cb?.invoke(asked.associateWith { p -> result[p] == true || granted(p) })
    }

    private fun granted(p: String): Boolean =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    fun ensurePerms(perms: Array<String>, cb: (Map<String, Boolean>) -> Unit) {
        val missing = perms.filter { !granted(it) }
        if (missing.isEmpty()) {
            cb(perms.associateWith { true })
            return
        }
        if (afterPerms != null) { // уже идёт запрос — отказываем, чтобы не потерять предыдущий колбэк
            cb(perms.associateWith { granted(it) })
            return
        }
        pendingPerms = perms
        afterPerms = cb
        permLauncher.launch(missing.toTypedArray())
    }

    // ---------------------------------------------------------------- выбор файла / камера
    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val cb = fileCallback
        fileCallback = null
        if (cb == null) return@registerForActivityResult
        var uris: Array<Uri>? = null
        if (res.resultCode == RESULT_OK) {
            val data = res.data
            val clip = data?.clipData
            val single = data?.data
            val cam = cameraUri
            uris = when {
                clip != null && clip.itemCount > 0 -> Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                single != null -> arrayOf(single)
                cam != null && hasContent(cam) -> arrayOf(cam)
                else -> null
            }
        }
        cameraUri = null
        cb.onReceiveValue(uris)
    }

    private fun hasContent(uri: Uri): Boolean = try {
        contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize > 0 } ?: false
    } catch (e: Exception) {
        false
    }

    private fun mimesOf(accept: Array<String>): Array<String> {
        val out = LinkedHashSet<String>()
        for (raw in accept) {
            for (part in raw.split(',')) {
                val a = part.trim().lowercase()
                if (a.isEmpty()) continue
                if (a.startsWith(".")) {
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(a.substring(1))?.let { out.add(it) }
                } else if (a.contains('/')) {
                    out.add(a)
                }
            }
        }
        return out.toTypedArray()
    }

    private fun openChooser(params: WebChromeClient.FileChooserParams) {
        val mimes = mimesOf(params.acceptTypes ?: emptyArray())
        val wantsImage = mimes.isEmpty() || mimes.any { it.startsWith("image/") || it == "*/*" }
        val multiple = params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE

        val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (mimes.size == 1) mimes[0] else "*/*"
            if (mimes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimes)
            if (multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }

        val launch = { withCamera: Boolean ->
            var camIntent: Intent? = null
            if (withCamera) {
                try {
                    val dir = File(cacheDir, "images").apply { mkdirs() }
                    val f = File(dir, "photo_" + System.currentTimeMillis() + ".jpg")
                    val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
                    cameraUri = uri
                    camIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(MediaStore.EXTRA_OUTPUT, uri)
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                } catch (e: Exception) {
                    camIntent = null
                    cameraUri = null
                }
            }
            val cam = camIntent
            val intent = if (cam != null && params.isCaptureEnabled) {
                cam
            } else {
                Intent.createChooser(pick, getString(R.string.choose_file)).apply {
                    if (cam != null) putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cam))
                }
            }
            try {
                fileLauncher.launch(intent)
            } catch (e: ActivityNotFoundException) {
                fileCallback?.onReceiveValue(null)
                fileCallback = null
                toast(getString(R.string.no_app))
            }
        }

        if (wantsImage && packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            ensurePerms(arrayOf(Manifest.permission.CAMERA)) { r -> launch(r.values.all { it }) }
        } else {
            launch(false)
        }
    }

    // ---------------------------------------------------------------- запуск
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_BYME)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        web = findViewById(R.id.web)
        swipe = findViewById(R.id.swipe)
        progress = findViewById(R.id.progress)
        offline = findViewById(R.id.offline)
        findViewById<Button>(R.id.retry).setOnClickListener { retry() }

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            loadWithOverviewMode = true
            useWideViewPort = true
            textZoom = 100
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setGeolocationEnabled(true)
            userAgentString = userAgentString + " BYMEApp/" + BuildConfig.VERSION_NAME
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        voice = VoiceBridge(this, web)
        web.addJavascriptInterface(voice, "BYMEVoice")
        web.addJavascriptInterface(FileBridge(), "BYMEFiles")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                if (request.isForMainFrame) handleExternal(request.url) else false

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                mainFrameFailed = false
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.GONE
                swipe.isRefreshing = false
                if (!mainFrameFailed) offline.visibility = View.GONE
                view.evaluateJavascript(injectJs, null)
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showOffline()
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame && errorResponse.statusCode >= 500) showOffline()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
            }

            override fun onShowFileChooser(view: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: WebChromeClient.FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                openChooser(fileChooserParams)
                return true
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                ensurePerms(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) { r ->
                    callback.invoke(origin, r.values.any { it }, false)
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                val need = ArrayList<String>()
                if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) need.add(Manifest.permission.CAMERA)
                if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) need.add(Manifest.permission.RECORD_AUDIO)
                if (need.isEmpty()) {
                    request.deny()
                    return
                }
                runOnUiThread {
                    ensurePerms(need.toTypedArray()) { r ->
                        val ok = ArrayList<String>()
                        if (r[Manifest.permission.CAMERA] == true) ok.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                        if (r[Manifest.permission.RECORD_AUDIO] == true) ok.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                        if (ok.isEmpty()) request.deny() else request.grant(ok.toTypedArray())
                    }
                }
            }
        }

        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            download(url, userAgent, contentDisposition, mimeType)
        }

        swipe.setColorSchemeResources(R.color.brand)
        swipe.setOnRefreshListener { web.reload() }
        swipe.setOnChildScrollUpCallback { _, _ -> web.scrollY > 0 }

        onBackPressedDispatcher.addCallback(this) {
            if (offline.visibility == View.VISIBLE) {
                offline.visibility = View.GONE
            }
            if (web.canGoBack()) {
                web.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            web.loadUrl(startUrl(intent))
        }
    }

    private fun startUrl(i: Intent?): String {
        val u = i?.data
        return if (u != null && isOwn(u)) u.toString() else "$baseUrl/"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val u = intent.data
        if (u != null && isOwn(u)) web.loadUrl(u.toString())
    }

    // ---------------------------------------------------------------- ссылки
    private fun isOwn(uri: Uri): Boolean {
        val h = (uri.host ?: "").lowercase()
        val b = baseHost.lowercase()
        return h == b || h.endsWith(".$b") || (b.startsWith("www.") && h == b.removePrefix("www."))
    }

    // http(s) открываем внутри (оплата банком возвращается на сайт); мессенджеры, звонки, карты, магазины приложений — во внешних приложениях
    private val externalHosts = Regex("(^|\\.)(wa\\.me|whatsapp\\.com|t\\.me|telegram\\.me|viber\\.com|maps\\.google\\.[a-z.]+|maps\\.app\\.goo\\.gl|goo\\.gl|yandex\\.[a-z]+|play\\.google\\.com|apps\\.apple\\.com|youtube\\.com|youtu\\.be|instagram\\.com|facebook\\.com|tiktok\\.com|vk\\.com)$")

    private fun handleExternal(uri: Uri): Boolean {
        val scheme = (uri.scheme ?: "").lowercase()
        if (scheme == "http" || scheme == "https") {
            if (isOwn(uri)) return false
            val host = (uri.host ?: "").lowercase()
            val path = uri.path ?: ""
            val isMaps = host.startsWith("www.google.") && path.startsWith("/maps")
            if (!externalHosts.containsMatchIn(host) && !isMaps) return false
            openOutside(uri)
            return true
        }
        if (scheme == "blob" || scheme == "data" || scheme == "about" || scheme == "javascript" || scheme == "file") return false
        if (scheme == "intent") {
            try {
                val i = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                i.addCategory(Intent.CATEGORY_BROWSABLE)
                i.component = null
                i.selector = null
                startActivity(i)
            } catch (e: Exception) {
                try {
                    val fb = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
                    if (!fb.isNullOrEmpty()) web.loadUrl(fb)
                } catch (_: Exception) {
                }
            }
            return true
        }
        openOutside(uri) // tel:, mailto:, sms:, whatsapp:, viber:, tg:, geo: …
        return true
    }

    private fun openOutside(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_app))
        }
    }

    // ---------------------------------------------------------------- нет сети
    private fun showOffline() {
        mainFrameFailed = true
        progress.visibility = View.GONE
        swipe.isRefreshing = false
        offline.visibility = View.VISIBLE
    }

    private fun retry() {
        offline.visibility = View.GONE
        val u = web.url
        if (u.isNullOrEmpty() || u == "about:blank") web.loadUrl("$baseUrl/") else web.reload()
    }

    // ---------------------------------------------------------------- скачивание
    private fun download(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (url.startsWith("blob:")) {
            web.evaluateJavascript("window.__bymeSaveBlob && window.__bymeSaveBlob(" + org.json.JSONObject.quote(url) + ", '');", null)
            return
        }
        if (url.startsWith("data:")) {
            val comma = url.indexOf(',')
            if (comma > 0) {
                val mime = url.substring(5, url.indexOf(';').let { if (it in 1 until comma) it else comma })
                saveBytes("file", mime, Base64.decode(url.substring(comma + 1), Base64.DEFAULT))
            }
            return
        }
        val go = {
            try {
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val req = DownloadManager.Request(Uri.parse(url)).apply {
                    CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                    if (!userAgent.isNullOrEmpty()) addRequestHeader("User-Agent", userAgent)
                    setTitle(name)
                    if (!mimeType.isNullOrEmpty()) setMimeType(mimeType)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                }
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                toast(getString(R.string.downloading, name))
            } catch (e: Exception) {
                openOutside(Uri.parse(url))
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            ensurePerms(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) { r -> if (r.values.all { it }) go() else toast(getString(R.string.no_storage)) }
        } else {
            go()
        }
    }

    /** Сохраняет файл в «Загрузки» и открывает его подходящим приложением. */
    fun saveBytes(name0: String, mime0: String, bytes: ByteArray) {
        val mime = mime0.ifEmpty { "application/octet-stream" }
        var name = name0.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "file" }
        if (!name.contains('.')) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { name = "$name.$it" }
        }
        try {
            val uri: Uri
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("insert")
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(filesDir, "Download")
                dir.mkdirs()
                val f = File(dir, name)
                f.writeBytes(bytes)
                uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            }
            toast(getString(R.string.saved, name))
            try {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
            }
        } catch (e: Exception) {
            toast(getString(R.string.save_failed))
        }
    }

    /** Мост для blob:-файлов (PDF счета, выгрузки), которые сайт создаёт в браузере. */
    inner class FileBridge {
        @JavascriptInterface
        fun save(name: String?, mime: String?, base64: String?) {
            if (base64.isNullOrEmpty()) return
            val bytes = try { Base64.decode(base64, Base64.DEFAULT) } catch (e: Exception) { return }
            runOnUiThread { saveBytes(name ?: "file", mime ?: "", bytes) }
        }
    }

    // ---------------------------------------------------------------- вспомогательное
    private val injectJs: String by lazy {
        try { assets.open("inject.js").bufferedReader().use { it.readText() } } catch (e: Exception) { "" }
    }

    fun toast(s: String) {
        runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        web.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        voice.destroy()
        web.destroy()
        super.onDestroy()
    }
}
