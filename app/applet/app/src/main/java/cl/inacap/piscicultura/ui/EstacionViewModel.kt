package cl.inacap.piscicultura.ui

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cl.inacap.piscicultura.bluetooth.BluetoothClient
import cl.inacap.piscicultura.bluetooth.ConnectionManager
import cl.inacap.piscicultura.domain.Protocol
import cl.inacap.piscicultura.security.EncryptedSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ViewModel para el rol ESTACIÓN DE MONITOREO.
 * Gestiona el cliente Bluetooth RFCOMM y el dashboard en tiempo real:
 * - Sobrevive a la rotación de pantalla manteniendo la conexión activa.
 * - Despacha comandos (FEED, AIREADOR) en Dispatchers.IO.
 * - Contador regresivo local actualizado cada 1s y resincronizado con cada trama DATA.
 * - Detección de datos desactualizados si transcurren más de 6 segundos sin telemetría.
 * - Manejo robusto de caídas de conexión con eventos para Snackbar y reconexión manual.
 */
class EstacionViewModel(application: Application) : AndroidViewModel(application) {

    data class TelemetryState(
        val temperatura: Float = 21.0f,
        val turbidez: Float = 20.0f,
        val aireadorEncendido: Boolean = false,
        val modoAireador: String = "AUTO",
        val alimentadorActivo: Boolean = false,
        val alimentadorPospuesto: Boolean = false,
        val proxAlimentacionSegundos: Int = 15,
        val manual: Boolean = false,
        val timestamp: Long = 0L
    )

    data class UiState(
        val isConnected: Boolean = false,
        val isConnecting: Boolean = false,
        val connectedDeviceName: String = "",
        val connectedDeviceAddress: String = "",
        val statusText: String = "Desconectado",
        val telemetria: TelemetryState = TelemetryState(),
        val segundosDesdeUltimaActualizacion: Int = 0,
        val isDatosDesactualizados: Boolean = false,
        val selectedDeviceName: String = "Seleccionar dispositivo emparejado..."
    )

    private val bluetoothClient = BluetoothClient(application)
    private val connectionManager = ConnectionManager(viewModelScope)
    private val sessionManager = EncryptedSessionManager(application)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Canal de eventos de un solo uso para mensajes emergentes (Snackbar)
    private val _snackbarEvent = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val snackbarEvent: SharedFlow<String> = _snackbarEvent.asSharedFlow()

    private var selectedDevice: BluetoothDevice? = null
    private var countdownJob: Job? = null
    private var connectionJob: Job? = null
    private var lastDataReceivedTime: Long = 0L

    init {
        observeConnectionManager()
        startPeriodicCountdownAndWatchdog()
    }

    /**
     * Retorna la lista de dispositivos Bluetooth emparejados.
     */
    fun getBondedDevices(): List<BluetoothDevice> {
        return bluetoothClient.getBondedDevices()
    }

    /**
     * Fija el dispositivo Nodo seleccionado para la conexión.
     */
    fun setSelectedDevice(device: BluetoothDevice) {
        selectedDevice = device
        val name = try { device.name ?: device.address } catch (_: SecurityException) { device.address }
        _uiState.value = _uiState.value.copy(
            selectedDeviceName = name,
            connectedDeviceAddress = device.address
        )
    }

    /**
     * Inicia la conexión cliente RFCOMM hacia el Nodo Sensor.
     */
    fun conectar(adapter: BluetoothAdapter?) {
        val device = selectedDevice ?: run {
            emitSnackbar("Por favor selecciona primero un dispositivo de la lista")
            return
        }

        if (_uiState.value.isConnecting || _uiState.value.isConnected) return

        connectionJob?.cancel()
        connectionJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(
                isConnecting = true,
                statusText = "Conectando a ${device.name ?: device.address}..."
            )

            val socket = bluetoothClient.connect(adapter, device)
            if (socket != null) {
                val userEmail = sessionManager.getSessionEmail().ifEmpty { "Estacion-Monitor" }
                // Conexión exitosa: inicializar flujos I/O con ConnectionManager
                connectionManager.start(
                    socket = socket,
                    role = Protocol.ROLE_ESTACION,
                    user = userEmail
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isConnecting = false,
                    isConnected = false,
                    statusText = "Fallo de conexión"
                )
                emitSnackbar("No fue posible conectar con el Nodo Sensor")
            }
        }
    }

    /**
     * Desconexión manual ordenada.
     */
    fun desconectar() {
        connectionJob?.cancel()
        connectionJob = null
        connectionManager.disconnect()
        bluetoothClient.resetState()
        _uiState.value = _uiState.value.copy(
            isConnected = false,
            isConnecting = false,
            statusText = "Desconectado por el usuario"
        )
        emitSnackbar("Conexión finalizada")
    }

    /**
     * Reintenta conectar con el último dispositivo seleccionado.
     */
    fun reconectar(adapter: BluetoothAdapter?) {
        desconectar()
        conectar(adapter)
    }

    /**
     * Envía comando para forzar alimentación.
     */
    fun enviarFeed() {
        if (!_uiState.value.isConnected) {
            emitSnackbar("No hay conexión con el Nodo Sensor")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val cmd = Protocol.CommandMessage(cmd = Protocol.CMD_FEED)
            val sent = connectionManager.sendMessage(cmd)
            if (sent) {
                emitSnackbar("Comando enviado: Forzar alimentación")
            }
        }
    }

    /**
     * Envía comando de cambio de modo de aireador ("AUTO", "ON", "OFF").
     */
    fun setAireador(modo: String) {
        if (!_uiState.value.isConnected) {
            emitSnackbar("No hay conexión con el Nodo Sensor")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val cmd = Protocol.CommandMessage(cmd = Protocol.CMD_AIREADOR, value = modo)
            val sent = connectionManager.sendMessage(cmd)
            if (sent) {
                emitSnackbar("Comando enviado: Aireador en modo $modo")
            }
        }
    }

    /**
     * Escucha mensajes entrantes y estados de la conexión activa.
     */
    private fun observeConnectionManager() {
        // Escucha de tramas JSON recibidas desde el Nodo
        viewModelScope.launch {
            connectionManager.incomingMessages.collect { rawLine ->
                val parsed = Protocol.parseSafe(rawLine) ?: return@collect
                val (type, obj) = parsed

                when (type) {
                    Protocol.TYPE_DATA -> {
                        val data = Protocol.parseData(obj) ?: return@collect
                        processDataMessage(data)
                    }
                    Protocol.TYPE_ACK -> {
                        val ack = Protocol.parseAck(obj) ?: return@collect
                        val estadoStr = if (ack.ok) "ejecutado con éxito" else "denegado (pospuesto)"
                        emitSnackbar("ACK de Nodo: ${ack.ref} $estadoStr")

                        // Si el comando fue FEED y vino denegado/pospuesto (aireador encendido)
                        if (ack.ref == Protocol.CMD_FEED && !ack.ok) {
                            _uiState.value = _uiState.value.copy(
                                telemetria = _uiState.value.telemetria.copy(
                                    alimentadorPospuesto = true,
                                    alimentadorActivo = false
                                )
                            )
                        }
                    }
                }
            }
        }

        // Escucha de estados de conexión (Conectado, Desconectado o Caída forzada)
        viewModelScope.launch {
            connectionManager.connectionState.collect { state ->
                when (state) {
                    is ConnectionManager.ConnectionState.Conectado -> {
                        lastDataReceivedTime = System.currentTimeMillis()
                        _uiState.value = _uiState.value.copy(
                            isConnected = true,
                            isConnecting = false,
                            connectedDeviceName = state.remoteDeviceName,
                            statusText = "Conectado a ${state.remoteDeviceName}"
                        )
                        emitSnackbar("Conectado con éxito a ${state.remoteDeviceName}")
                    }
                    is ConnectionManager.ConnectionState.Desconectado -> {
                        _uiState.value = _uiState.value.copy(
                            isConnected = false,
                            isConnecting = false,
                            statusText = "Desconectado"
                        )
                    }
                    is ConnectionManager.ConnectionState.Desconexion -> {
                        _uiState.value = _uiState.value.copy(
                            isConnected = false,
                            isConnecting = false,
                            statusText = "Desconectado: ${state.motivo}"
                        )
                        // Notificar caída forzada sin crashear la UI, dejando disponible el botón para reconectar
                        emitSnackbar("Conexión perdida con el Nodo: ${state.motivo}")
                    }
                }
            }
        }
    }

    /**
     * Procesa la telemetría periódica recibida del Nodo Sensor.
     */
    private fun processDataMessage(data: Protocol.DataMessage) {
        lastDataReceivedTime = System.currentTimeMillis()
        val segundosRestantes = (data.proxAlimMs / 1000L).toInt().coerceAtLeast(0)

        _uiState.value = _uiState.value.copy(
            isDatosDesactualizados = false,
            segundosDesdeUltimaActualizacion = 0,
            telemetria = TelemetryState(
                temperatura = data.temp,
                turbidez = data.turb,
                aireadorEncendido = data.aireador,
                modoAireador = if (data.manual) "MANUAL" else "AUTO",
                alimentadorActivo = data.alimentador,
                alimentadorPospuesto = data.pospuesto,
                proxAlimentacionSegundos = segundosRestantes,
                manual = data.manual,
                timestamp = data.ts
            )
        )
    }

    /**
     * Bucle de 1 segundo:
     * - Decrementa el contador regresivo de alimentación en tiempo real.
     * - Calcula "Última actualización hace X s" y marca datos desactualizados si pasan > 6 segundos.
     */
    private fun startPeriodicCountdownAndWatchdog() {
        countdownJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(1000L)
                val current = _uiState.value
                val now = System.currentTimeMillis()

                // Cálculo de tiempo transcurrido desde la última telemetría
                val segundosElapsed = if (lastDataReceivedTime > 0L) {
                    ((now - lastDataReceivedTime) / 1000L).toInt()
                } else {
                    0
                }

                val desactualizados = (segundosElapsed > 6 && current.isConnected)

                // Decremento suave del contador local mientras no esté alimentando ni pospuesto
                val proxSegs = current.telemetria.proxAlimentacionSegundos
                val nuevoCountdown = if (proxSegs > 0 && !current.telemetria.alimentadorActivo && !current.telemetria.alimentadorPospuesto) {
                    proxSegs - 1
                } else {
                    proxSegs
                }

                _uiState.value = current.copy(
                    segundosDesdeUltimaActualizacion = segundosElapsed,
                    isDatosDesactualizados = desactualizados,
                    telemetria = current.telemetria.copy(
                        proxAlimentacionSegundos = nuevoCountdown
                    )
                )
            }
        }
    }

    private fun emitSnackbar(msg: String) {
        viewModelScope.launch {
            _snackbarEvent.emit(msg)
        }
    }

    override fun onCleared() {
        super.onCleared()
        countdownJob?.cancel()
        connectionJob?.cancel()
        connectionManager.disconnect()
    }
}
