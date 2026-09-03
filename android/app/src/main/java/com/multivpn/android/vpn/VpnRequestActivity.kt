package com.multivpn.android.vpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import com.multivpn.android.AppModel

/**
 * A transparent trampoline: Android requires the VPN consent dialog to be
 * triggered by an Activity's startActivityForResult. The UI button starts
 * this; on grant it hands off to [AppModel.connectActive], which starts the
 * tunnel service and WAITS for it (a fixed postDelayed was a race — the
 * service's onCreate loads a ~100 MB native core first).
 */
class VpnRequestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val consent = VpnService.prepare(this)
        if (consent == null) {
            onVpnGranted()
        } else {
            startActivityForResult(consent, REQUEST_VPN)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN && resultCode == RESULT_OK) {
            onVpnGranted()
        } else {
            EngineBridge.setFailed("دسترسی VPN داده نشد.")
            finish()
        }
    }

    private fun onVpnGranted() {
        AppModel.connectActive()
        finish()
    }

    companion object {
        private const val REQUEST_VPN = 4201
    }
}
