package com.vayunmathur.communicate.data.rcs

import android.content.Context
import android.telephony.ims.ImsManager
import android.telephony.ims.ImsException
import android.telephony.ims.SipDelegateManager
import android.util.Log

/**
 * Reflection bridge over the `@SystemApi`/`@hide` members of otherwise-public
 * framework classes.
 *
 * The SDK bootclasspath wins over `compileOnly` stubs for classes that exist
 * in the public SDK (`ImsManager`, `RcsUceAdapter`), so same-FQN stubs cannot
 * add hidden members. These helpers cross that boundary with reflection; all
 * failures return null / rethrow as documented. Parameter and return *types*
 * come from the `library:rcs-stubs` top-level hidden classes (absent from the
 * SDK, so the stubs resolve normally).
 */
internal object RcsHiddenApi {
    private const val TAG = "RcsHiddenApi"

    /**
     * `ImsManager#getSipDelegateManager(int)` (`@SystemApi`, hidden). Null on
     * any failure. The returned instance is the framework's
     * `SipDelegateManager`, typed as the stub for subsequent calls.
     */
    fun sipDelegateManager(context: Context, subId: Int): SipDelegateManager? {
        if (!RcsFeature.enabled) return null
        return runCatching {
            val ims = context.applicationContext.getSystemService(ImsManager::class.java)
                ?: return null
            val method = ims.javaClass.getMethod("getSipDelegateManager", Int::class.javaPrimitiveType)
            method.invoke(ims, subId) as? SipDelegateManager
        }.getOrElse {
            Log.w(TAG, "getSipDelegateManager failed", it)
            null
        }
    }

    /**
     * `RcsUceAdapter#requestCapabilities(Collection, Executor,
     * CapabilitiesCallback)` (`@SystemApi`, hidden).
     *
     * The callback interface is hidden too, so [callback] is delivered through
     * a [java.lang.reflect.Proxy] implementing the framework interface; method
     * dispatch is by name (`onCapabilitiesReceived` / `onComplete` / `onError`).
     * Returns false when the bridge itself fails (caller treats as no caps).
     */
    fun requestUceCapabilities(
        adapter: Any,
        contactUris: Collection<android.net.Uri>,
        executor: java.util.concurrent.Executor,
        callback: UceCallback,
    ): Boolean {
        if (!RcsFeature.enabled) return false
        return runCatching {
            val callbackClass = Class.forName("android.telephony.ims.RcsUceAdapter\$CapabilitiesCallback")
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                adapter.javaClass.classLoader,
                arrayOf(callbackClass),
            ) { _, method, args ->
                when (method.name) {
                    "onCapabilitiesReceived" -> {
                        @Suppress("UNCHECKED_CAST")
                        val caps = (args?.getOrNull(0) as? List<*>) ?: emptyList<Any>()
                        callback.onCapabilitiesReceived(caps)
                    }
                    "onComplete" -> callback.onComplete()
                    "onError" -> callback.onError(
                        (args?.getOrNull(0) as? Int) ?: -1,
                        (args?.getOrNull(1) as? Long) ?: 0L,
                    )
                    // equals/hashCode/toString on the proxy itself.
                    "toString" -> "RcsUceCallbackProxy"
                    "hashCode" -> System.identityHashCode(callback)
                    "equals" -> args?.getOrNull(0) === callback
                    else -> null
                }
            }
            val method = adapter.javaClass.getMethod(
                "requestCapabilities",
                Collection::class.java,
                java.util.concurrent.Executor::class.java,
                callbackClass,
            )
            method.invoke(adapter, contactUris, executor, proxy)
            true
        }.getOrElse {
            // InvocationTargetException wraps the framework's ImsException /
            // SecurityException — unwrap one level for the caller's catch.
            val cause = (it as? java.lang.reflect.InvocationTargetException)?.cause ?: it
            Log.w(TAG, "requestCapabilities failed", cause)
            if (cause is SecurityException) throw cause
            if (cause is ImsException) throw cause
            false
        }
    }

    /** Dynamic-dispatch target for the UCE proxy (caps are framework objects). */
    interface UceCallback {
        fun onCapabilitiesReceived(caps: List<*>)
        fun onComplete()
        fun onError(errorCode: Int, retryAfterMillis: Long)
    }

    /** Dynamic-dispatch target for the provisioning proxy (config is raw XML bytes). */
    interface ProvisioningCallback {
        fun onConfigurationChanged(configXml: ByteArray)
        fun onConfigurationReset()
        fun onRemoved()
    }

    /**
     * `ProvisioningManager.createForSubscriptionId(int)` (hidden static).
     * Returns the framework manager as `Any` — all further calls go through
     * the helpers below. Null on any failure.
     */
    fun provisioningManager(subId: Int): Any? {
        if (!RcsFeature.enabled) return null
        return runCatching {
            val pmClass = Class.forName("android.telephony.ims.ProvisioningManager")
            val factory = pmClass.getMethod("createForSubscriptionId", Int::class.javaPrimitiveType)
            factory.invoke(null, subId)
        }.getOrElse {
            Log.w(TAG, "createForSubscriptionId failed", it)
            null
        }
    }

    /**
     * `ProvisioningManager#isRcsVolteSingleRegistrationCapable()` (hidden).
     * Tri-state: true/false from the service, null when the bridge itself
     * failed (e.g. missing permission) so callers can treat it as "unknown"
     * rather than "not capable".
     */
    fun isSingleRegCapable(provisioningManager: Any): Boolean? {
        return runCatching {
            val method = provisioningManager.javaClass.getMethod("isRcsVolteSingleRegistrationCapable")
            method.invoke(provisioningManager) as? Boolean
        }.getOrElse {
            Log.w(TAG, "isRcsVolteSingleRegistrationCapable failed", it)
            null
        }
    }

    /**
     * `setRcsClientConfiguration` + `registerRcsProvisioningCallback` (hidden).
     * The callback interface is hidden, so it is delivered through a Proxy
     * like the UCE path. Returns false when the bridge itself fails.
     */
    fun registerProvisioningCallback(
        provisioningManager: Any,
        executor: java.util.concurrent.Executor,
        rcsVersion: String,
        rcsProfile: String,
        callback: ProvisioningCallback,
    ): Boolean {
        if (!RcsFeature.enabled) return false
        return runCatching {
            val pmClass = provisioningManager.javaClass
            val configClass = Class.forName("android.telephony.ims.RcsClientConfiguration")
            val callbackClass = Class.forName("android.telephony.ims.ProvisioningManager\$RcsProvisioningCallback")
            val config = configClass
                .getConstructor(String::class.java, String::class.java, String::class.java, String::class.java)
                .newInstance(rcsVersion, rcsProfile, "Vayun", "Communicate-1.0")
            pmClass.getMethod("setRcsClientConfiguration", configClass).invoke(provisioningManager, config)
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                pmClass.classLoader,
                arrayOf(callbackClass),
            ) { _, method, args ->
                when (method.name) {
                    "onConfigurationChanged" -> callback.onConfigurationChanged(
                        (args?.getOrNull(0) as? ByteArray) ?: ByteArray(0),
                    )
                    "onConfigurationReset" -> callback.onConfigurationReset()
                    "onRemoved" -> callback.onRemoved()
                    "toString" -> "RcsProvisioningCallbackProxy"
                    "hashCode" -> System.identityHashCode(callback)
                    "equals" -> args?.getOrNull(0) === callback
                    else -> null
                }
            }
            pmClass.getMethod(
                "registerRcsProvisioningCallback",
                java.util.concurrent.Executor::class.java,
                callbackClass,
            ).invoke(provisioningManager, executor, proxy)
            true
        }.getOrElse {
            val cause = (it as? java.lang.reflect.InvocationTargetException)?.cause ?: it
            Log.w(TAG, "registerRcsProvisioningCallback failed", cause)
            false
        }
    }

    fun unregisterProvisioningCallback(provisioningManager: Any, callbackProxy: Any? = null) {
        runCatching {
            // Unregister by callback instance when we hold the proxy; otherwise best-effort no-op.
            if (callbackProxy != null) {
                provisioningManager.javaClass
                    .getMethod("unregisterRcsProvisioningCallback", callbackProxy.javaClass.interfaces.first())
                    .invoke(provisioningManager, callbackProxy)
            }
        }
    }
}
