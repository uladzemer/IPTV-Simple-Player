package ru.iptvsimple.engine.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Следит за основной сетью приставки.
 *
 * После включения сеть появляется не сразу, а потом ещё может смениться
 * (например, когда поднимается VPN). Канал, запущенный раньше, падает
 * с «адрес сервера не найден» — поэтому приложение ждёт сеть и перезапускает
 * канал, как только она появилась или сменилась.
 */
@Singleton
class NetworkMonitor @Inject constructor(@ApplicationContext context: Context) {

    /** Основная сеть: есть ли она и какая именно (меняется при смене сети). */
    data class State(val online: Boolean, val networkId: String?)

    private val _state = MutableStateFlow(State(online = true, networkId = null))
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        // Без системной службы считаем сеть доступной: лучше лишняя попытка,
        // чем вечная надпись «нет сети» при рабочем интернете.
        if (connectivity != null) {
            val current = connectivity.activeNetwork
            val capabilities = current?.let(connectivity::getNetworkCapabilities)
            _state.value = State(capabilities.hasInternet(), current?.toString())
            connectivity.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        _state.value = State(capabilities.hasInternet(), network.toString())
                    }

                    override fun onLost(network: Network) {
                        _state.value = State(online = false, networkId = null)
                    }
                },
            )
        }
    }

    private fun NetworkCapabilities?.hasInternet() =
        this?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}
