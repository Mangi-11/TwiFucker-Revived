package twifucker.revived.core

import android.util.Log
import io.github.libxposed.api.XposedInterface

fun XposedInterface.logV(tag: String, message: String) {
    log(Log.VERBOSE, tag, message)
}

fun XposedInterface.logV(tag: String, message: String, throwable: Throwable?) {
    log(Log.VERBOSE, tag, message, throwable)
}

fun XposedInterface.logD(tag: String, message: String) {
    log(Log.DEBUG, tag, message)
}

fun XposedInterface.logD(tag: String, message: String, throwable: Throwable?) {
    log(Log.DEBUG, tag, message, throwable)
}

fun XposedInterface.logI(tag: String, message: String) {
    log(Log.INFO, tag, message)
}

fun XposedInterface.logI(tag: String, message: String, throwable: Throwable?) {
    log(Log.INFO, tag, message, throwable)
}

fun XposedInterface.logW(tag: String, message: String) {
    log(Log.WARN, tag, message)
}

fun XposedInterface.logW(tag: String, message: String, throwable: Throwable?) {
    log(Log.WARN, tag, message, throwable)
}

fun XposedInterface.logE(tag: String, message: String) {
    log(Log.ERROR, tag, message)
}

fun XposedInterface.logE(tag: String, message: String, throwable: Throwable?) {
    log(Log.ERROR, tag, message, throwable)
}
