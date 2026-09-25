package com.odiousapps.mx3buttonmapper

import android.content.Context
import android.util.Log as AndroidLog
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Drop-in replacement for android.util.Log -- every file that logs
 * imports it as `import com.odiousapps.mx3buttonmapper.AppLog as Log`
 * instead of `import android.util.Log`, so every existing Log.i/w/e/d()
 * call site keeps working unchanged, but now also appends the same line
 * to a file. That's the whole point: logcat's buffer is small enough
 * that a TV's own boot chatter can push this app's lines out of it
 * before there's ever a chance to connect over adb and read them live,
 * which makes logcat alone useless for diagnosing a boot-time failure --
 * a file survives that race, since it can be pulled after the fact on
 * the NEXT boot.
 *
 * No init() call is required to get this: FileLogInitProvider (see its
 * own comment) calls init() automatically, before any other component in
 * the app's main process -- including ButtonMapperService, whose
 * AccessibilityService auto-connects on every boot -- gets a chance to
 * run. The separate Shizuku UserService process (KeyInjectorUserService,
 * running under the shell/root UID with no usable app Context of its own
 * -- see that file's header comment for why) never triggers that
 * provider at all, so it just keeps using the DEFAULT_LOG_FILE fallback
 * below, which is a path the shell UID can always write to without
 * needing a Context.
 *
 * Pull the result with:
 *   adb pull /sdcard/Android/data/com.odiousapps.mx3buttonmapper/files/logs/mapper.log
 *   adb pull /data/local/tmp/mx3buttonmapper-service.log
 */
object AppLog {

    // Keeps the file from growing unbounded across many boots; one
    // rotation (mapper.log -> mapper.log.1) is plenty for this app's log
    // volume -- it only logs on button presses and service lifecycle
    // events, never anything high-frequency.
    private const val MAX_BYTES = 512 * 1024L

    private val TIMESTAMP = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private val DEFAULT_LOG_FILE = File("/data/local/tmp/mx3buttonmapper-service.log")

    private val lock = Any()

    @Volatile
    private var logFile: File = DEFAULT_LOG_FILE

    @Volatile
    private var initialized = false

    /** Idempotent -- only the first caller (FileLogInitProvider) has any effect. */
    fun init(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            val dir = context.applicationContext.getExternalFilesDir(null)
                ?: context.applicationContext.filesDir
            logFile = File(dir, "logs/mapper.log")
            initialized = true
        }
    }

    fun d(tag: String, msg: String) {
        AndroidLog.d(tag, msg)
        write('D', tag, msg, null)
    }

    fun i(tag: String, msg: String) {
        AndroidLog.i(tag, msg)
        write('I', tag, msg, null)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) AndroidLog.w(tag, msg, tr) else AndroidLog.w(tag, msg)
        write('W', tag, msg, tr)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) AndroidLog.e(tag, msg, tr) else AndroidLog.e(tag, msg)
        write('E', tag, msg, tr)
    }

    private fun write(level: Char, tag: String, msg: String, tr: Throwable?) {
        // Must never throw back into the caller -- a logging call is not
        // allowed to be the reason something else breaks. If the file
        // isn't writable for whatever reason, logcat (already written
        // above) is silently all we get for that line.
        try {
            synchronized(lock) {
                val file = logFile
                file.parentFile?.mkdirs()
                rotateIfNeeded(file)
                FileWriter(file, true).use { fw ->
                    fw.append(TIMESTAMP.format(Date()))
                        .append(' ').append(level)
                        .append('/').append(tag).append(": ").append(msg).append('\n')
                    if (tr != null) {
                        val sw = StringWriter()
                        tr.printStackTrace(PrintWriter(sw))
                        fw.append(sw.toString())
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun rotateIfNeeded(file: File) {
        if (file.length() < MAX_BYTES) return
        val backup = File(file.parentFile, "${file.name}.1")
        backup.delete()
        file.renameTo(backup)
    }
}
