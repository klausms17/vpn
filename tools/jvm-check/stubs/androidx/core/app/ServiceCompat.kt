package androidx.core.app
import android.app.Notification
import android.app.Service
object ServiceCompat {
    const val STOP_FOREGROUND_REMOVE = 1
    @JvmStatic fun startForeground(service: Service, id: Int, notification: Notification, foregroundServiceType: Int) {}
    @JvmStatic fun stopForeground(service: Service, flags: Int) {}
}
