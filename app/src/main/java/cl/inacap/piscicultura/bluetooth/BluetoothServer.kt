package cl.inacap.piscicultura.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Servidor Bluetooth RFCOMM para el ROL NODO SENSOR (simula el ESP32).
 * Hospeda un BluetoothServerSocket y acepta conexiones entrantes en Dispatchers.IO.
 */
class BluetoothServer(private val context: Context) {

    sealed class State {
        object Desconectado : State()
        object Esperando : State()
        data class Conectado(val deviceName: String, val address: String) : State()
        data class Error(val mensaje: String) : State()
    }

    private val _serverState = MutableStateFlow<State>(State.Desconectado)
    val serverState: StateFlow<State> = _serverState.asStateFlow()

    private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var isRunning = false

    /**
     * Inicia la escucha de conexiones entrantes en un hilo de fondo (Dispatchers.IO).
     * @param onSocketAccepted Callback invocado cuando la Estación se conecta con éxito.
     */
    suspend fun startListening(
        bluetoothAdapter: BluetoothAdapter?,
        onSocketAccepted: (BluetoothSocket) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            _serverState.value = State.Error("El adaptador Bluetooth está apagado o no disponible")
            return@withContext
        }

        try {
            // Abrir el ServerSocket con el UUID estándar SPP
            serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(
                BluetoothConstants.SERVICE_NAME,
                BluetoothConstants.SPP_UUID
            )
            isRunning = true
            _serverState.value = State.Esperando

            while (isRunning) {
                val socket: BluetoothSocket? = try {
                    serverSocket?.accept()
                } catch (e: IOException) {
                    if (!isRunning) null else throw e
                }

                if (socket != null) {
                    val remoteDevice = socket.remoteDevice
                    val name = try { remoteDevice.name ?: "Estación" } catch (_: SecurityException) { "Estación" }
                    val address = remoteDevice.address

                    _serverState.value = State.Conectado(name, address)
                    onSocketAccepted(socket)

                    // Para enlace 1-a-1, cerramos el ServerSocket tras aceptar al cliente
                    stopListening()
                    break
                }
            }
        } catch (e: SecurityException) {
            _serverState.value = State.Error("Falta permiso BLUETOOTH_CONNECT para aceptar conexiones")
        } catch (e: IOException) {
            if (isRunning) {
                _serverState.value = State.Error("Fallo en el servidor Bluetooth: ${e.message}")
            } else {
                _serverState.value = State.Desconectado
            }
        } finally {
            closeQuietly()
        }
    }

    /**
     * Detiene el servidor y libera los sockets de forma segura.
     */
    fun stopListening() {
        isRunning = false
        closeQuietly()
        if (_serverState.value !is State.Conectado) {
            _serverState.value = State.Desconectado
        }
    }

    private fun closeQuietly() {
        try {
            serverSocket?.close()
        } catch (_: IOException) {}
        serverSocket = null
    }
}
