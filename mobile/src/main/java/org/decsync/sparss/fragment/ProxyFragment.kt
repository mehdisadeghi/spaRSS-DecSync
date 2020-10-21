package org.decsync.sparss.fragment

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import org.decsync.sparss.R

class ProxyFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.proxy_preferences)
    }
}