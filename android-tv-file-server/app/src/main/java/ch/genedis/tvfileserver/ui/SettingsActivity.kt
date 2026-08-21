package ch.genedis.tvfileserver.ui

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.GuidedStepSupportFragment

/**
 * Hosts the Leanback settings flow.
 *
 * Declared in the manifest as:
 * ```
 * <activity android:name=".ui.SettingsActivity" android:exported="false"
 *     android:theme="@style/Theme.TvFileServer.GuidedStep" />
 * ```
 */
class SettingsActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            GuidedStepSupportFragment.addAsRoot(this, SettingsFragment(), android.R.id.content)
        }
    }
}
