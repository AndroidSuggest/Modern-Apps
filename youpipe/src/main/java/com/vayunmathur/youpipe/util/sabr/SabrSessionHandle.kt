package com.vayunmathur.youpipe.util.sabr

import android.content.Context
import java.io.IOException
import java.util.IdentityHashMap
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/** Coordinates one lazy SABR session lease across overlapping MediaPeriods and loader threads. */
internal class SabrSessionHandle(
    context: Context,
    private val spec: SabrSourceSpec
) {
    private val appContext: Context = context.applicationContext
    private val trackModes: MutableMap<Any, Int> = IdentityHashMap()

    private var lease: SabrSessionStore.Lease? = null
    private var acquisition: FutureTask<SabrSessionStore.Lease>? = null
    private var activePeriods = 0
    private var periodGeneration: Long = 0
    private var playerTimeMs: Long = 0
    private var pendingSeekMs: Long = -1

    @Synchronized
    fun onPeriodCreated(startPositionMs: Long) {
        if (activePeriods == 0) {
            periodGeneration++
        }
        activePeriods++
        if (startPositionMs > 0) {
            playerTimeMs = startPositionMs
            pendingSeekMs = startPositionMs
        }
    }

    fun onPeriodReleased() {
        val leaseToClose: SabrSessionStore.Lease?
        synchronized(this) {
            if (activePeriods > 0) {
                activePeriods--
            }
            if (activePeriods != 0) {
                return
            }
            periodGeneration++
            trackModes.clear()
            pendingSeekMs = -1
            acquisition = null
            leaseToClose = lease
            lease = null
        }
        leaseToClose?.close()
    }

    @Throws(IOException::class)
    fun acquireHolder(): SabrSessionStore.Holder {
        val future: FutureTask<SabrSessionStore.Lease>
        val generation: Long
        val create: Boolean
        synchronized(this) {
            lease?.let { return it.getHolder() }
            if (activePeriods <= 0) {
                throw IOException("SABR period is no longer active for ${spec.videoId}")
            }
            generation = periodGeneration
            if (acquisition == null) {
                acquisition = FutureTask(
                    Callable { SabrSessionStore.acquire(appContext, spec) }
                )
                create = true
            } else {
                create = false
            }
            future = acquisition!!
        }

        if (create) {
            future.run()
        }

        val acquired = await(future)
        synchronized(this) {
            if (activePeriods <= 0 || generation != periodGeneration) {
                if (lease !== acquired) {
                    acquired.close()
                }
                throw IOException("SABR period was released while acquiring ${spec.videoId}")
            }
            lease?.let {
                if (it !== acquired) {
                    acquired.close()
                }
                return it.getHolder()
            }
            lease = acquired
            if (acquisition === future) {
                acquisition = null
            }
            applyPendingState(acquired.getHolder())
            return acquired.getHolder()
        }
    }

    @Throws(IOException::class)
    private fun await(future: FutureTask<SabrSessionStore.Lease>): SabrSessionStore.Lease {
        try {
            return future.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted acquiring SABR session for ${spec.videoId}", e)
        } catch (e: ExecutionException) {
            synchronized(this) {
                if (acquisition === future) {
                    acquisition = null
                }
            }
            val cause = e.cause
            if (cause is IOException) {
                throw cause
            }
            throw IOException("Could not acquire SABR session for ${spec.videoId}", cause)
        }
    }

    private fun applyPendingState(holder: SabrSessionStore.Holder) {
        holder.setPlayerTimeMs(playerTimeMs)
        for ((owner, mode) in trackModes) {
            holder.setActiveTracks(owner, (mode and 1) != 0, (mode and 2) != 0)
        }
        if (pendingSeekMs >= 0) {
            holder.requestSeek(pendingSeekMs, spec.localization)
        }
    }

    fun setActiveTracks(owner: Any, videoActive: Boolean, audioActive: Boolean) {
        val holder: SabrSessionStore.Holder?
        synchronized(this) {
            val mode = (if (videoActive) 1 else 0) or (if (audioActive) 2 else 0)
            if (mode == 0) {
                trackModes.remove(owner)
            } else {
                trackModes[owner] = mode
            }
            holder = lease?.getHolder()
        }
        holder?.setActiveTracks(owner, videoActive, audioActive)
    }

    fun releaseTracks(owner: Any) {
        val holder: SabrSessionStore.Holder?
        synchronized(this) {
            trackModes.remove(owner)
            holder = lease?.getHolder()
        }
        holder?.releaseTracks(owner)
    }

    fun advanceReaderGeneration(owner: Any) {
        getHolder()?.advanceReaderGeneration(owner)
    }

    fun requestSeek(positionMs: Long) {
        val holder: SabrSessionStore.Holder?
        synchronized(this) {
            playerTimeMs = maxOf(0, positionMs)
            pendingSeekMs = playerTimeMs
            holder = lease?.getHolder()
        }
        holder?.requestSeek(playerTimeMs, spec.localization)
    }

    @Synchronized
    fun setPlayerTimeMs(positionMs: Long) {
        playerTimeMs = maxOf(0, positionMs)
        lease?.getHolder()?.setPlayerTimeMs(playerTimeMs)
    }

    @Synchronized
    fun getHolder(): SabrSessionStore.Holder? = lease?.getHolder()

    fun close() {
        val leaseToClose: SabrSessionStore.Lease?
        synchronized(this) {
            activePeriods = 0
            periodGeneration++
            trackModes.clear()
            pendingSeekMs = -1
            acquisition = null
            leaseToClose = lease
            lease = null
        }
        leaseToClose?.close()
        spec.discardPreparedSession()
    }
}
