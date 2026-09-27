package androidx.core.net
import android.net.Uri
inline fun String.toUri(): Uri = Uri.parse(this)
