package com.zapretd

import android.content.Context
import java.io.File

class EngineResult(val ok: Boolean, val message: String)

object RootEngine {
    private const val DIR = "/data/local/tmp/zapret"
    private const val QUEUE = 200
    private const val MARK = "0x40000000"
    private const val TCP_PORTS = "80,443,2053,2083,2087,2096,8443"
    private const val UDP_PORTS = "443,19294:19344,50000:50100"

    private fun su(command: String): Pair<Int, String> {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        return Pair(process.waitFor(), output.trim())
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private fun hasRoot(): Boolean = try {
        su("id").second.contains("uid=0")
    } catch (e: Exception) {
        false
    }

    private fun copyAsset(context: Context, asset: String, target: File) {
        context.assets.open(asset).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun stage(context: Context, settings: Settings): File {
        val stage = File(context.filesDir, "stage")
        stage.deleteRecursively()
        val bin = File(stage, "bin").also { it.mkdirs() }
        val lists = File(stage, "lists").also { it.mkdirs() }

        for (name in context.assets.list("bin") ?: emptyArray()) {
            copyAsset(context, "bin/$name", File(bin, name))
        }
        for (name in listOf("list-exclude.txt", "ipset-exclude.txt")) {
            copyAsset(context, "lists/$name", File(lists, name))
        }

        File(lists, "list-general.txt").writeText(Catalog.generalList(context, settings).joinToString("\n"))
        File(lists, "list-google.txt").writeText(Catalog.youtubeList(context, settings).joinToString("\n"))
        File(lists, "list-general-user.txt").writeText(Catalog.PLACEHOLDER_HOST)
        File(lists, "list-exclude-user.txt").writeText(Catalog.PLACEHOLDER_HOST)
        File(lists, "ipset-exclude-user.txt").writeText(Catalog.PLACEHOLDER_IP)
        return stage
    }

    private fun queueRule(tool: String, protocol: String, ports: String): String =
        "$tool -t mangle -A zapret -p $protocol -m multiport --dports $ports " +
            "-m connbytes --connbytes-dir=original --connbytes-mode=packets --connbytes 1:6 " +
            "-j NFQUEUE --queue-num $QUEUE --queue-bypass"

    private fun rulesFor(tool: String): List<String> = listOf(
        "$tool -t mangle -N zapret",
        "$tool -t mangle -A zapret -m mark --mark $MARK/$MARK -j RETURN",
        queueRule(tool, "tcp", TCP_PORTS),
        queueRule(tool, "udp", UDP_PORTS),
        "$tool -t mangle -I POSTROUTING -j zapret"
    )

    private fun cleanupScript(): List<String> {
        val lines = ArrayList<String>()
        for (tool in listOf("iptables", "ip6tables")) {
            lines.add("$tool -t mangle -D POSTROUTING -j zapret 2>/dev/null")
            lines.add("$tool -t mangle -F zapret 2>/dev/null")
            lines.add("$tool -t mangle -X zapret 2>/dev/null")
        }
        lines.add("[ -f $DIR/nfqws.pid ] && kill \$(cat $DIR/nfqws.pid) 2>/dev/null")
        lines.add("for p in \$(pidof libnfqws.so nfqws 2>/dev/null); do kill \$p 2>/dev/null; done")
        lines.add("rm -f $DIR/nfqws.pid")
        return lines
    }

    fun start(context: Context, settings: Settings, presetName: String = settings.preset): EngineResult {
        val preset = Catalog.find(context, presetName, true)
            ?: return EngineResult(false, "Пресеты не найдены")
        val segments = preset.segments.filter { settings.allows(it.kind) }
        if (segments.isEmpty()) return EngineResult(false, "Включи хотя бы один сервис")

        val nfqws = File(context.applicationInfo.nativeLibraryDir, "libnfqws.so")
        if (!nfqws.exists()) return EngineResult(false, "Для этой архитектуры нет бинарника nfqws")
        if (!hasRoot()) return EngineResult(false, "Нет root-доступа. Выдай разрешение в менеджере root")

        val stage = stage(context, settings)

        val command = ArrayList<String>()
        command.add("$DIR/nfqws")
        command.add("--uid=0:0")
        command.add("--daemon")
        command.add("--pidfile=$DIR/nfqws.pid")
        command.add("--debug=@$DIR/nfqws.log")
        command.add("--qnum=$QUEUE")
        command.add("--dpi-desync-fwmark=$MARK")
        for ((index, segment) in segments.withIndex()) {
            if (index > 0) command.add("--new")
            for (arg in segment.args) {
                command.add(arg.replace("{BIN}", "$DIR/bin").replace("{LISTS}", "$DIR/lists"))
            }
        }

        val script = ArrayList<String>()
        script.addAll(cleanupScript())
        script.add("mkdir -p $DIR/bin $DIR/lists")
        script.add("cp -f ${stage.absolutePath}/bin/* $DIR/bin/")
        script.add("cp -f ${stage.absolutePath}/lists/* $DIR/lists/")
        script.add("cp -f ${nfqws.absolutePath} $DIR/nfqws")
        script.add("chmod 755 $DIR/nfqws")
        script.add("chmod 644 $DIR/bin/* $DIR/lists/*")
        script.add("echo 1 > /proc/sys/net/netfilter/nf_conntrack_tcp_be_liberal 2>/dev/null")
        script.add(": > $DIR/nfqws.log")
        script.add(command.joinToString(" ") { quote(it) })
        script.add("sleep 1")
        script.add("if [ -f $DIR/nfqws.pid ] && kill -0 \$(cat $DIR/nfqws.pid) 2>/dev/null; then echo NFQWS_OK; else echo NFQWS_DEAD; fi")
        script.add("if [ -f $DIR/nfqws.pid ]; then")
        script.addAll(rulesFor("iptables").map { "  $it || echo \"RULES_FAILED: $it\"" })
        script.addAll(rulesFor("ip6tables").map { "  $it 2>/dev/null || echo \"IPV6_RULE_FAILED\"" })
        script.add("fi")

        val scriptFile = File(stage, "start.sh")
        scriptFile.writeText(script.joinToString("\n") + "\n")

        val (_, output) = su("sh ${scriptFile.absolutePath}")
        AppLog.add(output)

        if (!output.contains("NFQWS_OK")) {
            val tail = su("tail -n 8 $DIR/nfqws.log").second
            AppLog.add(tail)
            return EngineResult(false, "nfqws не запустился. Возможно, ядро без поддержки NFQUEUE")
        }
        if (output.contains("IPV6_RULE_FAILED")) {
            AppLog.add("IPv6: правила не применились, трафик по IPv6 не обрабатывается")
        }
        if (output.contains("RULES_FAILED")) {
            stop()
            return EngineResult(false, "iptables не принял правила NFQUEUE. Ядро не поддерживает нужные модули")
        }
        return EngineResult(true, "Запущено через root: ${preset.name}")
    }

    fun stop() {
        try {
            su(cleanupScript().joinToString("\n"))
        } catch (e: Exception) {
            AppLog.add("Остановка: ${e.message}")
        }
    }

    fun isRunning(): Boolean = try {
        su("[ -f $DIR/nfqws.pid ] && kill -0 \$(cat $DIR/nfqws.pid) && echo UP").second.contains("UP")
    } catch (e: Exception) {
        false
    }
}
