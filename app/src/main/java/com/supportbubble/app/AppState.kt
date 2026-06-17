package com.supportbubble.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide observable state shared between background services.
 *
 * [currentApp] holds the package name of the most recently detected
 * foreground application, updated by [AppMonitorService] and observed
 * by [OverlayService] to keep the floating bubble icon current.
 */
object AppState {

    private val _currentApp = MutableStateFlow("")

    /** The package name of the currently active foreground app. */
    val currentApp: StateFlow<String> = _currentApp.asStateFlow()

    fun updateCurrentApp(packageName: String) {
        _currentApp.value = packageName
    }
}
