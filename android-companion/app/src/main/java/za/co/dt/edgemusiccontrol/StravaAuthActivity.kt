package za.co.dt.edgemusiccontrol

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Hosts Strava's authorisation page long enough to get one authorisation code.
 *
 * Strava redirects to the callback registered on the user's API application, http://localhost/…,
 * which nothing is listening on. The redirect is caught before the WebView can try to load it and
 * the code is read out of the URL; the exchange then happens off the main thread and the activity
 * finishes with the tokens already stored.
 */
class StravaAuthActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()

    private val handler = Handler(Looper.getMainLooper())

    private var web: WebView? = null

    // Strava can announce the redirect twice (shouldOverrideUrlLoading, then onPageStarted); the
    // code is single-use, so only the first one may be acted on.
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = Prefs.stravaApp(this)

        if (app == null) {
            Toast.makeText(this, R.string.strava_app_missing, Toast.LENGTH_LONG).show()

            finish()

            return
        }

        val view = WebView(this)

        view.settings.javaScriptEnabled = true

        view.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                return intercept(app, request.url.toString())
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (intercept(app, url)) view.stopLoading()
            }
        }

        web = view

        setContentView(view)

        view.loadUrl(StravaAuth.authorizeUrl(app.clientId))
    }

    override fun onDestroy() {
        worker.shutdownNow()

        web?.destroy()

        web = null

        super.onDestroy()
    }

    /** True when the URL is the callback, which is dealt with here rather than loaded. */
    private fun intercept(app: Prefs.StravaApp, url: String): Boolean {
        if (!url.startsWith(StravaAuth.REDIRECT_URI)) return false

        if (handled) return true

        handled = true

        val uri = Uri.parse(url)
        val error = uri.getQueryParameter("error")
        val code = uri.getQueryParameter("code")

        if (error != null) {
            fail(error)

            return true
        }

        if (code == null) {
            fail(getString(R.string.strava_no_code))

            return true
        }

        // The code, the secret and the tokens all stay off the main thread and out of the log.
        worker.execute {
            val failure = StravaAuth.exchange(this, app, code)

            handler.post {
                if (failure == null) {
                    succeed()
                } else {
                    fail(failure)
                }
            }
        }

        return true
    }

    private fun succeed() {
        if (isFinishing) return

        setResult(RESULT_OK)

        finish()
    }

    private fun fail(reason: String) {
        if (isFinishing) return

        Toast.makeText(
            this,
            getString(R.string.strava_connect_failed, reason),
            Toast.LENGTH_LONG
        ).show()

        setResult(RESULT_CANCELED)

        finish()
    }
}
