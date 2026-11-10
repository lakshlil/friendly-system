package com.lakshlabs.medreminder

import android.app.Application

/**
 * Records any crash (exception and the first lines of its stack) so the next launch can show it.
 * Debugging without access to the phone depends on this.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val where = e.stackTrace.take(6).joinToString("\n") { "  at ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                val cause = e.cause?.let { "\nCaused by ${it.javaClass.simpleName}: ${it.message}" } ?: ""
                val report = "${e.javaClass.simpleName}: ${e.message}\n$where$cause\n(version ${versionLabel()}, Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL})"
                Store.setLastCrash(this, report)
                Store.log(this, "CRASH ${e.javaClass.simpleName}: ${e.message}")
            } catch (_: Throwable) { }
            previous?.uncaughtException(t, e)
        }
    }
}
