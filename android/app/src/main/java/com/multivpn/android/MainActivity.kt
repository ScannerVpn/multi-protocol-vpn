package com.multivpn.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.multivpn.android.ui.AppRoot
import com.multivpn.android.ui.MultiVPNTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppModel.init(filesDir, this)
        setContent {
            // Read the theme from the same store every other setting comes
            // from, so a switch applies immediately and survives a restart.
            val theme by AppModel.settings.collectAsState()
            MultiVPNTheme(theme = theme.theme) {
                AppRoot()
            }
        }
    }
}
