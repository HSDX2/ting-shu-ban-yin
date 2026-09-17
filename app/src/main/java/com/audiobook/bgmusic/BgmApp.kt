package com.audiobook.bgmusic

import android.app.Application
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BgmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        Prefs.init(this)
        ServiceState.volume.value = Prefs.volume
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(thread, throwable)
            } catch (_: Throwable) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrash(thread: Thread, t: Throwable) {
        val dir = File(getExternalFilesDir(null), "crash").apply { mkdirs() }
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
        FileWriter(File(dir, name)).use { fw ->
            PrintWriter(fw).use { pw ->
                pw.println("Time: " + Date())
                pw.println("Thread: " + thread.name)
                t.printStackTrace(pw)
                val all = Thread.getAllStackTraces()
                for ((t2, stack) in all) {
                    if (t2 === thread) continue
                    pw.println()
                    pw.println("Thread: " + t2.name)
                    stack.forEach { pw.println("\t" + it) }
                }
            }
        }
    }
}
