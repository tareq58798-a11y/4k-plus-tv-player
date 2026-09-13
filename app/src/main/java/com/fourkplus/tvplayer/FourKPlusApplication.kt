package com.fourkplus.tvplayer

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Enables a crossfade for every AsyncImage in the app (Coil picks this up automatically as the
 *  default ImageLoader) so a poster that's still loading - slow provider, slow network, a big
 *  grid all loading at once - fades in once it arrives instead of popping in abruptly. Without
 *  this a slow-loading image looks identical to a missing one until the exact frame it finishes. */
class FourKPlusApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
    }

    // There's no way to pull adb logcat off a user's own phone or TV remotely, so a bare "it
    // crashed" report carries no information to act on. This saves the exact stack trace to a
    // file in the instant before the process actually dies (then still hands off to whatever
    // the platform's own crash handler normally does - Play/vendor crash reporting, the "app
    // keeps stopping" dialog, etc.) so the NEXT launch can read it back and show it on-screen
    // via MainActivity's LastCrashScreen, screenshot-able and everything.
    private fun installCrashLogger() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val writer = StringWriter()
                throwable.printStackTrace(PrintWriter(writer))
                File(filesDir, "last_crash.txt").writeText(writer.toString())
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .crossfade(true)
            .build()
}
