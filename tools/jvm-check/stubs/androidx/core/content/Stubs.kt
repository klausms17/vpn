package androidx.core.content
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
object ContextCompat {
    const val RECEIVER_VISIBLE_TO_INSTANT_APPS = 0x1
    const val RECEIVER_EXPORTED = 0x2
    const val RECEIVER_NOT_EXPORTED = 0x4
    @JvmStatic fun registerReceiver(context: Context, receiver: BroadcastReceiver?, filter: IntentFilter, flags: Int): Intent? = null
    @JvmStatic fun startForegroundService(context: Context, intent: Intent) {}
    @JvmStatic fun checkSelfPermission(context: Context, permission: String): Int = 0
}
inline fun SharedPreferences.edit(commit: Boolean = false, action: SharedPreferences.Editor.() -> Unit) {
    val e = edit(); action(e); if (commit) e.commit() else e.apply()
}
