/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : app
 * File       : HiltTestRunner.kt
 * Purpose    : Custom AndroidJUnitRunner that replaces the Application with a Hilt-
 *              compatible test application so @HiltAndroidTest works in instrumented tests.
 * Architecture Layer : androidTest (instrumentation)
 *
 * Requirements: 21.3
 * ============================================================
 */
package com.aiassistant

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Custom test runner required for @HiltAndroidTest. Registered in app/build.gradle.kts
 * as the testInstrumentationRunner.
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
