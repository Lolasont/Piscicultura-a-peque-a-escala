package cl.inacap.piscicultura.domain

import cl.inacap.piscicultura.data.model.PisciculturaData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Controlador de lógica de negocio para el Nodo Sensor (simulador ESP32).
 *
 * Reglas de negocio:
 * 1. Aireador ON si temperatura < 18 °C o > 26 °C, o turbidez > 60 %.
 * 2. Alimentador: cada 15 s intenta alimentar, pero SOLO si el aireador está apagado.
 *    Si el aireador está encendido, pospone y reintenta cada 2 s sin reiniciar el temporizador de ciclo.
 * 3. Permite modo manual o forzado vía comandos.
 */
class NodoController {

    private val _dataState = MutableStateFlow(
        PisciculturaData(
            temperatura = 22.0f,
            turbidez = 25.0f,
            aireadorEncendido = false,
            modoAireador = "AUTO",
            alimentadorActivo = false,
            segundosParaAlimentar = 15,
            alimentadorPospuesto = false,
            mensajeAlerta = "Sistema óptimo para salmónidos"
        )
    )
    val dataState: StateFlow<PisciculturaData> = _dataState.asStateFlow()

    private var feedingDurationRemaining = 0
    private var postponementRetriesRemaining = 0

    fun updateTemperatura(temp: Float) {
        val clamped = temp.coerceIn(0.0f, 40.0f)
        val current = _dataState.value
        val newState = evaluateAeratorAndAlerts(current.copy(temperatura = clamped))
        _dataState.value = newState
    }

    fun updateTurbidez(turb: Float) {
        val clamped = turb.coerceIn(0.0f, 100.0f)
        val current = _dataState.value
        val newState = evaluateAeratorAndAlerts(current.copy(turbidez = clamped))
        _dataState.value = newState
    }

    fun setModoAireador(modo: String) {
        val current = _dataState.value
        val updated = current.copy(modoAireador = modo)
        _dataState.value = evaluateAeratorAndAlerts(updated)
    }

    fun forceFeed() {
        val current = _dataState.value
        if (current.aireadorEncendido) {
            _dataState.value = current.copy(
                alimentadorPospuesto = true,
                mensajeAlerta = "¡Alimentación forzada pospuesta! Aireador encendido por seguridad"
            )
        } else {
            feedingDurationRemaining = 2
            _dataState.value = current.copy(
                alimentadorActivo = true,
                alimentadorPospuesto = false,
                segundosParaAlimentar = 15,
                mensajeAlerta = "Alimentador activado manualmente (2s)"
            )
        }
    }

    /**
     * Se ejecuta en un bucle temporal de 1 segundo
     */
    fun onOneSecondTick() {
        val current = _dataState.value

        // Manejo de duración activa de alimentación (2 segundos dispensando)
        if (feedingDurationRemaining > 0) {
            feedingDurationRemaining--
            if (feedingDurationRemaining == 0) {
                _dataState.value = current.copy(
                    alimentadorActivo = false,
                    segundosParaAlimentar = 15,
                    alimentadorPospuesto = false
                )
            }
            return
        }

        // Si está en espera por posposición (reintento cada 2 segundos)
        if (current.alimentadorPospuesto) {
            if (postponementRetriesRemaining > 1) {
                postponementRetriesRemaining--
                _dataState.value = current.copy(
                    mensajeAlerta = "Alimentación pospuesta. Reintento en ${postponementRetriesRemaining}s..."
                )
            } else {
                // Reintentar ahora
                if (current.aireadorEncendido) {
                    // Sigue encendido el aireador, posponer 2 segundos más
                    postponementRetriesRemaining = 2
                    _dataState.value = current.copy(
                        alimentadorPospuesto = true,
                        mensajeAlerta = "Reintento fallido: Aireador sigue activo. Reintentando en 2s..."
                    )
                } else {
                    // Aireador apagado, alimentar exitosamente
                    feedingDurationRemaining = 2
                    postponementRetriesRemaining = 0
                    _dataState.value = current.copy(
                        alimentadorActivo = true,
                        alimentadorPospuesto = false,
                        segundosParaAlimentar = 15,
                        mensajeAlerta = "Alimentador activado tras posposición"
                    )
                }
            }
            return
        }

        // Cuenta regresiva normal de 15 segundos
        val nextSeconds = current.segundosParaAlimentar - 1
        if (nextSeconds <= 0) {
            // Tiempo de alimentar alcanzado
            if (current.aireadorEncendido) {
                // Aireador está prendido -> Posponer y reintentar en 2s sin reiniciar el temporizador principal
                postponementRetriesRemaining = 2
                _dataState.value = current.copy(
                    segundosParaAlimentar = 0,
                    alimentadorPospuesto = true,
                    alimentadorActivo = false,
                    mensajeAlerta = "Alerta: Aireador en marcha. Alimentación pospuesta (reintento en 2s)"
                )
            } else {
                // Aireador apagado -> Dispensar alimento
                feedingDurationRemaining = 2
                _dataState.value = current.copy(
                    segundosParaAlimentar = 15,
                    alimentadorActivo = true,
                    alimentadorPospuesto = false,
                    mensajeAlerta = "Dispensando alimento a los salmones..."
                )
            }
        } else {
            _dataState.value = current.copy(segundosParaAlimentar = nextSeconds)
        }
    }

    private fun evaluateAeratorAndAlerts(data: PisciculturaData): PisciculturaData {
        val temp = data.temperatura
        val turb = data.turbidez

        val autoNeedAerator = (temp < 18.0f || temp > 26.0f || turb > 60.0f)

        val aeratorOn = when (data.modoAireador) {
            "MANUAL_ON" -> true
            "MANUAL_OFF" -> false
            else -> autoNeedAerator // "AUTO"
        }

        val alertas = mutableListOf<String>()
        if (temp < 18.0f) alertas.add("Temp baja (<18°C)")
        if (temp > 26.0f) alertas.add("Temp crítica (>26°C)")
        if (turb > 60.0f) alertas.add("Agua turbia (>60%)")

        val alertMsg = if (alertas.isNotEmpty()) {
            "Alerta salmónidos: " + alertas.joinToString(", ") + " -> Aireador requerido"
        } else {
            "Parámetros óptimos para salmónidos (18-26°C, turbidez <60%)"
        }

        return data.copy(
            aireadorEncendido = aeratorOn,
            mensajeAlerta = alertMsg,
            timestamp = System.currentTimeMillis()
        )
    }
}
