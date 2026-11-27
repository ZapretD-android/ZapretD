package com.zapretd

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.ParcelFileDescriptor
import io.github.dovecoteescapee.byedpi.core.TProxyService
import java.io.File

class ZapretVpnService : VpnService() {
    private var tunnel: ParcelFileDescriptor? = null
    private var proxy: Process? = null
    private var front: SocksFront? = null
    private var tproxy: TProxyService? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        if (running || starting) return START_NOT_STICKY
        starting = true
        startForeground(NOTIFICATION_ID, buildNotification())
        Thread { boot() }.start()
        return START_NOT_STICKY
    }

    private fun boot() {
        try {
            val settings = Settings(this)
            val preset = Catalog.find(this, settings.strategy, false)
            if (preset == null) {
                fail("Пресеты не найдены")
                return
            }
            val upstreamPort = Catalog.freePort()
            val frontPort = Catalog.freePort()
            val hosts = Catalog.proxyHosts(this, settings)
            if (hosts.isEmpty()) {
                fail("Включи хотя бы один сервис")
                return
            }

            val hostsFile = File(filesDir, "hosts.txt")
            hostsFile.writeText(hosts.joinToString("\n"))

            val binary = File(applicationInfo.nativeLibraryDir, "libciadpi.so")
            if (!binary.exists()) {
                fail("Для этой архитектуры нет бинарника ByeDPI")
                return
            }

            val command = ArrayList<String>()
            command.addAll(listOf(binary.absolutePath, "-i", "127.0.0.1", "-p", upstreamPort.toString(), "-U"))
            if (preset.name == Catalog.COMBO) {
                command.addAll(comboArguments(settings))
            } else {
                command.addAll(listOf("-H", hostsFile.absolutePath))
                command.addAll(preset.byedpi.split(" "))
            }
            AppLog.add("ByeDPI: " + command.drop(1).joinToString(" "))

            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            proxy = process
            Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine { AppLog.add(it) }
                } catch (e: Exception) {
                }
            }.start()

            Thread.sleep(500)
            val exited = try {
                process.exitValue()
                true
            } catch (e: IllegalThreadStateException) {
                false
            }
            if (exited) {
                fail("ByeDPI сразу завершился")
                return
            }

            val socksFront = SocksFront(frontPort, upstreamPort)
            socksFront.start()
            front = socksFront

            val configFile = File(filesDir, "tunnel.yml")
            configFile.writeText(tunnelConfig(frontPort))

            val descriptor = Builder()
                .setSession("zapretD")
                .setMtu(MTU)
                .addAddress(TUNNEL_ADDRESS, 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(DNS_ADDRESS)
                .addDisallowedApplication(packageName)
                .establish()
            if (descriptor == null) {
                fail("Система не дала создать VPN")
                return
            }
            tunnel = descriptor

            val service = TProxyService()
            tproxy = service
            service.TProxyStartService(configFile.absolutePath, descriptor.fd)

            running = true
            starting = false
            AppLog.add("VPN запущен: ${preset.name}")
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        } catch (e: UnsatisfiedLinkError) {
            fail("Не загрузилась библиотека туннеля: ${e.message}")
        }
    }

    private fun comboArguments(settings: Settings): List<String> {
        val result = ArrayList<String>()
        val youtube = Catalog.youtubeHosts(this, settings)
        val discord = Catalog.discordHosts(this, settings)
        if (youtube.isNotEmpty()) {
            val file = File(filesDir, "hosts-youtube.txt")
            file.writeText(youtube.joinToString("\n"))
            result.addAll(listOf("-H", file.absolutePath))
            result.addAll(Catalog.argsOf(this, settings.comboYoutube).split(" "))
            AppLog.add("YouTube: ${settings.comboYoutube}")
        }
        if (discord.isNotEmpty()) {
            if (youtube.isNotEmpty()) result.addAll(listOf("-A", "none"))
            val file = File(filesDir, "hosts-discord.txt")
            file.writeText(discord.joinToString("\n"))
            result.addAll(listOf("-H", file.absolutePath))
            result.addAll(Catalog.argsOf(this, settings.comboDiscord).split(" "))
            AppLog.add("Discord: ${settings.comboDiscord}")
        }
        return result
    }

    private fun tunnelConfig(frontPort: Int): String = listOf(
        "tunnel:",
        "  mtu: $MTU",
        "  ipv4: $TUNNEL_ADDRESS",
        "socks5:",
        "  port: $frontPort",
        "  address: 127.0.0.1",
        "  udp: 'udp'",
        "misc:",
        "  task-stack-size: 81920",
        "  connect-timeout: 5000",
        "  read-write-timeout: 60000",
        "  log-level: warn"
    ).joinToString("\n") + "\n"

    private fun fail(message: String) {
        AppLog.add("Ошибка: $message")
        starting = false
        shutdown()
        stopSelf()
    }

    private fun shutdown() {
        running = false
        try {
            tproxy?.TProxyStopService()
        } catch (e: Exception) {
        }
        tproxy = null
        try {
            tunnel?.close()
        } catch (e: Exception) {
        }
        tunnel = null
        front?.stop()
        front = null
        proxy?.destroy()
        proxy = null
        stopForeground(true)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), 0)
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, ZapretVpnService::class.java).setAction(ACTION_STOP), 0
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_stat), "Выключить", stopIntent
        ).build()
        val builder = Notification.Builder(this)
            .setContentTitle("zapretD")
            .setContentText("Обход блокировок включён")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(openIntent)
            .addAction(stopAction)
            .setOngoing(true)
        attachChannel(builder)
        return builder.build()
    }

    private fun attachChannel(builder: Notification.Builder) {
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE)
            val channelClass = Class.forName("android.app.NotificationChannel")
            val channel = channelClass
                .getConstructor(String::class.java, CharSequence::class.java, Int::class.javaPrimitiveType)
                .newInstance(CHANNEL_ID, "Zapret", 2)
            manager.javaClass.getMethod("createNotificationChannel", channelClass).invoke(manager, channel)
            Notification.Builder::class.java.getMethod("setChannelId", String::class.java).invoke(builder, CHANNEL_ID)
        } catch (e: Exception) {
            AppLog.add("Канал уведомлений: ${e.message}")
        }
    }

    override fun onRevoke() {
        shutdown()
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.zapretd.STOP"
        private const val NOTIFICATION_ID = 7
        private const val CHANNEL_ID = "zapret_status"
        private const val MTU = 8500
        private const val TUNNEL_ADDRESS = "198.18.0.1"
        private const val DNS_ADDRESS = "198.18.0.2"

        @Volatile
        var running = false

        @Volatile
        var starting = false
    }
}
