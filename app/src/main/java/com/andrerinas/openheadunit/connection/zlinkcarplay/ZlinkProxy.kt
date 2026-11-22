package com.andrerinas.openheadunit.connection.zlinkcarplay

import com.andrerinas.openheadunit.utils.AppLog
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Transparent TCP loopback proxy between the zLink daemon (root) and the zLink app.
 *
 * Listens on 127.0.0.1 ports 11666, 11777, 11888, 11999 (offset +10000) and pairs each daemon
 * connection with upstream 127.0.0.1:1666/1777/1888/1999.
 */
class ZlinkProxy(private val onNothingToDrop: () -> Unit = {}) {
    @Volatile private var running = false
    private val serverSockets = CopyOnWriteArrayList<ServerSocket>()
    private val activeSockets = CopyOnWriteArrayList<Socket>()
    private val serverThreads = ArrayList<Thread>()

    fun start() {
        ZlinkHub.setProxyDropper { dropConnections() }
        if (running) return
        running = true
        for (port in PORTS) {
            val thread = Thread({ runServer(port) }, "zlink-proxy-$port").also {
                it.isDaemon = true
                it.start()
            }
            serverThreads.add(thread)
        }
    }

    /**
     * Closes every live daemon and app connection. The daemon then runs its own session end ("control
     * Service is down": Bluetooth recovery for CarPlay, restart) and reconnects, which is what brings a
     * disconnected phone back by itself, exactly as after any normal session end.
     */
    fun dropConnections() {
        // Nothing here means the daemon is connected to the zLink app directly (the connections were made before the
        // redirect existed), so closing ours would change nothing: the owner restarts the app instead.
        if (activeSockets.isEmpty()) {
            onNothingToDrop()
            return
        }
        for (sock in activeSockets) { try { sock.close() } catch (_: Exception) {} }
        activeSockets.clear()
    }

    fun stop() {
        ZlinkHub.setProxyDropper(null)
        running = false
        for (ss in serverSockets) {
            try { ss.close() } catch (_: Exception) {}
        }
        serverSockets.clear()
        for (s in activeSockets) {
            try { s.close() } catch (_: Exception) {}
        }
        activeSockets.clear()
        for (t in serverThreads) {
            t.interrupt()
        }
        serverThreads.clear()
    }

    private fun runServer(port: Int) {
        val listenPort = port + PORT_OFFSET
        val backoffs = longArrayOf(1000L, 2000L, 5000L)
        var backoffIdx = 0

        while (running) {
            var ss: ServerSocket? = null
            try {
                ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), listenPort))
                serverSockets.add(ss)
                backoffIdx = 0
                AppLog.i("zlink proxy: listening on $listenPort -> $port")

                while (running && !ss.isClosed) {
                    val daemonSocket = ss.accept()
                    daemonSocket.tcpNoDelay = true
                    activeSockets.add(daemonSocket)

                    val upstreamSocket = Socket()
                    try {
                        upstreamSocket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 3000)
                    } catch (e: IOException) {
                        // Upstream app is not up; let the daemon retry
                        try { upstreamSocket.close() } catch (_: Exception) {}
                        try { daemonSocket.close() } catch (_: Exception) {}
                        activeSockets.remove(daemonSocket)
                        AppLog.w("zlink proxy: upstream connect failed on $port: ${e.message}")
                        continue
                    }

                    upstreamSocket.tcpNoDelay = true
                    activeSockets.add(upstreamSocket)

                    val lockToDaemon = Any()
                    val lockToApp = Any()

                    if (port == PORT_CONTROL) {
                        ZlinkHub.setDaemonControl(daemonSocket.getOutputStream(), lockToDaemon)
                    }

                    Thread({
                        pump(port, true, daemonSocket, upstreamSocket, lockToApp)
                    }, "zlink-pump-d2a-$port").also {
                        it.isDaemon = true
                        it.start()
                    }

                    Thread({
                        pump(port, false, upstreamSocket, daemonSocket, lockToDaemon)
                    }, "zlink-pump-a2d-$port").also {
                        it.isDaemon = true
                        it.start()
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    val delay = backoffs[backoffIdx.coerceAtMost(backoffs.lastIndex)]
                    if (backoffIdx < backoffs.lastIndex) backoffIdx++
                    AppLog.w("zlink proxy: server $listenPort error: ${e.message}, retrying in ${delay}ms")
                    try { Thread.sleep(delay) } catch (_: InterruptedException) { break }
                }
            } finally {
                if (ss != null) {
                    serverSockets.remove(ss)
                    try { ss.close() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun pump(port: Int, d2a: Boolean, from: Socket, to: Socket, outLock: Any) {
        try {
            val input = DataInputStream(BufferedInputStream(from.getInputStream(), 65536))
            val out = to.getOutputStream()
            val h = ByteArray(ZlinkWire.FRAME_HEADER)

            while (running) {
                input.readFully(h)
                if (h[0] != (-1).toByte() || h[1] != (-1).toByte() ||
                    h[2] != (-1).toByte() || h[3] != 16.toByte()
                ) {
                    throw IOException("bad zLink magic")
                }

                val len = ZlinkWire.be32(h, 4)
                if (len < 0 || len > ZlinkWire.MAX_FRAME) {
                    throw IOException("bad frame len $len")
                }

                val ty = ZlinkWire.be32(h, 8)
                var frame = ByteArray(ZlinkWire.FRAME_HEADER + len)
                System.arraycopy(h, 0, frame, 0, ZlinkWire.FRAME_HEADER)
                input.readFully(frame, ZlinkWire.FRAME_HEADER, len)

                if (port == PORT_CONTROL && !d2a && ty == ZlinkMessage.TYPE_INIT) {
                    frame = ZlinkHub.onAppInit(frame)
                }

                val forwardToApp = !(port == PORT_AUDIO && d2a && ZlinkHub.hasSubscribers())
                if (forwardToApp) {
                    synchronized(outLock) {
                        out.write(frame)
                        out.flush()
                    }
                }

                val payload = frame.copyOfRange(ZlinkWire.FRAME_HEADER, frame.size)
                ZlinkHub.publish(port, d2a, ty, payload)
            }
        } catch (e: Exception) {
            if (running) {
                AppLog.w("zlink proxy: pump $port ${if (d2a) "d2a" else "a2d"} closed: ${e.message}")
            }
        } finally {
            if (port == PORT_CONTROL && d2a) {
                try {
                    ZlinkHub.onDaemonControlClosed(from.getOutputStream())
                } catch (_: Exception) {
                    ZlinkHub.onDaemonControlClosed()
                }
            }
            activeSockets.remove(from)
            activeSockets.remove(to)
            try { from.close() } catch (_: Exception) {}
            try { to.close() } catch (_: Exception) {}
        }
    }

    companion object {
        const val PORT_OFFSET = 10000
        const val PORT_AUDIO = 1666
        const val PORT_CONTROL = 1777
        const val PORT_VIDEO = 1888
        const val PORT_EXTRA = 1999

        val PORTS = intArrayOf(PORT_AUDIO, PORT_CONTROL, PORT_VIDEO, PORT_EXTRA)
    }
}
