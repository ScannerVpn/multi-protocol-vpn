package com.multivpn.android

import android.app.Application
import com.hiddify.core.libbox.Libbox
import com.hiddify.core.libbox.SetupOptions
import com.multivpn.android.data.AppLog
import java.io.File

/**
 * Process entry point. libbox is a Go library behind gomobile: it MUST be
 * handed its working directories via [Libbox.setup] before ANY other libbox
 * call, otherwise `newCommandServer` / `startOrReloadService` fail inside Go
 * with no Java stack trace to show for it (which is exactly how the tunnel
 * silently refused to start).
 *
 * Go panics also never reach logcat as a Java exception — [Libbox.redirectStderr]
 * parks them in a file we can read after the fact, which is the only way to
 * diagnose a core-side failure honestly.
 */
class MultiVpnApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        val base = filesDir
        val working = File(filesDir, "box").apply { mkdirs() }
        val temp = File(cacheDir, "box").apply { mkdirs() }
        runCatching {
            Libbox.redirectStderr(File(working, "stderr.log").absolutePath)
        }
        runCatching {
            Libbox.setup(
                SetupOptions().apply {
                    basePath = base.absolutePath
                    workingPath = working.absolutePath
                    tempPath = temp.absolutePath
                    // The Android network stack needs Go's larger stack when a
                    // request crosses the JNI boundary from a core goroutine.
                    fixAndroidStack = true
                },
            )
        }.onFailure {
            LibboxSetup.error = it.message ?: it.toString()
            AppLog.e("App", "Libbox.setup failed: ${LibboxSetup.error}")
        }
        LibboxSetup.done = true
    }
}

/** Whether [Libbox.setup] succeeded — the engine reports the failure honestly. */
object LibboxSetup {
    @Volatile var done = false
    @Volatile var error: String? = null
}
