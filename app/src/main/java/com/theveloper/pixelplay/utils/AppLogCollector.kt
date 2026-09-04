package com.theveloper.pixelplay.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.theveloper.pixelplay.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 应用内日志收集器。
 *
 * 背景：`ReleaseTree` 只放行 WARN 及以上，release 包里 DEBUG/INFO 的导入日志不会进 logcat，
 * 真机排查（如 Poweramp 导入丢历史）拿不到证据。本收集器通过独立 Timber Tree 捕获
 * [minimumPriority] 及以上的日志（默认 release=WARN，可在设置页动态调节），落两处：
 * 1. 内存环形缓冲（[MAX_MEMORY_LINES] 行）——进程内存活，导出最快；
 * 2. 滚动文件（[MAX_FILE_BYTES] 轮转，保留 1 份 .1）——跨进程重启可追溯。
 *
 * 导出时再附一份 logcat 兜底，用于捞取本收集器种植之前 / native / 系统侧的输出。
 *
 * 注意：日志可能包含歌曲标题与文件路径，导出文件由用户主动分享，分享前请自行确认。
 */
object AppLogCollector {

    private const val MAX_MEMORY_LINES = 3_000
    private const val MAX_FILE_BYTES = 1L * 1024 * 1024
    private const val SIZE_CHECK_INTERVAL_BYTES = 32 * 1024
    private const val LOGCAT_LINES = 3_000
    private const val EXPORT_KEEP_MS = 60 * 60 * 1000L

    private const val LOG_DIR = "logs"
    private const val LOG_FILE_NAME = "pixelplay.log"
    private const val EXPORT_DIR = "log_export"

    private const val PREFS_NAME = "log_collector"
    private const val KEY_MIN_PRIORITY = "minimum_priority"

    private val lock = Any()
    private val ring = ArrayDeque<String>()

    private var appContext: Context? = null
    private var logFile: File? = null
    /** 复用同一个追加流：每条日志开关一次文件在高频日志下代价过高。 */
    private var fileStream: FileOutputStream? = null
    private var bytesSinceCheck = 0L
    private var planted = false

    /**
     * 当前最小记录级别（[Log.VERBOSE]..[Log.ERROR]）。release 默认 WARN，debug 默认 VERBOSE。
     * 可在设置页动态调节，立即生效并持久化（下次启动沿用）。
     */
    @Volatile
    private var minimumPriority: Int = if (BuildConfig.DEBUG) Log.VERBOSE else Log.WARN

    private val lineDateFormat: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    /**
     * 安装收集器。应在 Application.onCreate() 中、其他 Timber Tree **之前**调用，
     * 确保后续所有 Timber 输出都被捕获。可重复调用（幂等）。
     */
    fun install(context: Context) {
        val ctx = context.applicationContext
        // 读回上次调节的日志级别（默认 release=WARN / debug=VERBOSE）。
        minimumPriority = runCatching {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_MIN_PRIORITY, minimumPriority)
        }.getOrDefault(minimumPriority)
        synchronized(lock) {
            appContext = ctx
            val dir = File(ctx.filesDir, LOG_DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                // 目录建不出来就只保留内存缓冲，不影响主流程
            }
            logFile = File(dir, LOG_FILE_NAME)
        }
        val shouldPlant = synchronized(lock) {
            if (planted) false else { planted = true; true }
        }
        if (shouldPlant) {
            Timber.plant(
                CollectorTree(
                    minPriorityProvider = { minimumPriority },
                    onLog = { priority, tag, message, throwable ->
                        append(formatLine(priority, tag, message, throwable))
                    }
                )
            )
        }
    }

    /** 当前最小记录级别（[Log.VERBOSE]..[Log.ERROR]）。 */
    fun getMinimumPriority(): Int = minimumPriority

    /** 运行时调节记录级别，立即生效并持久化（下次启动沿用）。 */
    fun setMinimumPriority(priority: Int) {
        val clamped = priority.coerceIn(Log.VERBOSE, Log.ERROR)
        minimumPriority = clamped
        runCatching {
            appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()?.putInt(KEY_MIN_PRIORITY, clamped)?.apply()
        }
    }

    /** 内存缓冲快照（按写入顺序，最旧在前）。 */
    fun snapshot(): List<String> = synchronized(lock) { ring.toList() }

    /** 清空内存缓冲与落盘文件（含轮转备份）。 */
    fun clear() {
        synchronized(lock) {
            ring.clear()
            bytesSinceCheck = 0
            closeStreamLocked() // 先关流再删文件，否则删除后写入会落到已 unlink 的 inode
            runCatching {
                logFile?.parentFile?.listFiles()?.forEach { it.delete() }
            }
        }
    }

    /**
     * 抓取 logcat（仅本应用可见的条目，Android 4.1+ 限制）。
     * 用于捞取收集器种植之前、以及 native / 系统侧的输出。
     */
    fun dumpLogcat(lines: Int = LOGCAT_LINES): String = try {
        val process = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "-v", "threadtime", "-t", lines.toString())
        )
        // logcat -d 读完即退出，读到 EOF 即可；不 waitFor（避免低版本无超时重载）
        val text = process.inputStream.bufferedReader().readText()
        runCatching { process.destroy() }
        text
    } catch (e: Exception) {
        "<logcat unavailable: ${e.message}>"
    }

    /** 导出完整诊断日志到 cacheDir，返回可供 FileProvider 分享的文件。 */
    suspend fun exportLogs(): File = withContext(Dispatchers.IO) {
        val ctx = synchronized(lock) { appContext }
            ?: error("AppLogCollector.install() not called")
        val exportDir = File(ctx.cacheDir, EXPORT_DIR)
        if (!exportDir.exists()) exportDir.mkdirs()
        cleanupOldExports(exportDir)

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(exportDir, "pixelplay-log-$stamp.txt")
        out.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(buildHeader(ctx))
            writer.write("\n")

            // 落盘日志：跨进程会话唯一来源（内存缓冲重启即丢），必须导出
            val persisted = readPersistedLogs()
            writer.write("---- persisted log (${persisted.fileCount} file(s), ${persisted.bytes} bytes) ----\n")
            writer.write(persisted.text)
            if (persisted.text.isNotEmpty() && !persisted.text.endsWith("\n")) writer.write("\n")

            val buffered = snapshot()
            writer.write("\n---- in-memory buffer (${buffered.size} lines, current session) ----\n")
            buffered.forEach { writer.write(it); writer.write("\n") }

            writer.write("\n---- logcat (last $LOGCAT_LINES) ----\n")
            writer.write(dumpLogcat())
        }
        out
    }

    /** 通过系统分享面板导出日志文件。导出本身在 [exportLogs] 完成，这里只负责分享。 */
    fun shareLogFile(file: File) {
        val ctx = synchronized(lock) { appContext } ?: return
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "PixelPlayer diagnostic log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "PixelPlayer log")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(chooser)
    }

    // region 内部实现

    private fun append(line: String) {
        synchronized(lock) {
            while (ring.size >= MAX_MEMORY_LINES) ring.removeFirst()
            ring.addLast(line)
            writeToFileLocked(line)
        }
    }

    private fun writeToFileLocked(line: String) {
        val file = logFile ?: return
        try {
            if (bytesSinceCheck >= SIZE_CHECK_INTERVAL_BYTES) {
                bytesSinceCheck = 0
                if (file.length() > MAX_FILE_BYTES) rotateLocked(file)
            }
            val stream = fileStream ?: FileOutputStream(file, true).also { fileStream = it }
            val payload = (line + "\n").toByteArray(Charsets.UTF_8)
            stream.write(payload)
            bytesSinceCheck += payload.size
        } catch (_: Exception) {
            // 收集失败绝不能影响主流程；此处也不能再走 Timber，否则递归
            closeStreamLocked()
        }
    }

    private fun rotateLocked(file: File) {
        closeStreamLocked()
        try {
            val backup = File(file.parentFile, "$LOG_FILE_NAME.1")
            if (backup.exists()) backup.delete()
            file.renameTo(backup)
        } catch (_: Exception) {
            // 轮转失败就继续往原文件写
        }
    }

    private fun closeStreamLocked() {
        runCatching { fileStream?.close() }
        fileStream = null
    }

    private fun formatLine(priority: Int, tag: String?, message: String, t: Throwable?): String {
        val time = lineDateFormat.get().format(Date())
        val level = when (priority) {
            Log.VERBOSE -> "V"
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            Log.ASSERT -> "A"
            else -> priority.toString()
        }
        val head = "$time $level/${tag ?: "PixelPlay"} [${Thread.currentThread().name}] $message"
        return if (t != null) "$head\n${Log.getStackTraceString(t)}" else head
    }

    private fun buildHeader(context: Context): String = buildString {
        appendLine("=== PixelPlayer Diagnostic Log ===")
        appendLine("Exported: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) buildType=${BuildConfig.BUILD_TYPE}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Locale: ${Locale.getDefault().toLanguageTag()}")
        val file = synchronized(lock) { logFile }
        appendLine("Log file: ${file?.absolutePath} (${file?.length() ?: 0} bytes)")
        appendLine("NOTE: may contain song titles and file paths - review before sharing.")
    }

    private data class PersistedLogs(val text: String, val bytes: Long, val fileCount: Int)

    /** 读取落盘日志（轮转备份在前 = 更旧，当前文件在后）。 */
    private fun readPersistedLogs(): PersistedLogs {
        val file = synchronized(lock) { logFile } ?: return PersistedLogs("", 0L, 0)
        var fileCount = 0
        var bytes = 0L
        val text = StringBuilder()
        for (f in listOf(File(file.parentFile, "$LOG_FILE_NAME.1"), file)) {
            if (!f.exists() || f.length() == 0L) continue
            runCatching {
                fileCount++
                bytes += f.length()
                text.append(f.readText(Charsets.UTF_8))
            }
        }
        return PersistedLogs(text.toString(), bytes, fileCount)
    }

    private fun cleanupOldExports(dir: File) {
        val cutoff = System.currentTimeMillis() - EXPORT_KEEP_MS
        runCatching {
            dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        }
    }

    /** 只记录、不再转发到 Log（输出由 DebugTree / ReleaseTree 负责）。 */
    private class CollectorTree(
        private val minPriorityProvider: () -> Int,
        private val onLog: (Int, String?, String, Throwable?) -> Unit
    ) : Timber.Tree() {
        override fun isLoggable(tag: String?, priority: Int): Boolean {
            // 动态级别：每次调用都读最新值（minimumPriority 为 @Volatile）。
            return priority >= minPriorityProvider()
        }

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            onLog(priority, tag, message, t)
        }
    }

    // endregion
}
