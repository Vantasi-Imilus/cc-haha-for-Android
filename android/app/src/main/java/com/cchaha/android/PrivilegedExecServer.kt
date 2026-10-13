package com.cchaha.android

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * 特权命令执行通道（本地回环 HTTP）。
 *
 * 目的：让 cc-haha 的 Agent/工具链（node 侧）能够以 **shell 身份** 执行命令，
 * 从而访问受限目录、执行特权操作 —— 由 Shizuku/群星之眼授权提供能力。
 *
 * 安全设计：
 *  - 仅绑定 127.0.0.1（回环），不接受外部连接
 *  - 需要随机 token（每次启动重新生成，通过 env 注入 node 进程）
 *  - 仅 POST /exec，body 为 JSON {"cmd": "...", "timeoutMs": 30000}
 *
 * node 侧用法（环境变量已自动注入）：
 *   curl -s -X POST -H "X-CC-Token: $CC_HAHA_PRIV_TOKEN" \
 *        -d "{\"cmd\":\"id\"}" http://127.0.0.1:$CC_HAHA_PRIV_PORT/exec
 */
class PrivilegedExecServer(private val bindPort: Int = 0) {
    companion object {
        private const val TAG = "PrivExec"

        /** 请求体上限（特权命令请求本身很小；此上限防 OOM 攻击） */
        private const val MAX_BODY_BYTES = 64 * 1024
        @Volatile
        var instance: PrivilegedExecServer? = null
            private set

        /** 启动（幂等）；返回 (port, token) */
        fun start(): Pair<Int, String>? {
            instance?.let { return it.port to it.token }
            return try {
                val s = PrivilegedExecServer()
                s.listen()
                instance = s
                s.port to s.token
            } catch (e: Exception) {
                Log.e(TAG, "启动失败", e)
                null
            }
        }
    }

    private var serverSocket: ServerSocket? = null
    private var actualPort: Int = 0
    val port: Int get() = actualPort

    /** 每次启动生成的一次性 token（node 进程通过 env 获得） */
    val token: String = ByteArray(24).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    private fun listen() {
        val ss = ServerSocket(bindPort, 8, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        actualPort = ss.localPort
        Log.i(TAG, "特权通道已启动 127.0.0.1:$actualPort")
        thread(name = "priv-exec-accept", isDaemon = true) {
            while (!ss.isClosed) {
                try {
                    val sock = ss.accept()
                    thread(isDaemon = true) { handle(sock) }
                } catch (e: Exception) {
                    if (!ss.isClosed) Log.w(TAG, "accept 异常: ${e.message}")
                }
            }
        }
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 120_000
                val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val requestLine = input.readLine() ?: return
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: return
                val path = parts.getOrNull(1) ?: return

                // 读 headers
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase()] =
                            line.substring(idx + 1).trim()
                    }
                }
                // 请求体大小上限：防止伪造超大 Content-Length 触发 OOM
                // （分配发生在 token 校验之前，任何能连本端口的进程都可触发）
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
                    respond(s.getOutputStream(), 413, """{"error":"payload too large"}""")
                    return
                }
                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(buf, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    String(buf, 0, read)
                } else ""

                when {
                    method == "POST" && path == "/exec" -> {
                        val provided = headers["x-cc-token"] ?: ""
                        if (!constantTimeEquals(provided, token)) {
                            Log.w(TAG, "token 校验失败")
                            respond(s.getOutputStream(), 403, """{"error":"forbidden"}""")
                            return
                        }
                        if (!ShizukuHelper.permissionGranted) {
                            respond(
                                s.getOutputStream(), 503,
                                """{"error":"no privileged permission (Shizuku/Dhizuku not granted)"}""",
                            )
                            return
                        }
                        val cmd = extractJsonString(body, "cmd")
                        if (cmd.isNullOrBlank()) {
                            respond(s.getOutputStream(), 400, """{"error":"cmd required"}""")
                            return
                        }
                        Log.i(TAG, "执行特权命令: ${cmd.take(200)}")
                        val out = ShizukuHelper.runAsShell(arrayOf("sh", "-c", cmd))
                        val payload = if (out == null) {
                            """{"ok":false,"error":"exec failed"}"""
                        } else {
                            """{"ok":true,"output":${jsonEscape(out)}}"""
                        }
                        respond(s.getOutputStream(), 200, payload)
                    }
                    method == "GET" && path == "/status" -> {
                        respond(
                            s.getOutputStream(), 200,
                            """{"privileged":${ShizukuHelper.permissionGranted},"status":"${ShizukuHelper.statusSummary()}"}""",
                        )
                    }
                    else -> respond(s.getOutputStream(), 404, """{"error":"not found"}""")
                }
            } catch (e: Exception) {
                Log.w(TAG, "处理请求异常: ${e.message}")
            }
        }
    }

    private fun respond(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    /** 恒定时间字符串比较（避免 token 逐字符比较的时序侧信道） */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    /** 极简 JSON 字符串字段提取（避免引入 JSON 库） */
    private fun extractJsonString(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\""
        val m = Regex(pattern).find(json) ?: return null
        val start = m.range.last + 1
        val sb = StringBuilder()
        var i = start
        while (i < json.length) {
            val c = json[i]
            when {
                c == '\\' && i + 1 < json.length -> {
                    when (json[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        else -> sb.append(json[i + 1])
                    }
                    i += 2
                }
                c == '"' -> return sb.toString()
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    private fun jsonEscape(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append("\"").toString()
    }
}
