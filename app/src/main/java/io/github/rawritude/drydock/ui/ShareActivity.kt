package io.github.rawritude.drydock.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import io.github.rawritude.drydock.R
import io.github.rawritude.drydock.util.UrlSafety

/**
 * Tiny trampoline for ACTION_SEND intents: extracts the first http(s) link and
 * opens [MainActivity] with the add-download sheet prefilled.
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val url = UrlSafety.firstUrlIn(sharedText)
        if (url != null) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_SHARED_URL, url)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } else {
            Toast.makeText(this, getString(R.string.share_no_url), Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
