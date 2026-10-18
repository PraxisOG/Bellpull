package org.praxisog.hermes

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/** App visibility, including transitions to model setup and configuration recreation. */
internal class LocalFallbackLifecycle(application: Application, private val onBackground: () -> Unit) : Application.ActivityLifecycleCallbacks {
    private var started = 0
    private val handler = Handler(Looper.getMainLooper())
    private val release = Runnable { if (started == 0) onBackground() }
    init { application.registerActivityLifecycleCallbacks(this) }
    override fun onActivityStarted(activity: Activity) { started++; handler.removeCallbacks(release) }
    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0 && !activity.isChangingConfigurations) handler.postDelayed(release, 750)
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
