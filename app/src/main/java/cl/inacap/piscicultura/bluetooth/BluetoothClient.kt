package cl.inacap.piscicultura.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * Cliente Bluetooth RFCOMM para el ROL ESTACIÓN DE MONITOREO.
 * Permite listar dispositivos vinculados y conectarse al Nodo Sensor en Dispatchers.IO con timeout.
 */
class BluetoothClient(private val context: Context) {

    sealed class State {
        object Desconectado : State()
        data class Conectando(val deviceName: String) : State()
        data class Conectado(val deviceName: String, val address: String) : State()
        data class Error(val mensaje: String) : State()
    }

    private val _clientState = MutableStateFlow<State>(State.Desconectado)
    val clientState: StateFlow<State> = _clientState.asStateFlow()

    /**
     * Obtiene la lista de dispositivos Bluetooth emparejados (bondedDevices) de forma segura.
     */
    fun getBondedDevices(bluetoothAdapter: BluetoothAdapter?): List<BluetoothDevice> {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return emptyList()
        return try {
            bluetoothAdapter.bondedDevices?.toList() ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    /**
     * Establece conexión cliente RFCOMM con el dispositivo remoto en Dispatchers.IO con timeout.
     */
    suspend fun connect(
        bluetoothAdapter: BluetoothAdapter?,
        device: BluetoothDevice,
        timeoutMs: Long = BluetoothConstants.CONNECT_TIMEOUT_MS
    ): BluetoothSocket? = withContext(Dispatchers.IO) {
        val deviceName = try { device.name ?: device.address } catch (_: SecurityException) { device.address }
        _clientState.value = State.Conectando(deviceName)

        try {
            // Cancelar descubrimiento para no saturar el canal de radiofrecuencia
            try {
                bluetoothAdapter?.cancelDiscovery()
            } catch (_: SecurityException) {}

            val socket: BluetoothSocket = device.createRfcommSocketToServiceRecord(BluetoothConstants.SPP_UUID)

            // Conexión bloqueante envuelta en corrutina con timeout de seguridad
            withTimeout(timeoutMs) {
                socket.connect()
            }

            _clientState.value = State.Conectado(deviceName, device.address)
            return@withContext socket

        } catch (e: SecurityException) {
            _clientState.value = State.Error("Permiso BLUETOOTH_CONNECT denegado")
            null
        } catch (e: IOException) {
            _clientState.value = State.Error("No fue posible conectar con $deviceName: ${e.message}")
            null
        } catch (e: Exception) {
            _clientState.value = State.Error("Tiempo de conexión agotado ($timeoutMs ms)")
            null
        }
    }

    fun resetState() {
        _clientState.value = State.Desconectado
    }
}
