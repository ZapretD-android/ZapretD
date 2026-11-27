package com.zapretd

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap

class SocksFront(private val port: Int, private val upstreamPort: Int) {
    private var server: ServerSocket? = null

    @Volatile
    private var logged = 0

    @Volatile
    private var tcpLogged = 0

    private val dnsCache = ConcurrentHashMap<String, Pair<Long, List<ByteArray>>>()

    fun start() {
        val socket = ServerSocket(port, 64, InetAddress.getByName("127.0.0.1"))
        server = socket
        Thread {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break
                }
                Thread { handle(client) }.start()
            }
        }.start()
    }

    fun stop() {
        try {
            server?.close()
        } catch (e: Exception) {
        }
    }

    private fun readFully(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buffer, offset, count - offset)
            if (read < 0) throw EOFException()
            offset += read
        }
        return buffer
    }

    private fun reply(client: Socket, code: Int) {
        client.getOutputStream().write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        client.getOutputStream().flush()
    }

    private fun resolve(name: String): InetAddress? = try {
        val all = InetAddress.getAllByName(name)
        val chosen = all.firstOrNull { it is Inet4Address } ?: all.firstOrNull()
        if (chosen != null && logged < 12) {
            logged++
            AppLog.add("DNS $name → ${chosen.hostAddress}")
        }
        chosen
    } catch (e: Exception) {
        AppLog.add("DNS не нашёл $name")
        null
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            val input = client.getInputStream()
            val head = readFully(input, 2)
            if (head[0].toInt() != 5) return
            readFully(input, head[1].toInt() and 0xFF)
            client.getOutputStream().write(byteArrayOf(5, 0))

            val request = readFully(input, 4)
            var target: InetAddress? = null
            var domain: String? = null
            when (request[3].toInt()) {
                1 -> target = InetAddress.getByAddress(readFully(input, 4))
                4 -> target = InetAddress.getByAddress(readFully(input, 16))
                3 -> {
                    val length = readFully(input, 1)[0].toInt() and 0xFF
                    domain = String(readFully(input, length))
                }
                else -> {
                    reply(client, 8)
                    return
                }
            }
            val portBytes = readFully(input, 2)

            if (request[1].toInt() == 3) {
                serveUdp(client, input)
                return
            }
            if (request[1].toInt() != 1) {
                reply(client, 7)
                return
            }

            if (domain != null) target = resolve(domain)
            if (target == null) {
                reply(client, 4)
                return
            }
            if (target.hostAddress == "198.18.0.2") {
                reply(client, 5)
                return
            }
            if (domain == null && tcpLogged < 8) {
                tcpLogged++
                AppLog.add("TCP → ${target.hostAddress}:${((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)}")
            }

            val link = Socket()
            upstream = link
            link.connect(InetSocketAddress("127.0.0.1", upstreamPort), 3000)
            link.tcpNoDelay = true
            val upIn = link.getInputStream()
            val upOut = link.getOutputStream()
            upOut.write(byteArrayOf(5, 1, 0))
            readFully(upIn, 2)

            val address = target.address
            val connect = ByteArrayOutputStream()
            connect.write(byteArrayOf(5, 1, 0, if (address.size == 4) 1 else 4))
            connect.write(address)
            connect.write(portBytes)
            upOut.write(connect.toByteArray())
            upOut.flush()

            val answer = readFully(upIn, 4)
            when (answer[3].toInt()) {
                1 -> readFully(upIn, 6)
                4 -> readFully(upIn, 18)
                3 -> readFully(upIn, (readFully(upIn, 1)[0].toInt() and 0xFF) + 2)
            }
            if (answer[1].toInt() != 0) {
                reply(client, answer[1].toInt())
                return
            }
            reply(client, 0)

            val back = Thread {
                pipe(upIn, client)
                closeQuietly(client)
                closeQuietly(link)
            }
            back.start()
            pipe(input, link)
        } catch (e: Exception) {
        } finally {
            closeQuietly(client)
            if (upstream != null) closeQuietly(upstream)
        }
    }

    private fun pipe(from: InputStream, to: Socket) {
        val buffer = ByteArray(16384)
        try {
            val output = to.getOutputStream()
            while (true) {
                val read = from.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (e: Exception) {
        }
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (e: Exception) {
        }
    }

    private fun serveUdp(client: Socket, input: InputStream) {
        val udp = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        val relay = UdpRelay(udp)
        try {
            val bound = udp.localPort
            val out = client.getOutputStream()
            out.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, (bound shr 8).toByte(), (bound and 0xFF).toByte()))
            out.flush()
            Thread { relay.run() }.start()
            while (input.read() >= 0) {
            }
        } catch (e: Exception) {
        } finally {
            relay.stop()
        }
    }

    private fun lookup(name: String): List<ByteArray>? {
        val cached = dnsCache[name]
        if (cached != null && System.currentTimeMillis() - cached.first < 60000) return cached.second
        return try {
            val found = InetAddress.getAllByName(name).filterIsInstance<Inet4Address>().map { it.address }
            dnsCache[name] = Pair(System.currentTimeMillis(), found)
            if (logged < 40) {
                logged++
                AppLog.add("DNS $name → ${found.joinToString { (it.map { b -> b.toInt() and 0xFF }).joinToString(".") }}")
            }
            found
        } catch (e: Exception) {
            AppLog.add("DNS не нашёл $name")
            null
        }
    }

    private fun dnsAnswer(query: ByteArray): ByteArray? {
        if (query.size < 17) return null
        var pos = 12
        val parts = ArrayList<String>()
        while (pos < query.size) {
            val length = query[pos].toInt() and 0xFF
            if (length == 0) {
                pos++
                break
            }
            if ((length and 0xC0) != 0 || pos + 1 + length > query.size) return null
            parts.add(String(query, pos + 1, length))
            pos += 1 + length
        }
        if (pos + 4 > query.size) return null
        val type = ((query[pos].toInt() and 0xFF) shl 8) or (query[pos + 1].toInt() and 0xFF)
        val end = pos + 4

        var rcode = 0
        var addresses: List<ByteArray> = emptyList()
        if (type == 1) {
            val found = lookup(parts.joinToString("."))
            if (found == null) rcode = 2 else addresses = found
        }

        val out = ByteArrayOutputStream()
        out.write(query, 0, 2)
        val flags = 0x8180 or rcode
        out.write(flags shr 8)
        out.write(flags and 0xFF)
        out.write(0)
        out.write(1)
        out.write(addresses.size shr 8)
        out.write(addresses.size and 0xFF)
        out.write(byteArrayOf(0, 0, 0, 0))
        out.write(query, 12, end - 12)
        for (address in addresses) {
            out.write(byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4))
            out.write(address)
        }
        return out.toByteArray()
    }

    private inner class UdpRelay(private val socket: DatagramSocket) {
        @Volatile
        private var peer: SocketAddress? = null

        @Volatile
        private var stopped = false
        private val flows = ConcurrentHashMap<String, DatagramSocket>()

        fun stop() {
            stopped = true
            socket.close()
            for (flow in flows.values) flow.close()
            flows.clear()
        }

        fun run() {
            val buffer = ByteArray(65535)
            while (!stopped) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: Exception) {
                    break
                }
                peer = packet.socketAddress
                try {
                    handlePacket(buffer.copyOf(packet.length))
                } catch (e: Exception) {
                }
            }
        }

        private fun sendToPeer(header: ByteArray, payload: ByteArray) {
            val target = peer ?: return
            val data = header + payload
            try {
                socket.send(DatagramPacket(data, data.size, target))
            } catch (e: Exception) {
            }
        }

        private fun handlePacket(data: ByteArray) {
            if (data.size < 10 || data[2].toInt() != 0) return
            var offset = 4
            val addressSize = when (data[3].toInt()) {
                1 -> 4
                4 -> 16
                else -> return
            }
            val addressBytes = data.copyOfRange(offset, offset + addressSize)
            offset += addressSize
            val port = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            offset += 2
            val header = data.copyOfRange(0, offset)
            val payload = data.copyOfRange(offset, data.size)

            if (port == 53) {
                Thread {
                    val answer = dnsAnswer(payload)
                    if (answer != null) sendToPeer(header, answer)
                }.start()
                return
            }
            if (port == 443) return

            val destination = InetAddress.getByAddress(addressBytes)
            val key = "${destination.hostAddress}:$port"
            var flow = flows[key]
            if (flow == null) {
                val created = DatagramSocket()
                created.soTimeout = 60000
                flows[key] = created
                flow = created
                Thread {
                    val reply = ByteArray(65535)
                    try {
                        while (!stopped) {
                            val incoming = DatagramPacket(reply, reply.size)
                            created.receive(incoming)
                            sendToPeer(header, reply.copyOf(incoming.length))
                        }
                    } catch (e: Exception) {
                    } finally {
                        created.close()
                        flows.remove(key)
                    }
                }.start()
            }
            flow.send(DatagramPacket(payload, payload.size, destination, port))
        }
    }
}
