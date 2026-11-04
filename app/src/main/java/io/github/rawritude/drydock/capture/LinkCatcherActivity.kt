package io.github.rawritude.drydock.capture

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.rawritude.drydock.DrydockApp
import io.github.rawritude.drydock.R
import io.github.rawritude.drydock.service.DownloadCoordinator
import io.github.rawritude.drydock.util.LinkPatterns
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Entry point for http(s) links the system offers to Drydock (capture mode).
 *
 * Matching links are queued as downloads without leaving the current app.
 * Everything else is forwarded to a real browser, so Drydock can be enabled
 * as a link handler without hijacking browsing.
 */
class LinkCatcherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent?.dataString
        if (url.isNullOrBlank()) {
            finish()
            return
        }

        val container = (application as DrydockApp).container
        lifecycleScope.launch {
            val captureEnabled = container.settings.captureEnabled.first()
            val patterns = LinkPatterns.parse(container.settings.capturePatterns.first())
            val allowInsecure = container.settings.captureAllowHttp.first()

            val shouldCapture = captureEnabled && LinkPatterns.matches(url, patterns)
            if (!shouldCapture) {
                forwardToBrowser(url)
                return@launch
            }

            try {
                container.coordinator.enqueue(
                    DownloadCoordinator.AddRequest(url = url, allowInsecureHttp = allowInsecure),
                )
                Toast.makeText(
                    this@LinkCatcherActivity,
                    getString(R.string.capture_added),
                    Toast.LENGTH_SHORT,
                ).show()
                finish()
            } catch (e: IllegalArgumentException) {
                forwardToBrowser(url)
            }
        }
    }

    private fun forwardToBrowser(url: String) {
        val uri = Uri.parse(url)
        val view = Intent(Intent.ACTION_VIEW, uri)
        val candidates = packageManager.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.packageName != packageName }

        if (candidates.isNotEmpty()) {
            val target = candidates.first().activityInfo
            val forward = Intent(Intent.ACTION_VIEW, uri)
                .setClassName(target.packageName, target.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(forward)
        } else {
            Toast.makeText(this, getString(R.string.capture_no_browser), Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
