package com.openminis.app.offload

import com.openminis.app.logging.AppLogger
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Executes a command in the Android host namespace through KernelSU/Magisk.
 * The configured su provider remains responsible for the app-level grant.
 */
object RootShellExecutor {
    private const val TAG = "RootShellExecutor"
    private const val DEFAULT_TIMEOUT_MS = 600_000L
    private const val MAX_TIMEOUT_MS = 900_000L
    private const val HOST_PATH =
        "/data/adb/ksu/bin:/data/adb/magisk:/system/bin:/system/xbin:/vendor/bin:/vendor/xbin:/sbin"

    private val suCandidates = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/magisk/su",
        "su",
    )

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val suBinary: String?,
        val started: Boolean,
    ) {
        val combined: String
            get() = buildString {
                append(stdout)
                if (stderr.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(stderr)
                }
            }.trimEnd()

        val isRoot: Boolean
            get() = exitCode == 0 &&
                Regex("""(?:^|\s)uid=0(?:\(|\s|$)""").containsMatchIn(combined)
    }

    fun execute(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        environment: Map<String, String> = emptyMap(),
    ): Result {
        if (command.isBlank()) {
            return Result(2, "", "root command is empty", null, false)
        }

        val boundedTimeout = timeoutMs.coerceIn(100L, MAX_TIMEOUT_MS)
        var lastError: String? = null
        for (binary in suCandidates.distinct()) {
            try {
                return runWithSu(binary, command, boundedTimeout, environment)
            } catch (error: IOException) {
                lastError = error.message ?: error.javaClass.simpleName
            } catch (error: SecurityException) {
                lastError = error.message ?: error.javaClass.simpleName
            }
        }

        return Result(
            exitCode = 127,
            stdout = "",
            stderr = "unable to start su: ${lastError ?: "no su binary found"}",
            suBinary = null,
            started = false,
        )
    }

    private fun runWithSu(
        binary: String,
        command: String,
        timeoutMs: Long,
        environment: Map<String, String>,
    ): Result {
        val processBuilder = ProcessBuilder(binary, "-c", command)
            .redirectErrorStream(false)
        val processEnvironment = processBuilder.environment()
        environment.forEach { (key, value) ->
            if (key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                processEnvironment[key] = value
            }
        }
        processEnvironment["PATH"] = HOST_PATH
        processEnvironment["HOME"] = "/data/local/tmp"
        processEnvironment["TMPDIR"] = "/data/local/tmp"

        val process = processBuilder.start()
        runCatching { process.outputStream.close() }

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutThread = thread(start = true, isDaemon = true, name = "minis-root-stdout") {
            drain(process.inputStream, stdout)
        }
        val stderrThread = thread(start = true, isDaemon = true, name = "minis-root-stderr") {
            drain(process.errorStream, stderr)
        }

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(1_000L, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            joinQuietly(stdoutThread)
            joinQuietly(stderrThread)
            return Result(
                exitCode = 124,
                stdout = stdout.toString().trimEnd(),
                stderr = (stderr.toString().trimEnd() + "\nroot command timed out").trim(),
                suBinary = binary,
                started = true,
            )
        }

        joinQuietly(stdoutThread)
        joinQuietly(stderrThread)
        val result = Result(
            exitCode = process.exitValue(),
            stdout = stdout.toString().trimEnd(),
            stderr = stderr.toString().trimEnd(),
            suBinary = binary,
            started = true,
        )
        AppLogger.info(TAG, "root command finished: binary=$binary exit=${result.exitCode} timeoutMs=$timeoutMs")
        return result
    }

    private fun drain(stream: InputStream, target: StringBuilder) {
        runCatching {
            stream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                val buffer = CharArray(8 * 1024)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    synchronized(target) { target.append(buffer, 0, count) }
                }
            }
        }
    }

    private fun joinQuietly(thread: Thread) {
        try {
            thread.join(2_000L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
