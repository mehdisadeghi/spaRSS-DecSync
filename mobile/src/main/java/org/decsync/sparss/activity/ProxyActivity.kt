package org.decsync.sparss.activity

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.widget.Toolbar
import org.decsync.sparss.R
import org.decsync.sparss.utils.UiUtils

class ProxyActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        UiUtils.setPreferenceTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_proxy_prefs)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onOptionsItemSelected(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
        }
        return super.onOptionsItemSelected(menuItem)
    }
}