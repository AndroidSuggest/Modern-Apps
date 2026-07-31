package com.vayunmathur.vpn.util

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vayunmathur.vpn.data.VpnConfig
import com.vayunmathur.vpn.data.VpnConfigDao
import com.vayunmathur.vpn.data.VpnConfigEntity
import com.vayunmathur.vpn.data.VpnStats
import com.vayunmathur.vpn.data.WgConfigParser
import com.vayunmathur.vpn.data.toEntity
import com.vayunmathur.vpn.data.toModel
import com.vayunmathur.vpn.service.VpnTunnelService
import java.net.DatagramSocket
import java.net.InetSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class VpnViewModel(
    application: Application,
    private val dao: VpnConfigDao,
) : AndroidViewModel(application) {

    val configs: StateFlow<List<VpnConfig>> =
        dao.flowAll().map { list -> list.map { it.toModel() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _connectingId = MutableStateFlow<Long?>(null)
    val connectingId: StateFlow<Long?> = _connectingId.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _activeStats = MutableStateFlow<VpnStats?>(null)
    val activeStats: StateFlow<VpnStats?> = _activeStats

    // used by service lifecycle — when service stops we clear connecting id
    init {
        viewModelScope.launch {
            while (true) {
                delay(500)
                if (!VpnTunnelService.isRunning) {
                    _connectingId.value = null
                }
            }
        }
    }

    fun startVpn(activity: Activity, config: VpnConfig) {
        val ctx = getApplication<Application>()
        val intent = VpnService.prepare(ctx)
        if (intent != null) {
            activity.startActivityForResult(intent, 1001)
            _status.value = "Granting VPN permission…"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            dao.touch(config.id, System.currentTimeMillis())
            withContext(Dispatchers.Main) {
                val svcIntent = Intent(ctx, VpnTunnelService::class.java).apply {
                    action = VpnTunnelService.ACTION_CONNECT
                    putExtra(
                        VpnTunnelService.EXTRA_CONFIG_JSON,
                        Json.encodeToString(config),
                    )
                }
                _connectingId.value = config.id
                ctx.startService(svcIntent)
                _status.value = "Connecting to ${config.name}…"
            }
        }
    }

    fun stopVpn() {
        val ctx = getApplication<Application>()
        ctx.startService(
            Intent(ctx, VpnTunnelService::class.java).apply {
                action = VpnTunnelService.ACTION_DISCONNECT
            }
        )
        _connectingId.value = null
        _status.value = "Disconnected"
    }

    fun isActive(configId: Long): Boolean {
        return VpnTunnelService.isRunning && _connectingId.value == configId
    }

    fun generateKeys(): Pair<String, String> {
        return try {
            val priv = VpnNative.generatePrivateKey()
            val pubKey = VpnNative.derivePublicKey(priv)
            priv to pubKey
        } catch (e: Throwable) {
            Log.e("VpnVM", "keygen", e)
            "" to ""
        }
    }

    fun upsert(config: VpnConfig, onSaved: ((Long) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val newId = dao.upsert(config.toEntity())
            onSaved?.let { withContext(Dispatchers.Main) { it(newId) } }
        }
    }

    fun delete(config: VpnConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.delete(config.toEntity())
        }
    }

    fun importFromText(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { WgConfigParser.parse(text).getOrThrow() }
                .onSuccess { imp ->
                    try {
                        val derivedPub = VpnNative.derivePublicKey(imp.privateKey)
                        val config = VpnConfig(
                            name = imp.peerEndpoint.substringBefore(':'),
                            privateKey = imp.privateKey,
                            publicKey = derivedPub,
                            address = imp.address,
                            dns = imp.dns,
                            mtu = imp.mtu,
                            peerPublicKey = imp.peerPublicKey,
                            peerPresharedKey = imp.peerPresharedKey,
                            peerAllowedIPs = imp.peerAllowedIps,
                            peerEndpoint = imp.peerEndpoint,
                            peerKeepalive = imp.peerKeepalive,
                        )
                        dao.upsert(config.toEntity())
                        _status.value = "Imported ${config.name}"
                    } catch (e: Exception) {
                        _status.value = "Import derivation failed: ${e.message}"
                    }
                }
                .onFailure { _status.value = "Import failed: ${it.message}" }
        }
    }

    suspend fun preflight(endpoint: String, timeoutMs: Int = 2000): Boolean {
        return withContext(Dispatchers.IO) {
            val host = endpoint.substringBefore(':').trim()
            val port = endpoint.substringAfterLast(':').toIntOrNull() ?: 51820
            if (host.isEmpty()) return@withContext true
            try {
                DatagramSocket().use { sock ->
                    sock.soTimeout = timeoutMs
                    val addr = InetSocketAddress(host, port)
                    // quick probe: send a single null byte, expect ICMP unreachable etc — we only test reachability of socket create
                    sock.connect(addr)
                    true
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    fun clearStatus() { _status.value = null }

    @Composable
    fun configState(id: Long, default: () -> VpnConfig = { VpnConfig() }): VpnConfig {
        val list by configs.collectAsStateWithLifecycle()
        return list.firstOrNull { it.id == id } ?: default()
    }
}

class VpnViewModelFactory(
    private val application: Application,
    private val dao: VpnConfigDao,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(VpnViewModel::class.java))
        return VpnViewModel(application, dao) as T
    }
}
