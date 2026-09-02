package com.openminis.app.sandbox.offload

import com.openminis.app.offload.RootShellExecutor
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import org.json.JSONObject

/**
 * Native-offload endpoint for full Android-host root access.
 *
 * Usage from the PRoot shell:
 *   android-root-cli status
 *   android-root-cli exec <command...> [--timeout-ms N]
 */
class RootOffloadHandler : NativeOffloadHandler {
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = request.argv.drop(1)
        if (args.isEmpty() || args.first() == "help" || args.first() == "--help") {
            return NativeOffloadResult(0, HELP)
        }

        return when (args.first()) {
            "--version", "-V" -> NativeOffloadResult(
                0,
                "android-root-cli 1.0 (KernelSU/Magisk su bridge)\n",
            )
            "status" -> status()
            "exec" -> execute(args.drop(1), request)
            else -> execute(args, request)
        }
    }

    private fun status(): NativeOffloadResult {
        val result = RootShellExecutor.execute(
            command = "id; printf '\\n-- SELINUX --\\n'; (getenforce 2>/dev/null || true); " +
                "printf '\\n-- SU --\\n'; (command -v su 2>/dev/null || true)",
            timeoutMs = 15_000L,
        )
        val root = result.isRoot
        val payload = JSONObject()
            .put("available", root)
            .put("uid0", root)
            .put("backend", "KernelSU/Magisk su bridge")
            .put("su_binary", result.suBinary ?: JSONObject.NULL)
            .put("exitCode", result.exitCode)
            .put("stdout", result.stdout)
            .put("stderr", result.stderr)
        return NativeOffloadResult(if (root) 0 else 1, payload.toString(2) + "\n")
    }

    private fun execute(
        rawArgs: List<String>,
        request: NativeOffloadRequest,
    ): NativeOffloadResult {
        if (rawArgs.isEmpty()) {
            return NativeOffloadResult(2, "android-root-cli: command is empty\n$HELP")
        }

        val commandParts = ArrayList<String>(rawArgs.size)
        var timeoutMs = 600_000L
        var index = 0
        while (index < rawArgs.size) {
            val argument = rawArgs[index]
            if (argument == "--timeout-ms") {
                if (index + 1 >= rawArgs.size) {
                    return NativeOffloadResult(2, "android-root-cli: --timeout-ms needs a value\n")
                }
                timeoutMs = rawArgs[index + 1].toLongOrNull()?.coerceIn(100L, 900_000L)
                    ?: return NativeOffloadResult(2, "android-root-cli: invalid --timeout-ms value\n")
                index += 2
            } else {
                commandParts += argument
                index += 1
            }
        }

        val command = commandParts.joinToString(" ").trim()
        if (command.isEmpty()) {
            return NativeOffloadResult(2, "android-root-cli: command is empty\n")
        }

        val result = RootShellExecutor.execute(
            command = command,
            timeoutMs = timeoutMs,
            environment = request.env,
        )
        val payload = JSONObject()
            .put("ok", result.exitCode == 0)
            .put("command", command)
            .put("exitCode", result.exitCode)
            .put("stdout", result.stdout)
            .put("stderr", result.stderr)
            .put("combined", result.combined)
            .put("su_binary", result.suBinary ?: JSONObject.NULL)
            .put("uid0Verified", result.isRoot)
        return NativeOffloadResult(result.exitCode, payload.toString(2) + "\n")
    }

    private companion object {
        const val HELP = """
android-root-cli — full Android host root through KernelSU/Magisk su

Commands:
  android-root-cli status
  android-root-cli exec <command...> [--timeout-ms N]
  android-root-cli <command...> [--timeout-ms N]

Commands run outside Alpine/PRoot in the Android host namespace.
"""
    }
}
