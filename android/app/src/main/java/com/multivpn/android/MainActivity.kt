package com.multivpn.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.multivpn.android.ui.AppRoot
import com.multivpn.android.ui.MultiVPNTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppModel.init(filesDir)
        setContent {
            MultiVPNTheme {
                AppRoot()
            }
        }
    }
}
