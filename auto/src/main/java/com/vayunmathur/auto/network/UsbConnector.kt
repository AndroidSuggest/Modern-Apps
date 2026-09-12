package com.vayunmathur.auto.network

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.util.Log
import com.vayunmathur.auto.platform.TransportState
import com.vayunmathur.auto.protocol.StreamTransport
import com.vayunmathur.auto.protocol.TransportKind
import com.vayunmathur.auto.protocol.UsbSession

/**
 * The USB (AOAP) bring-up: accessory attach → permission → open → intake.
 *
 * On a real car the head unit puts the phone into accessory mode itself and Android
 * delivers `USB_ACCESSORY_ATTACHED` to `MainActivity` (manifest filter), which forwards
 * the intent here via `onAccessoryIntent`, including re-delivery through `onNewIntent`.
 * The DHU path has no USB at all: this never fires there and TCP loopback is untouched.
 *
 * Bring-up state lives in a [UsbSession] (pure, JVM-tested) mirrored to
 * [TransportState] for the pairing UI. The opened file descriptor becomes a
 * [StreamTransport] — an accessory fd reads as an ordinary fd
 * (`carsetup/setup/UsbConnectionHelper.java`) — parked in [TransportIntake] for the
 * service loop. No retry of its own: a dropped cable surfaces as end-of-stream in the
 * session, owned by the reconnect logic.
 */
object UsbConnector {

    /** Broadcast action for the accessory-permission round-trip; see [UsbReceiver]. */
    const val ACTION_USB_PERMISSION = "com.vayunmathur.auto.USB_PERMISSION"

    private val session = UsbSession()
    private var lastAccessory: UsbAccessory? = null

    /**
     * Handles accessory intents forwarded from `MainActivity`.
     *
     * @return true when the intent was an accessory attach/detach this consumed.
     */
    @Suppress("DEPRECATION")
    fun onAccessoryIntent(context: Context, intent: Intent): Boolean {
        when (intent.action) {
            UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                val accessory =
                    intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)
                        ?: return false
                onAttached(context, accessory)
                return true
            }
            UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                lastAccessory = null
                session.onDetached()
                TransportState.publishUsb(session)
                Log.i(TAG, "USB accessory detached")
                return true
            }
            else -> return false
        }
    }

    /**
     * Handles the permission answer from [UsbReceiver].
     *
     * @return true when the intent was the permission answer this consumed.
     */
    @Suppress("DEPRECATION")
    fun onPermissionResult(context: Context, intent: Intent): Boolean {
        if (intent.action != ACTION_USB_PERMISSION) return false
        val accessory = intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        if (granted && accessory != null) {
            open(context, accessory)
        } else {
            session.onPermissionDenied()
            TransportState.publishUsb(session)
            Log.i(TAG, "USB accessory permission denied")
        }
        return true
    }

    /** Re-requests permission after a denial without a physical replug. */
    fun retryPermission(context: Context) {
        val accessory = lastAccessory ?: return
        if (!session.retryPermission()) return
        TransportState.publishUsb(session)
        requestPermission(context, accessory)
    }

    private fun onAttached(context: Context, accessory: UsbAccessory) {
        lastAccessory = accessory
        val manager = context.getSystemService(UsbManager::class.java)
        session.onAttachedWithPermission(
            accessory.manufacturer,
            accessory.model,
            hasPermission = manager?.hasPermission(accessory) == true,
        )
        TransportState.publishUsb(session)
        Log.i(TAG, "USB accessory attached: ${session.accessoryLabel}")
        val granted = manager?.hasPermission(accessory) == true
        if (granted) open(context, accessory) else requestPermission(context, accessory)
    }

    private fun requestPermission(context: Context, accessory: UsbAccessory) {
        val manager = context.getSystemService(UsbManager::class.java) ?: return
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        // Mutable: the system fills in the grant answer on delivery (required on S+).
        val pending = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        manager.requestPermission(accessory, pending)
    }

    private fun open(context: Context, accessory: UsbAccessory) {
        val manager = context.getSystemService(UsbManager::class.java) ?: return
        val fd: ParcelFileDescriptor = runCatching { manager.openAccessory(accessory) }
            .getOrNull() ?: run {
                session.onOpenFailed("could not open ${accessory.model}")
                TransportState.publishUsb(session)
                return
            }
        val transport = StreamTransport(
            ParcelFileDescriptor.AutoCloseInputStream(fd),
            ParcelFileDescriptor.AutoCloseOutputStream(fd),
        )
        TransportIntake.offer(
            TransportIntake.Pending(
                transport = transport,
                carName = session.accessoryLabel ?: accessory.model ?: CAR_NAME_FALLBACK,
                kind = TransportKind.USB,
            ),
        )
        session.onPermissionGranted()
        TransportState.publishUsb(session)
        Log.i(TAG, "USB accessory open, transport parked for the service loop")
    }

    private const val TAG = "MaAuto.Usb"
    private const val CAR_NAME_FALLBACK = "Car"
}
