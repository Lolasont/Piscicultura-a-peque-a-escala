package cl.inacap.piscicultura.bluetooth

import android.bluetooth.BluetoothSocket
import cl.inacap.piscicultura.domain.Protocol
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Gestor integral de la conexión activa RFCOMM.
 * - Lectura en bucle con BufferedReader y validación de 2 KB máximo por línea.
 * - Escritura atómica concurrente protegida con Mutex.
 * - Heartbeat bidireccional (PING cada 5s, timeout por inactividad tras 10s).
 * - Cierre ordenado y seguro de socket y flujos de datos en bloque finally.
 */
class ConnectionManager(
    private val scope: CoroutineScope
) {

    sealed class ConnectionState {
        object Desconectado : ConnectionState()
        data class Conectado(val remoteDeviceName: String) : ConnectionState()
        data class Desconexion(val motivo: String) : ConnectionState()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Desconectado)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Flujo de líneas JSON válidas recibidas
    private val _incomingMessages = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<String> = _incomingMessages.asSharedFlow()

    private var activeSocket: BluetoothSocket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    private val writeMutex = Mutex()
    private var readerJob: Job? = null
    private var heartbeatJob: Job? = null

    @Volatile private var lastMessageTimestamp: Long = 0L

    /**
     * Inicia la gestión de una conexión BluetoothSocket activa.
     */
    fun start(socket: BluetoothSocket, role: String, user: String) {
        disconnect() // Limpiar conexión previa si existiese

        activeSocket = socket
        val deviceName = try {
            socket.remoteDevice.name ?: socket.remoteDevice.address
        } catch (_: SecurityException) {
            socket.remoteDevice.address
        }

        try {
            reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
            writer = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))
        } catch (e: IOException) {
            handleDisconnection("Error al inicializar flujos I/O: ${e.message}")
            return
        }

        lastMessageTimestamp = System.currentTimeMillis()
        _connectionState.value = ConnectionState.Conectado(deviceName)

        // Enviar saludo inicial HELLO
        sendMessage(Protocol.HelloMessage(role = role, user = user))

        // Iniciar lecturas y watchdog de heartbeat
        startReaderJob()
        startHeartbeatJob()
    }

    /**
     * Envía cualquier mensaje del protocolo serializado a JSON de una línea terminada en '\n'.
     */
    fun sendMessage(payload: Any): Boolean {
        val jsonLine = Protocol.toJson(payload)
        return sendRawLine(jsonLine)
    }

    /**
     * Escritura thread-safe protegida por Mutex en Dispatchers.IO.
     */
    fun sendRawLine(line: String): Boolean {
        val currentWriter = writer ?: return false
        val formatted = if (line.endsWith("\n")) line else "$line\n"

        scope.launch(Dispatchers.IO) {
            writeMutex.withLock {
                try {
                    currentWriter.write(formatted)
                    currentWriter.flush()
                } catch (e: IOException) {
                    handleDisconnection("Error de escritura en el socket: ${e.message}")
                }
            }
        }
        return true
    }

    /**
     * Bucle de lectura de tramas entrantes en Dispatchers.IO.
     */
    private fun startReaderJob() {
        readerJob = scope.launch(Dispatchers.IO) {
            val localReader = reader ?: return@launch
            try {
                while (isActive) {
                    val line = localReader.readLine() ?: break // null significa socket cerrado
                    lastMessageTimestamp = System.currentTimeMillis()

                    // Validar límite máximo de línea (2 KB)
                    if (line.toByteArray(Charsets.UTF_8).size > Protocol.MAX_PAYLOAD_SIZE) {
                        continue // Descartar línea maliciosa o corrupta
                    }

                    // Parseo preliminar seguro
                    val parsed = Protocol.parseSafe(line)
                    if (parsed != null) {
                        val (type, _) = parsed
                        when (type) {
                            Protocol.TYPE_PING -> {
                                // Responder inmediatamente al PING con un PONG
                                sendMessage(Protocol.HeartbeatMessage(Protocol.TYPE_PONG))
                            }
                            Protocol.TYPE_PONG -> {
                                // Latido confirmado, se actualiza timestamp
                            }
                            else -> {
                                // Emitir mensaje de datos, comando, saludo o ack
                                _incomingMessages.emit(line)
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                // Caída de socket o desconexión física forzada
            } finally {
                handleDisconnection("El dispositivo remoto cerró la conexión")
            }
        }
    }

    /**
     * Heartbeat bidireccional:
     * - Envía PING cada 5 segundos.
     * - Si transcurren más de 10 segundos sin recibir ninguna trama, asume desconexión.
     */
    private fun startHeartbeatJob() {
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(BluetoothConstants.HEARTBEAT_INTERVAL_MS)

                val elapsedSinceLastMsg = System.currentTimeMillis() - lastMessageTimestamp
                if (elapsedSinceLastMsg > BluetoothConstants.HEARTBEAT_TIMEOUT_MS) {
                    handleDisconnection("Tiempo de espera agotado (10s sin respuesta del remoto)")
                    break
                }

                // Enviar pulso PING
                sendMessage(Protocol.HeartbeatMessage(Protocol.TYPE_PING))
            }
        }
    }

    /**
     * Cierra sockets y flujos liberando recursos de forma segura.
     */
    fun disconnect() {
        readerJob?.cancel()
        readerJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null

        try { reader?.close() } catch (_: Exception) {}
        try { writer?.close() } catch (_: Exception) {}
        try { activeSocket?.close() } catch (_: Exception) {}

        reader = null
        writer = null
        activeSocket = null
        _connectionState.value = ConnectionState.Desconectado
    }

    private fun handleDisconnection(reason: String) {
        readerJob?.cancel()
        heartbeatJob?.cancel()

        try { activeSocket?.close() } catch (_: Exception) {}
        activeSocket = null

        _connectionState.value = ConnectionState.Desconexion(reason)
    }
}
