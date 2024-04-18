package com.spotify.confidence

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ParseException
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class AndroidLifecycleEventProducer(
    private val application: Application
) : Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver, EventProducer {
    private val eventsFlow = MutableSharedFlow<Event>()
    private val contextFlow = MutableStateFlow<Map<String, ConfidenceValue>>(mapOf())
    private val sharedPreferences by lazy {
        application.getSharedPreferences("CONFIDENCE_EVENTS", Context.MODE_PRIVATE)
    }
    private val packageInfo: PackageInfo
    private val lifecycle: Lifecycle

    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    init {
        val packageManager: PackageManager = application.packageManager
        packageInfo = try {
            packageManager.getPackageInfo(application.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            throw Error("can't read package")
        }

        // setup lifecycle listeners
        application.registerActivityLifecycleCallbacks(this)
        lifecycle = ProcessLifecycleOwner.get().lifecycle
        coroutineScope.launch(Dispatchers.Main) {
            lifecycle.addObserver(this@AndroidLifecycleEventProducer)
        }
    }
    override fun onActivityCreated(p0: Activity, p1: Bundle?) {
        trackDeepLink(p0)
        p0.trackActivity("activity-created")
    }

    override fun onCreate(owner: LifecycleOwner) {
        trackApplicationLifecycleEvents()
    }

    override fun onStart(owner: LifecycleOwner) {
        contextFlow.value =
            mapOf(IS_FOREGROUND_KEY to ConfidenceValue.Boolean(true))
    }

    override fun onStop(owner: LifecycleOwner) {
        contextFlow.value =
            mapOf(IS_FOREGROUND_KEY to ConfidenceValue.Boolean(false))
    }

    override fun onActivityStarted(p0: Activity) {
        p0.trackActivity("activity-started")
    }

    override fun onActivityResumed(p0: Activity) {
        p0.trackActivity("activity-resumed")
    }

    override fun onActivityPaused(p0: Activity) {
        p0.trackActivity("activity-paused")
    }

    private fun Activity.trackActivity(state: String) {
        getLabel()?.let { label ->
            val message = mapOf("label" to label)
            coroutineScope.launch { eventsFlow.emit(Event(state, message)) }
        }
    }

    private fun trackDeepLink(activity: Activity?) {
        val intent = activity?.intent
        if (intent == null || intent.data == null) {
            return
        }

        val map = mutableMapOf<String, ConfidenceValue.String>()

        with(map) {
            intent.data?.let { uri ->
                if (uri.isHierarchical) {
                    for (parameter in uri.queryParameterNames) {
                        val value = uri.getQueryParameter(parameter)
                        if (value != null && value.trim().isNotEmpty()) {
                            put(parameter, ConfidenceValue.String(value))
                        }
                    }
                }
                put("url", ConfidenceValue.String(uri.toString()))
            }
            put("referrer", ConfidenceValue.String(getReferrer(activity).toString()))
        }
        coroutineScope.launch { eventsFlow.emit(Event("Deeplink", map)) }
    }

    override fun onActivityStopped(p0: Activity) {
        p0.trackActivity("activity-stopped")
    }

    override fun onActivitySaveInstanceState(p0: Activity, p1: Bundle) {}

    override fun onActivityDestroyed(p0: Activity) {
        p0.trackActivity("activity-destroyed")
    }

    private fun trackApplicationLifecycleEvents() {
        // Get the current version.
        val packageInfo = packageInfo
        val currentVersion = ConfidenceValue.String(packageInfo.versionName)
        val currentBuild = ConfidenceValue.String(packageInfo.getVersionCode().toString())

        // Get the previous recorded version.
        val previousVersion = sharedPreferences
            .getString(APP_VERSION, null)
            ?.let(ConfidenceValue::String)

        val previousBuild: ConfidenceValue.String? = sharedPreferences
            .getString(APP_BUILD, null)
            ?.let(ConfidenceValue::String)

        val legacyPreviousBuild = sharedPreferences.getString(LEGACY_APP_BUILD, null)

        // Check and track Application Installed or Application Updated.
        if (previousBuild == null && legacyPreviousBuild == null) {
            val message = mapOf("version" to currentVersion, "build" to currentBuild)
            coroutineScope.launch { eventsFlow.emit(Event(APP_INSTALLED_EVENT, message)) }
        } else if (currentBuild != previousBuild) {
            val message = mapOf(
                "version" to currentVersion,
                "build" to currentBuild,
                "previous_version" to (previousVersion ?: ConfidenceValue.String("")),
                "previous_build" to (previousBuild ?: ConfidenceValue.String(""))
            )
            coroutineScope.launch { eventsFlow.emit(Event(APP_UPDATED_EVENT, message)) }
        }

        coroutineScope.launch {
            sharedPreferences.edit().putString(APP_VERSION, currentVersion.string).apply()
            sharedPreferences.edit().putString(APP_BUILD, currentBuild.string).apply()
        }

        coroutineScope.launch {
            val message = mapOf("version" to currentVersion, "build" to currentBuild)
            eventsFlow.emit(Event(APP_CREATED, message))
        }
    }

    override fun events(): Flow<Event> = eventsFlow

    override fun contextChanges(): Flow<Map<String, ConfidenceValue>> = contextFlow
    override fun stop() {
        application.unregisterActivityLifecycleCallbacks(this)
        coroutineScope.launch(Dispatchers.Main) {
            lifecycle.removeObserver(this@AndroidLifecycleEventProducer)
        }
    }

    companion object {
        private const val IS_FOREGROUND_KEY = "is_foreground"
        private const val APP_VERSION = "AppVersion"
        private const val APP_BUILD = "APP_BUILD"
        private const val LEGACY_APP_BUILD = "LEGACY_APP_BUILD"
        private const val APP_INSTALLED_EVENT = "app-installed"
        private const val APP_UPDATED_EVENT = "app-updated"
        private const val APP_CREATED = "app-on-created"
    }
}

private fun PackageInfo.getVersionCode(): Number =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        this.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        this.versionCode
    }

// Returns the referrer who started the Activity.
fun getReferrer(activity: Activity): Uri? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
        activity.referrer
    } else {
        getReferrerCompatible(activity)
    }
}

// Returns the referrer on devices running SDK versions lower than 22.
private fun getReferrerCompatible(activity: Activity): Uri? {
    var referrerUri: Uri? = null
    val intent = activity.intent
    referrerUri = intent.getParcelableExtra(Intent.EXTRA_REFERRER)

    if (referrerUri == null) {
        // Intent.EXTRA_REFERRER_NAME
        referrerUri = intent.getStringExtra("android.intent.extra.REFERRER_NAME")?.let {
            // Try parsing the referrer URL; if it's invalid, return null
            try {
                Uri.parse(it)
            } catch (ignored: ParseException) {
                null
            }
        }
    }
    return referrerUri
}

private fun Activity.getLabel(): ConfidenceValue.String? {
    val packageManager = packageManager
    try {
        return packageManager?.getActivityInfo(
            componentName,
            PackageManager.GET_META_DATA
        )?.loadLabel(packageManager).toString()
            .let(ConfidenceValue::String)
    } catch (_: Exception) { }
    return null
}