package com.android5.ai

import android.app.Application
import android.content.Intent
import android.os.Process

class AIApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLogger.start(this)
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLogger.recordCrash(thread, throwable)
            try {
                val stackTrace = CrashActivity.buildStackTrace(throwable)
                val message = throwable.localizedMessage ?: throwable.javaClass.simpleName
                CrashActivity.saveCrash(this, stackTrace, message)

                val intent = Intent(this, CrashActivity::class.java).apply {
                    putExtra("stack_trace", stackTrace)
                    putExtra("message", message)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(intent)
            } catch (_: Throwable) {
                defaultHandler?.uncaughtException(Thread.currentThread(), throwable)
            }
            // CrashActivity relaunches in a fresh process; kill this dying one.
            Process.killProcess(Process.myPid())
        }
    }
}