package dev.shizzi.adb

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class RecoveryIoTest {
    @Test
    fun `a recovery deadline closes a blocked socket and permits another attempt`() = runBlocking {
        ServerSocket(0).use { server ->
            Socket("127.0.0.1", server.localPort).use { socket ->
                server.accept().use {
                    val entered = AtomicBoolean(false)
                    val result = runCatching {
                        withTimeout(500) {
                            recoveryIo(socket::close) {
                                entered.set(true)
                                socket.getInputStream().read()
                            }
                        }
                    }
                    assertTrue(entered.get())
                    assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
                    assertTrue(socket.isClosed)
                    ensureActive()
                    var completed = false
                    var closed = false
                    recoveryIo({ closed = true }) { completed = true }
                    assertTrue(completed)
                    assertTrue(closed)
                }
            }
        }
    }

    @Test
    fun `stopping protection cancels blocked work and closes its connection`() = runBlocking {
        withTimeout(5_000) {
            ServerSocket(0).use { server ->
                Socket("127.0.0.1", server.localPort).use { socket ->
                    server.accept().use {
                        val entered = CompletableDeferred<Unit>()
                        val job = launch {
                            recoveryIo(socket::close) {
                                entered.complete(Unit)
                                socket.getInputStream().read()
                            }
                        }
                        entered.await()
                        job.cancelAndJoin()
                        assertTrue(job.isCancelled)
                        assertTrue(socket.isClosed)
                    }
                }
            }
        }
    }
}
