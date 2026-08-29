package org.ryubing.android

import android.content.Context
import android.content.Intent
import android.os.Process
import org.ryubing.android.data.AppLifecycleStore

/**
 * Restarts the app in a fresh process.
 *
 * Every game launch runs in its own process: the guest address-space reservation degrades
 * badly when a second emulation session starts in a process that already hosted one.
 *
 * Callers must persist their state with `commit()` (not `apply()`) before calling this —
 * the process is killed here, and an in-flight async preference write would be lost.
 */
object ProcessRestarter {
    fun restart(context: Context, markIntentional: Boolean = true) {
        if (markIntentional) AppLifecycleStore(context).markIntentionalRestart()
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        Process.killProcess(Process.myPid())
    }
}