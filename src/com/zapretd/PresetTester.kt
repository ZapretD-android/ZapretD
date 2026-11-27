package com.zapretd

import android.content.Context
import java.io.BufferedReader
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class ProbeResult(
    val preset: String,
    val ms: Long,
    val note: String = "",
    val baseline: Boolean = false,
    val passed: Int = 0,
    val total: Int = 0,
    val detail: String = "",
    val youtube: ServiceScore = ServiceScore(),
    val discord: ServiceScore = ServiceScore()
)

class ServiceScore(val passed: Int = 0, val total: Int = 0, val ms: Long = -1)

private class Measure(
    val passed: Int,
    val total: Int,
    val ms: Long,
    val detail: String,
    val failures: String,
    val youtube: ServiceScore,
    val discord: ServiceScore
)

class PresetTester(private val context: Context, private val settings: Settings) {
    @Volatile
    var cancelled = false

    private val addresses = HashMap<String, InetAddress>()

    private fun targets(): List<String> {
        val list = ArrayList<String>()
        if (settings.discord) {
            list.add("discord.com")
            list.add("gateway.discord.gg")
        }
        if (settings.youtube) {
            list.add("www.youtube.com")
            list.add("i.ytimg.com")
        }
        return list
    }

    private fun serviceOf(host: String) = if (host.contains("discord")) "Discord" else "YouTube"

    @Synchronized
    private fun addressOf(host: String): InetAddress {
        val cached = addresses[host]
        if (cached != null) return cached
        val all = InetAddress.getAllByName(host)
        val chosen = all.firstOrNull { it is Inet4Address } ?: all[0]
        addresses[host] = chosen
        return chosen
    }

    private fun readExactly(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) throw EOFException()
            offset += read
        }
    }

    private fun connectThroughProxy(socket: Socket, proxyPort: Int, address: InetAddress) {
        socket.connect(InetSocketAddress("127.0.0.1", proxyPort), TIMEOUT_MS)
        val output = socket.getOutputStream()
        val input = socket.getInputStream()
        output.write(byteArrayOf(5, 1, 0))
        readExactly(input, ByteArray(2))
        val raw = address.address
        val request = ByteArray(4 + raw.size + 2)
        request[0] = 5
        request[1] = 1
        request[3] = if (raw.size == 4) 1 else 4
        System.arraycopy(raw, 0, request, 4, raw.size)
        request[request.size - 2] = (443 shr 8).toByte()
        request[request.size - 1] = (443 and 0xFF).toByte()
        output.write(request)
        val header = ByteArray(4)
        readExactly(input, header)
        if (header[1].toInt() != 0) throw IllegalStateException("socks")
        readExactly(input, ByteArray(if (header[3].toInt() == 4) 18 else 6))
    }

    private fun describe(error: Exception): String = when (error) {
        is SocketTimeoutException -> "таймаут"
        is UnknownHostException -> "DNS"
        is ConnectException -> "отказ соединения"
        is SSLException -> if ((error.message ?: "").contains("reset", true)) "сброс при TLS" else "ошибка TLS"
        is SocketException -> if ((error.message ?: "").contains("reset", true)) "сброс соединения" else "обрыв"
        is EOFException -> "закрыто без ответа"
        is IllegalStateException -> "SOCKS отказал"
        else -> error.javaClass.simpleName
    }

    private fun probe(host: String, proxyPort: Int?): Pair<Long, String> {
        val started = System.nanoTime()
        val raw = Socket()
        try {
            raw.soTimeout = TIMEOUT_MS
            val address = addressOf(host)
            if (proxyPort == null) {
                raw.connect(InetSocketAddress(address, 443), TIMEOUT_MS)
            } else {
                connectThroughProxy(raw, proxyPort, address)
            }
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(raw, host, 443, true) as SSLSocket
            ssl.soTimeout = TIMEOUT_MS
            ssl.startHandshake()
            ssl.outputStream.write("HEAD / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
            ssl.outputStream.flush()
            val line = BufferedReader(InputStreamReader(ssl.inputStream)).readLine()
            if (line == null || !line.startsWith("HTTP/")) return Pair(-1L, "$host: нет HTTP-ответа")
            return Pair((System.nanoTime() - started) / 1_000_000, "")
        } catch (e: Exception) {
            return Pair(-1L, "$host: ${describe(e)}")
        } finally {
            try {
                raw.close()
            } catch (e: Exception) {
            }
        }
    }

    private fun measure(proxyPort: Int?): Measure {
        val hosts = targets()
        val results = Array(hosts.size) { Pair(-1L, "") }
        val threads = hosts.mapIndexed { index, host ->
            Thread { results[index] = probe(host, proxyPort) }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val perService = LinkedHashMap<String, IntArray>()
        for ((index, host) in hosts.withIndex()) {
            val counter = perService.getOrPut(serviceOf(host)) { IntArray(2) }
            counter[1]++
            if (results[index].first >= 0) counter[0]++
        }
        val detail = perService.entries.joinToString(" · ") { "${it.key} ${it.value[0]}/${it.value[1]}" }
        val times = results.filter { it.first >= 0 }.map { it.first }
        val average = if (times.isEmpty()) -1L else times.sum() / times.size
        val failures = results.filter { it.first < 0 }.joinToString("; ") { it.second }

        fun scoreOf(service: String): ServiceScore {
            var total = 0
            val ok = ArrayList<Long>()
            for ((index, host) in hosts.withIndex()) {
                if (serviceOf(host) != service) continue
                total++
                if (results[index].first >= 0) ok.add(results[index].first)
            }
            return ServiceScore(ok.size, total, if (ok.isEmpty()) -1L else ok.sum() / ok.size)
        }
        return Measure(times.size, hosts.size, average, detail, failures, scoreOf("YouTube"), scoreOf("Discord"))
    }

    private fun resultOf(name: String, measure: Measure) =
        ProbeResult(
            name, measure.ms, measure.failures, false, measure.passed, measure.total, measure.detail,
            measure.youtube, measure.discord
        )

    private fun failed(name: String, reason: String) =
        ProbeResult(name, -1, reason, false, 0, targets().size, "")

    fun scan(presets: List<Preset>, onResult: (ProbeResult) -> Unit) {
        val base = measure(null)
        val dns = targets().joinToString(", ") { host ->
            try {
                "$host=${addressOf(host).hostAddress}"
            } catch (e: Exception) {
                "$host=нет DNS"
            }
        }
        AppLog.add("Без обхода: ${base.detail}${if (base.ms >= 0) " · ${base.ms} мс" else ""} ${base.failures}")
        AppLog.add("DNS: $dns")
        val note = if (base.failures.isEmpty()) dns else base.failures + "\n" + dns
        onResult(ProbeResult("Без обхода", base.ms, note, true, base.passed, base.total, base.detail))
        if (settings.useRoot) scanRoot(presets, onResult) else scanProxy(presets, onResult)
    }

    private fun logResult(result: ProbeResult) {
        val text = if (result.passed > 0) "${result.detail} · ${result.ms} мс" else result.note
        AppLog.add("${result.preset}: $text")
    }

    private fun scanRoot(presets: List<Preset>, onResult: (ProbeResult) -> Unit) {
        try {
            for (preset in presets) {
                if (cancelled) break
                val started = RootEngine.start(context, settings, preset.name)
                val result = if (started.ok) {
                    Thread.sleep(500)
                    resultOf(preset.name, measure(null))
                } else {
                    failed(preset.name, started.message)
                }
                RootEngine.stop()
                logResult(result)
                onResult(result)
            }
        } finally {
            RootEngine.stop()
        }
    }

    private fun scanProxy(presets: List<Preset>, onResult: (ProbeResult) -> Unit) {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libciadpi.so")
        if (!binary.exists()) {
            for (preset in presets) onResult(failed(preset.name, "нет бинарника ByeDPI"))
            return
        }
        val hostsFile = File(context.cacheDir, "scan-hosts.txt")
        hostsFile.writeText(Catalog.proxyHosts(context, settings).joinToString("\n"))

        val pool = Executors.newFixedThreadPool(3)
        for (preset in presets) {
            pool.execute {
                if (cancelled) return@execute
                val port = Catalog.freePort()
                val command = ArrayList<String>()
                command.addAll(listOf(binary.absolutePath, "-i", "127.0.0.1", "-p", port.toString(), "-U"))
                command.addAll(listOf("-H", hostsFile.absolutePath))
                command.addAll(preset.byedpi.split(" "))
                var process: Process? = null
                val result = try {
                    process = ProcessBuilder(command).redirectErrorStream(true).start()
                    Thread.sleep(400)
                    resultOf(preset.name, measure(port))
                } catch (e: Exception) {
                    failed(preset.name, e.message ?: "ByeDPI не запустился")
                } finally {
                    process?.destroy()
                }
                if (!cancelled) {
                    logResult(result)
                    onResult(result)
                }
            }
        }
        pool.shutdown()
        try {
            while (!pool.awaitTermination(300, TimeUnit.MILLISECONDS)) {
                if (cancelled) pool.shutdownNow()
            }
        } catch (e: InterruptedException) {
            pool.shutdownNow()
        }
    }

    companion object {
        private const val TIMEOUT_MS = 4000
    }
}
