package com.vayunmathur.auto.network

import com.vayunmathur.auto.protocol.GalTransport
import com.vayunmathur.auto.protocol.TransportKind

/**
 * Where freshly-opened non-TCP transports wait for the pump loop.
 *
 * `HeadUnitServer.accept()` blocks for the TCP loopback path, but USB hands back an
 * already-open accessory file descriptor and WiFi Direct an already-connected socket —
 * neither can block-accept. The connectors wrap those in a [GalTransport] and park them
 * here; `ProjectionService.serve()` drains the queue before blocking on TCP, so the
 * reconnect/backoff ownership stays in one place and every transport runs the same
 * session body. TLS and the whole GAL bring-up are transport-agnostic, so nothing
 * downstream knows which intake won.
 */
object TransportIntake {

    /** An opened transport plus how the phone UI should name the car. */
    data class Pending(
        val transport: GalTransport,
        val carName: String,
        val kind: TransportKind,
    )

    private val queue = ArrayDeque<Pending>()

    @Synchronized
    fun offer(pending: Pending) {
        queue.addLast(pending)
    }

    @Synchronized
    fun take(): Pending? = queue.removeFirstOrNull()
}
