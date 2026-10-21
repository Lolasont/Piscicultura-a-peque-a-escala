package cl.inacap.piscicultura.ui

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cl.inacap.piscicultura.bluetooth.BluetoothServer
import cl.inacap.piscicultura.bluetooth.ConnectionManager
import cl.inacap.piscicultura.domain.PiscicultureLogic
import cl.inacap.piscicultura.domain.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ViewModel para el rol NODO SENSOR.
 * Mantiene la conexión Bluetooth RFCOMM y el estado de la simulación vivos ante rotaciones de pantalla:
 * - Sliders de temperatura (0 a 40 °C) y turbidez (0 a 100 %).
 * - Ciclo de evaluación periódico de 2 segundos en Dispatchers.Default.
 * - Transmisión de telemetría DATA hacia la Estación.
 * - Procesamiento de comandos CMD (FEED, AIREADOR) y emisión de ACK.
 * - Registro de eventos en vivo.
 */
class NodoViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val temperatura: Float = 21.0f,
        val turbidez: Float = 20.0f,
        val aireadorEncendido: Boolean = false,
        val modoAireador: String = "AUTO",
        val alimentadorActivo: Boolean = false,
        val alimentadorPospuesto: Boolean = false,
        val proxAlimentacionMs: Long = PiscicultureLogic.INTERVALO_ALIMENTACION,
        val mensajeEstado: String = "Sistema listo",
        val serverStatusText: String = "Servidor detenido",
        val isServerListening: Boolean = false,
        val isEstacionConectada: Boolean = false,
        val nombreEstacionConectada: String = "",
        val eventLogs: List<String> = emptyList()
    )

    private val logic = PiscicultureLogic()
    private val bluetoothServer = BluetoothServer(application)
    private val connectionManager = ConnectionManager(viewModelScope)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var simulationJob: Job? = null
    private var serverJob: Job? = null

    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    init {
        // Iniciar el bucle de simulación del estanque (cada 2 segundos)
        startSimulationLoop()
        // Escuchar mensajes entrantes desde la conexión Bluetooth
        observeIncomingMessages()
        // Escuchar cambios de estado en la conexión activa
        observeConnectionState()
        // Escuchar estados del servidor
        observeServerState()

        addLog("Nodo Sensor inicializado en modo ESP32")
    }

    fun setTemperatura(value: Float) {
        val clamped = value.coerceIn(0.0f, 40.0f)
        _uiState.value = _uiState.value.copy(temperatura = clamped)
        triggerImmediateEvaluation()
    }

    fun setTurbidez(value: Float) {
        val clamped = value.coerceIn(0.0f, 100.0f)
        _uiState.value = _uiState.value.copy(turbidez = clamped)
        triggerImmediateEvaluation()
    }

    fun setModoAireador(modo: String) {
        val now = System.currentTimeMillis()
        val newState = logic.setModoAireador(modo, now)
        applyLogicState(newState)
        addLog("Modo de aireador cambiado a: $modo")
    }

    fun forzarAlimentacionManual() {
        val now = System.currentTimeMillis()
        val exito = logic.forzarAlimentacion(now)
        val estadoActualizado = logic.getCurrentState()
        applyLogicState(estadoActualizado)

        if (exito) {
            addLog("Alimentación forzada activada (3s)")
        } else {
            addLog("Alimentación forzada pospuesta: aireador activo")
        }
    }

    /**
     * Inicia el BluetoothServerSocket para aceptar la conexión de la Estación.
     */
    fun iniciarServidor(adapter: BluetoothAdapter?) {
        if (serverJob?.isActive == true) return

        serverJob = viewModelScope.launch(Dispatchers.IO) {
            bluetoothServer.startListening(adapter) { clientSocket ->
                // Socket aceptado: transferir a ConnectionManager
                connectionManager.start(
                    socket = clientSocket,
                    role = Protocol.ROLE_NODO,
                    user = "Nodo-ESP32"
                )
            }
        }
    }

    /**
     * Detiene el servidor y cierra la conexión activa.
     */
    fun detenerServidor() {
        serverJob?.cancel()
        serverJob = null
        bluetoothServer.stopListening()
        connectionManager.disconnect()
        addLog("Servidor Bluetooth detenido")
    }

    /**
     * Bucle periódico de 2 segundos en Dispatchers.Default que evalúa la lógica
     * y transmite el mensaje DATA a la Estación si está conectada.
     */
    private fun startSimulationLoop() {
        simulationJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(PiscicultureLogic.CICLO)

                val now = System.currentTimeMillis()
                val currentUi = _uiState.value

                // Evaluar ciclo con los valores de sliders actuales
                val state = logic.evaluarCiclo(
                    now = now,
                    temp = currentUi.temperatura,
                    turb = currentUi.turbidez,
                    modoAireadorManual = if (currentUi.modoAireador == "AUTO") null else currentUi.modoAireador
                )

                applyLogicState(state)

                // Transmitir telemetría DATA por RFCOMM si hay Estación vinculada
                if (_uiState.value.isEstacionConectada) {
                    val dataMsg = Protocol.DataMessage(
                        temp = state.temperatura,
                        turb = state.turbidez,
                        aireador = state.aireadorEncendido,
                        alimentador = state.alimentadorActivo,
                        proxAlimMs = state.proxAlimentacionMs,
                        pospuesto = state.alimentadorPospuesto,
                        manual = state.modoAireador != "AUTO"
                    )
                    connectionManager.sendMessage(dataMsg)
                }
            }
        }
    }

    private fun triggerImmediateEvaluation() {
        val now = System.currentTimeMillis()
        val currentUi = _uiState.value
        val state = logic.evaluarCiclo(
            now = now,
            temp = currentUi.temperatura,
            turb = currentUi.turbidez,
            modoAireadorManual = if (currentUi.modoAireador == "AUTO") null else currentUi.modoAireador
        )
        applyLogicState(state)
    }

    private fun applyLogicState(state: PiscicultureLogic.State) {
        _uiState.value = _uiState.value.copy(
            temperatura = state.temperatura,
            turbidez = state.turbidez,
            aireadorEncendido = state.aireadorEncendido,
            modoAireador = state.modoAireador,
            alimentadorActivo = state.alimentadorActivo,
            alimentadorPospuesto = state.alimentadorPospuesto,
            proxAlimentacionMs = state.proxAlimentacionMs,
            mensajeEstado = state.mensajeEstado
        )
    }

    /**
     * Escucha y atiende comandos entrantes desde la Estación.
     */
    private fun observeIncomingMessages() {
        viewModelScope.launch {
            connectionManager.incomingMessages.collect { rawLine ->
                val parsed = Protocol.parseSafe(rawLine) ?: return@collect
                val (type, obj) = parsed

                when (type) {
                    Protocol.TYPE_CMD -> {
                        val cmdMsg = Protocol.parseCommand(obj) ?: return@collect
                        when (cmdMsg.cmd) {
                            Protocol.CMD_FEED -> {
                                val now = System.currentTimeMillis()
                                val ok = logic.forzarAlimentacion(now)
                                applyLogicState(logic.getCurrentState())
                                connectionManager.sendMessage(Protocol.AckMessage(ref = Protocol.CMD_FEED, ok = ok))
                                addLog("CMD recibido: FEED -> ${if (ok) "Ejecutado" else "Pospuesto"}")
                            }
                            Protocol.CMD_AIREADOR -> {
                                val valor = cmdMsg.value ?: Protocol.VAL_AUTO
                                setModoAireador(valor)
                                connectionManager.sendMessage(Protocol.AckMessage(ref = Protocol.CMD_AIREADOR, ok = true))
                                addLog("CMD recibido: AIREADOR $valor")
                            }
                        }
                    }
                    Protocol.TYPE_HELLO -> {
                        val hello = Protocol.parseHello(obj)
                        addLog("Dispositivo remoto identificado: ${hello?.role} (${hello?.user})")
                    }
                }
            }
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            connectionManager.connectionState.collect { state ->
                when (state) {
                    is ConnectionManager.ConnectionState.Conectado -> {
                        _uiState.value = _uiState.value.copy(
                            isEstacionConectada = true,
                            nombreEstacionConectada = state.remoteDeviceName,
                            serverStatusText = "Conectado a ${state.remoteDeviceName}"
                        )
                        addLog("Conexión RFCOMM activa con ${state.remoteDeviceName}")
                    }
                    is ConnectionManager.ConnectionState.Desconectado -> {
                        _uiState.value = _uiState.value.copy(
                            isEstacionConectada = false,
                            nombreEstacionConectada = "",
                            serverStatusText = if (_uiState.value.isServerListening) "Esperando estación..." else "Servidor inactivo"
                        )
                    }
                    is ConnectionManager.ConnectionState.Desconexion -> {
                        _uiState.value = _uiState.value.copy(
                            isEstacionConectada = false,
                            nombreEstacionConectada = "",
                            serverStatusText = "Desconectado: ${state.motivo}"
                        )
                        addLog("Desconexión detectada: ${state.motivo}")
                    }
                }
            }
        }
    }

    private fun observeServerState() {
        viewModelScope.launch {
            bluetoothServer.serverState.collect { serverState ->
                when (serverState) {
                    is BluetoothServer.State.Esperando -> {
                        _uiState.value = _uiState.value.copy(
                            isServerListening = true,
                            serverStatusText = "Esperando estación (RFCOMM)..."
                        )
                        addLog("Servidor RFCOMM en espera de conexión en UUID SPP")
                    }
                    is BluetoothServer.State.Conectado -> {
                        _uiState.value = _uiState.value.copy(
                            isServerListening = false
                        )
                    }
                    is BluetoothServer.State.Desconectado -> {
                        _uiState.value = _uiState.value.copy(
                            isServerListening = false
                        )
                    }
                    is BluetoothServer.State.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isServerListening = false,
                            serverStatusText = "Error: ${serverState.mensaje}"
                        )
                        addLog("Error en servidor: ${serverState.mensaje}")
                    }
                }
            }
        }
    }

    private fun addLog(message: String) {
        val timestamp = dateFormat.format(Date())
        val entry = "[$timestamp] $message"
        val updated = (_uiState.value.eventLogs + entry).takeLast(12)
        _uiState.value = _uiState.value.copy(eventLogs = updated)
    }

    override fun onCleared() {
        super.onCleared()
        simulationJob?.cancel()
        serverJob?.cancel()
        bluetoothServer.stopListening()
        connectionManager.disconnect()
    }
}
