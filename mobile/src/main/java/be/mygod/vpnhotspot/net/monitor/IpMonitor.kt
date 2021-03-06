package be.mygod.vpnhotspot.net.monitor

import android.system.ErrnoException
import android.system.OsConstants
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.DebugHelper
import be.mygod.vpnhotspot.R
import be.mygod.vpnhotspot.util.RootSession
import be.mygod.vpnhotspot.widget.SmartSnackbar
import timber.log.Timber
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

abstract class IpMonitor : Runnable {
    companion object {
        const val KEY = "service.ipMonitor"
        // https://android.googlesource.com/platform/external/iproute2/+/7f7a711/lib/libnetlink.c#493
        private const val MAGIC_SUFFIX = "Dump was interrupted and may be inconsistent."
        private val currentMode get() = Mode.valueOf(app.pref.getString(KEY, Mode.Poll.toString()) ?: "")
    }

    enum class Mode(val isMonitor: Boolean = false) {
        Monitor(true), MonitorRoot(true), Poll, PollRoot
    }

    private class FlushFailure : RuntimeException()
    protected abstract val monitoredObject: String
    protected abstract fun processLine(line: String)
    protected abstract fun processLines(lines: Sequence<String>)

    @Volatile
    private var destroyed = false
    private var monitor: Process? = null
    private var pool: ScheduledExecutorService? = null

    private fun handleProcess(builder: ProcessBuilder) {
        val process = try {
            builder.start()
        } catch (e: IOException) {
            Timber.d(e)
            return
        }
        monitor = process
        val err = thread(name = "${javaClass.simpleName}-error") {
            try {
                process.errorStream.bufferedReader().forEachLine { Timber.e(it) }
            } catch (_: InterruptedIOException) { } catch (e: IOException) {
                if ((e.cause as? ErrnoException)?.errno != OsConstants.EBADF) Timber.w(e)
            }
        }
        try {
            process.inputStream.bufferedReader().forEachLine {
                if (it.endsWith(MAGIC_SUFFIX)) {
                    Timber.w(it)
                    process.destroy()   // move on to next mode
                } else processLine(it)
            }
        } catch (_: InterruptedIOException) { } catch (e: IOException) {
            if ((e.cause as? ErrnoException)?.errno != OsConstants.EBADF) Timber.w(e)
        }
        err.join()
        process.waitFor()
        DebugHelper.log("IpMonitor", "Monitor process exited with ${process.exitValue()}")
    }

    init {
        thread(name = "${javaClass.simpleName}-input") {
            val mode = currentMode
            if (mode.isMonitor) {
                if (mode != Mode.MonitorRoot) {
                    // monitor may get rejected by SELinux enforcing
                    handleProcess(ProcessBuilder("ip", "monitor", monitoredObject))
                    if (destroyed) return@thread
                }
                handleProcess(ProcessBuilder("su", "-c", "exec ip monitor $monitoredObject"))
                if (destroyed) return@thread
                DebugHelper.logEvent("ip_monitor_failure")
            }
            val pool = Executors.newScheduledThreadPool(1)
            pool.scheduleAtFixedRate(this, 1, 1, TimeUnit.SECONDS)
            this.pool = pool
        }
    }

    fun flush() = thread(name = "${javaClass.simpleName}-flush") { run() }

    private fun poll() {
        val process = ProcessBuilder("ip", monitoredObject)
                .redirectErrorStream(true)
                .start()
        process.waitFor()
        thread(name = "${javaClass.simpleName}-flush-error") {
            val err = process.errorStream.bufferedReader().readText()
            if (err.isNotBlank()) {
                Timber.e(err)
                Timber.i(FlushFailure())
                SmartSnackbar.make(R.string.noisy_su_failure).show()
            }
        }
        process.inputStream.bufferedReader().useLines {
            processLines(it.map { line ->
                if (line.endsWith(MAGIC_SUFFIX)) throw IOException(line)
                line
            })
        }
    }

    override fun run() {
        if (currentMode != Mode.PollRoot) try {
            return poll()
        } catch (e: IOException) {
            DebugHelper.logEvent("ip_poll_failure")
            Timber.d(e)
        }
        try {
            val command = "ip $monitoredObject"
            RootSession.use {
                val result = it.execQuiet(command)
                RootSession.checkOutput(command, result, false)
                if (result.out.any { it.endsWith(MAGIC_SUFFIX) }) throw IOException(result.out.joinToString("\n"))
                processLines(result.out.asSequence())
            }
        } catch (e: RuntimeException) {
            DebugHelper.logEvent("ip_su_poll_failure")
            Timber.w(e)
        }
    }

    fun destroy() {
        destroyed = true
        monitor?.destroy()
        pool?.shutdown()
    }
}
