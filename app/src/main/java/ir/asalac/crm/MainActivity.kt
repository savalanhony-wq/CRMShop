package ir.asalac.crm

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Asalac CRM - thin Android shell around the CRM that runs on the shop computer (run.bat, LAN=1).
 * It only shows the CRM web page, so every feature and all data stay on the computer.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var bar: ProgressBar
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private val reqFile = 11

    private val prefs by lazy { getSharedPreferences("asalac", MODE_PRIVATE) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
    private fun baseUrl(): String = prefs.getString("url", "") ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(-1, -1))

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        bar.visibility = View.GONE
        root.addView(bar, FrameLayout.LayoutParams(-1, dp(3)))

        val gear = TextView(this)
        gear.text = "\u2699"
        gear.textSize = 18f
        gear.setTextColor(Color.WHITE)
        gear.gravity = Gravity.CENTER
        gear.alpha = 0.6f
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(0x99000000.toInt())
        gear.background = bg
        val lp = FrameLayout.LayoutParams(dp(36), dp(36), Gravity.BOTTOM or Gravity.START)
        lp.setMargins(dp(10), 0, 0, dp(10))
        root.addView(gear, lp)
        gear.setOnClickListener { showMenu() }

        setContentView(root)

        if (Build.VERSION.SDK_INT in 23..28) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }

        setupWebView()
        val u = baseUrl()
        if (u.isEmpty()) askUrl(true) else web.loadUrl(u)
    }

    private fun setupWebView() {
        val s: WebSettings = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.setSupportZoom(false)
        s.userAgentString = s.userAgentString + " AsalacApp/1.0"
        CookieManager.getInstance().setAcceptCookie(true)
        web.addJavascriptInterface(Bridge(), "Android")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url
                val base = Uri.parse(baseUrl())
                val sch = u.scheme ?: ""
                if (sch == "blob" || sch == "about" || sch == "data") return false
                if ((sch == "http" || sch == "https") && u.host == base.host && u.port == base.port) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                } catch (e: ActivityNotFoundException) {
                    toast("\u0628\u0631\u0646\u0627\u0645\u0647\u0654 \u0645\u0646\u0627\u0633\u0628 \u0628\u0631\u0627\u06cc \u0628\u0627\u0632 \u06a9\u0631\u062f\u0646 \u067e\u06cc\u062f\u0627 \u0646\u0634\u062f")
                }
                return true
            }

            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (r.isForMainFrame) showError()
            }

            override fun onPageFinished(v: WebView, url: String?) {
                // the CRM prints invoices with window.print(); route it to Android's print / save-as-PDF dialog
                v.evaluateJavascript("window.print=function(){Android.print()};", null)
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) {
                bar.progress = p
                bar.visibility = if (p in 1..99) View.VISIBLE else View.GONE
            }

            override fun onShowFileChooser(
                v: WebView,
                cb: ValueCallback<Array<Uri>>,
                p: WebChromeClient.FileChooserParams
            ): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                return try {
                    startActivityForResult(p.createIntent(), reqFile)
                    true
                } catch (e: Exception) {
                    fileCb = null
                    false
                }
            }
        }

        web.setDownloadListener(DownloadListener { url, ua, cd, mime, _ ->
            if (url.startsWith("blob:")) {
                fetchBlob(url, mime)
            } else {
                try {
                    val req = DownloadManager.Request(Uri.parse(url))
                    req.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                    req.addRequestHeader("User-Agent", ua)
                    req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    req.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        URLUtil.guessFileName(url, cd, mime)
                    )
                    (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                    toast("\u062f\u0627\u0646\u0644\u0648\u062f \u0634\u0631\u0648\u0639 \u0634\u062f (\u067e\u0648\u0634\u0647\u0654 Downloads)")
                } catch (e: Exception) {
                    toast("\u062f\u0627\u0646\u0644\u0648\u062f \u0646\u0627\u0645\u0648\u0641\u0642 \u0628\u0648\u062f")
                }
            }
        })
    }

    /** Blob downloads (Excel / PDF exports made in the page) cannot go through DownloadManager: read them in JS and hand them to Android. */
    private fun fetchBlob(url: String, mime: String?) {
        val m = if (mime.isNullOrEmpty()) "application/octet-stream" else mime
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(m) ?: "bin"
        val name = "asalac-" + System.currentTimeMillis() + "." + ext
        val js = "(function(){var x=new XMLHttpRequest();x.open('GET','" + url + "',true);" +
            "x.responseType='blob';x.onload=function(){var r=new FileReader();" +
            "r.onloadend=function(){Android.saveBase64('" + name + "','" + m + "',r.result.split(',')[1])};" +
            "r.readAsDataURL(x.response)};x.send()})()"
        web.evaluateJavascript(js, null)
    }

    private fun showError() {
        val html = "<html dir='rtl'><body style='font-family:sans-serif;background:#1a1033;color:#fff;padding:24px;text-align:center'>" +
            "<h2>\u0627\u062a\u0635\u0627\u0644 \u0628\u0647 \u06a9\u0627\u0645\u067e\u06cc\u0648\u062a\u0631 \u0628\u0631\u0642\u0631\u0627\u0631 \u0646\u0634\u062f</h2>" +
            "<p>\u06a9\u0627\u0645\u067e\u06cc\u0648\u062a\u0631 \u0631\u0648\u0634\u0646 \u0648 <b>run.bat</b> \u0627\u062c\u0631\u0627 \u0634\u062f\u0647 \u0628\u0627\u0634\u062f\u060c \u06af\u0648\u0634\u06cc \u0647\u0645\u0627\u0646 \u0648\u0627\u06cc\u200c\u0641\u0627\u06cc \u0628\u0627\u0634\u062f \u0648 \u0622\u062f\u0631\u0633 \u062f\u0631\u0633\u062a \u0628\u0627\u0634\u062f.</p>" +
            "<p><button style='font-size:18px;padding:10px 20px' onclick='Android.retry()'>\u062a\u0644\u0627\u0634 \u062f\u0648\u0628\u0627\u0631\u0647</button></p>" +
            "<p><button style='font-size:18px;padding:10px 20px' onclick='Android.settings()'>\u062a\u063a\u06cc\u06cc\u0631 \u0622\u062f\u0631\u0633 \u0633\u0631\u0648\u0631</button></p></body></html>"
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private fun askUrl(first: Boolean) {
        val et = EditText(this)
        et.hint = "http://192.168.1.10:3000"
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        et.textDirection = View.TEXT_DIRECTION_LTR
        et.setText(baseUrl())
        val b = AlertDialog.Builder(this)
            .setTitle("\u0622\u062f\u0631\u0633 \u0633\u0631\u0648\u0631 \u0639\u0633\u0644\u06a9")
            .setMessage("\u0622\u062f\u0631\u0633 \u06a9\u0627\u0645\u067e\u06cc\u0648\u062a\u0631\u06cc \u06a9\u0647 run.bat \u0631\u0648\u06cc \u0622\u0646 \u0627\u062c\u0631\u0627 \u0634\u062f\u0647 \u0631\u0627 \u0648\u0627\u0631\u062f \u06a9\u0646\u06cc\u062f\u060c \u0645\u062b\u0644\u0627\u064b http://192.168.1.10:3000")
            .setView(et)
            .setCancelable(!first)
            .setPositiveButton("\u0627\u062a\u0635\u0627\u0644") { _, _ ->
                var u = et.text.toString().trim()
                if (u.isNotEmpty()) {
                    if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
                    prefs.edit().putString("url", u).apply()
                    web.loadUrl(u)
                } else if (first) {
                    askUrl(true)
                }
            }
        if (!first) b.setNegativeButton("\u0627\u0646\u0635\u0631\u0627\u0641", null)
        b.show()
    }

    private fun showMenu() {
        val items = arrayOf(
            "\u062a\u063a\u06cc\u06cc\u0631 \u0622\u062f\u0631\u0633 \u0633\u0631\u0648\u0631",
            "\u0628\u0627\u0631\u06af\u0630\u0627\u0631\u06cc \u0645\u062c\u062f\u062f \u0635\u0641\u062d\u0647",
            "\u067e\u0627\u06a9\u200c\u06a9\u0631\u062f\u0646 \u062d\u0627\u0641\u0638\u0647\u0654 \u0645\u0648\u0642\u062a"
        )
        AlertDialog.Builder(this).setItems(items) { _, i ->
            when (i) {
                0 -> askUrl(false)
                1 -> { val u = baseUrl(); if (u.isEmpty()) askUrl(true) else web.loadUrl(u) }
                2 -> { web.clearCache(true); toast("\u067e\u0627\u06a9 \u0634\u062f") }
                else -> {}
            }
        }.show()
    }

    inner class Bridge {
        @JavascriptInterface
        fun saveBase64(name: String, mime: String, b64: String) {
            try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                var uri: Uri? = null
                if (Build.VERSION.SDK_INT >= 29) {
                    val cv = ContentValues()
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, name)
                    cv.put(MediaStore.Downloads.MIME_TYPE, mime)
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    uri?.let { u -> contentResolver.openOutputStream(u)?.use { o -> o.write(bytes) } }
                } else {
                    val f = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name)
                    f.parentFile?.mkdirs()
                    f.writeBytes(bytes)
                }
                runOnUiThread {
                    toast("\u0641\u0627\u06cc\u0644 \u062f\u0631 \u067e\u0648\u0634\u0647\u0654 Downloads \u0630\u062e\u06cc\u0631\u0647 \u0634\u062f: $name")
                    val u = uri
                    if (u != null) {
                        try {
                            val i = Intent(Intent.ACTION_VIEW)
                            i.setDataAndType(u, mime)
                            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(i)
                        } catch (e: Exception) { /* no viewer installed: file is still in Downloads */ }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("\u0630\u062e\u06cc\u0631\u0647\u0654 \u0641\u0627\u06cc\u0644 \u0646\u0627\u0645\u0648\u0641\u0642 \u0628\u0648\u062f") }
            }
        }

        @JavascriptInterface
        fun print() {
            runOnUiThread {
                val pm = getSystemService(PRINT_SERVICE) as PrintManager
                pm.print("Asalac", web.createPrintDocumentAdapter("Asalac"), PrintAttributes.Builder().build())
            }
        }

        @JavascriptInterface
        fun retry() {
            runOnUiThread { val u = baseUrl(); if (u.isEmpty()) askUrl(true) else web.loadUrl(u) }
        }

        @JavascriptInterface
        fun settings() {
            runOnUiThread { askUrl(false) }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == reqFile) {
            fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCb = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
