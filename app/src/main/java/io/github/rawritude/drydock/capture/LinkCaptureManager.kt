package io.github.rawritude.drydock.capture

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Enables or disables the link-capture activity. The activity ships disabled,
 * so while capture is off the app costs nothing and never appears as a link
 * handler. Turning the toggle on enables the component; the system then starts
 * the app only when a matching http(s) VIEW intent arrives.
 */
class LinkCaptureManager(private val context: Context) {

    private val component = ComponentName(context, LinkCatcherActivity::class.java)

    fun setEnabled(enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
    }
}
