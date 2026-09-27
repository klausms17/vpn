package androidx.annotation
annotation class LayoutRes
annotation class RequiresApi(val value: Int = 0, val api: Int = 0)
annotation class VisibleForTesting(val otherwise: Int = 2)
