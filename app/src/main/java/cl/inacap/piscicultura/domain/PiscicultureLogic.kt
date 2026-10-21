package cl.inacap.piscicultura.domain

/**
 * Lógica pura de control del estanque de piscicultura (salmonicultura).
 * Clase independiente de Android, 100% testeable en JVM.
 *
 * Reglas de negocio:
 * 1. Aireador: ON si temp < 18 °C o temp > 26 °C, o turbidez > 60 %, salvo modo manual (ON/OFF).
 * 2. Alimentador: cada 15.000 ms intenta alimentar.
 *    - Si aireador está APAGADO: alimenta durante 3.000 ms y reinicia el temporizador de 15s.
 *    - Si aireador está ENCENDIDO: marca "pospuesto" sin reiniciar el temporizador y reintenta en cada ciclo de 2s.
 */
class PiscicultureLogic {

    companion object {
        const val TEMP_MIN = 18.0f
        const val TEMP_MAX = 26.0f
        const val TURBIDEZ_MAX = 60.0f

        const val INTERVALO_ALIMENTACION = 15000L // 15 segundos entre alimentaciones
        const val DURACION_INDICADOR = 3000L       // 3 segundos activo dispensando
        const val CICLO = 2000L                    // 2 segundos de periodo de evaluación
    }

    data class State(
        val temperatura: Float = 21.0f,
        val turbidez: Float = 20.0f,
        val aireadorEncendido: Boolean = false,
        val modoAireador: String = "AUTO", // "AUTO", "ON", "OFF"
        val alimentadorActivo: Boolean = false,
        val alimentadorPospuesto: Boolean = false,
        val tiempoUltimaAlimentacion: Long = 0L,
        val finDuracionAlimentacion: Long = 0L,
        val proxAlimentacionMs: Long = INTERVALO_ALIMENTACION,
        val mensajeEstado: String = "Parámetros óptimos para salmónidos"
    )

    private var currentState = State(
        tiempoUltimaAlimentacion = System.currentTimeMillis()
    )

    fun getCurrentState(): State = currentState

    /**
     * Evalúa el ciclo temporal del estanque con el timestamp actual.
     */
    fun evaluarCiclo(
        now: Long,
        temp: Float,
        turb: Float,
        modoAireadorManual: String? = null // null: AUTO, "ON", "OFF"
    ): State {
        val clampedTemp = temp.coerceIn(0.0f, 40.0f)
        val clampedTurb = turb.coerceIn(0.0f, 100.0f)
        val modo = modoAireadorManual ?: currentState.modoAireador

        // 1. Evaluación del Aireador
        val autoRequiereAireador = (clampedTemp < TEMP_MIN || clampedTemp > TEMP_MAX || clampedTurb > TURBIDEZ_MAX)
        val aireadorOn = when (modo) {
            "ON" -> true
            "OFF" -> false
            else -> autoRequiereAireador // "AUTO"
        }

        // 2. Evaluación de duración activa del alimentador (dispensando durante 3s)
        var alimentadorActivo = currentState.alimentadorActivo
        if (alimentadorActivo && now >= currentState.finDuracionAlimentacion) {
            alimentadorActivo = false
        }

        // 3. Temporizador del alimentador
        var tiempoUltima = currentState.tiempoUltimaAlimentacion
        var finDuracion = currentState.finDuracionAlimentacion
        var pospuesto = currentState.alimentadorPospuesto

        val tiempoTranscurrido = now - tiempoUltima
        val tiempoRestante = (INTERVALO_ALIMENTACION - tiempoTranscurrido).coerceAtLeast(0L)

        if (tiempoTranscurrido >= INTERVALO_ALIMENTACION) {
            // Cumplido el intervalo de 15 segundos: verificar condición del aireador
            if (!aireadorOn) {
                // Aireador APAGADO -> Alimentación exitosa
                alimentadorActivo = true
                pospuesto = false
                finDuracion = now + DURACION_INDICADOR
                tiempoUltima = now // Reinicia temporizador de 15 segundos
            } else {
                // Aireador ENCENDIDO -> Pospone y reintenta en cada ciclo de 2s sin reiniciar el temporizador
                alimentadorActivo = false
                pospuesto = true
                // tiempoUltima NO se reinicia para que en el próximo ciclo (now + 2s) siga cumplido el intervalo
            }
        }

        // 4. Diagnóstico de condiciones
        val mensaje = when {
            alimentadorActivo -> "Dispensando alimento al cardumen de salmónidos..."
            pospuesto && aireadorOn -> "En espera (aireador activo): alimentación pospuesta"
            clampedTemp < TEMP_MIN -> "Alerta: Temperatura baja (<18°C) -> Aireador encendido"
            clampedTemp > TEMP_MAX -> "Alerta crítica: Temperatura alta (>26°C) -> Aireador encendido"
            clampedTurb > TURBIDEZ_MAX -> "Alerta: Agua turbia (>60%) -> Aireador encendido"
            else -> "Parámetros óptimos para salmónidos (18-26°C, turbidez <60%)"
        }

        val proxMs = if (pospuesto) 0L else tiempoRestante

        currentState = State(
            temperatura = clampedTemp,
            turbidez = clampedTurb,
            aireadorEncendido = aireadorOn,
            modoAireador = modo,
            alimentadorActivo = alimentadorActivo,
            alimentadorPospuesto = pospuesto,
            tiempoUltimaAlimentacion = tiempoUltima,
            finDuracionAlimentacion = finDuracion,
            proxAlimentacionMs = proxMs,
            mensajeEstado = mensaje
        )

        return currentState
    }

    /**
     * Forzado manual de alimentación por comando OT desde la Estación.
     * Solo permite alimentar si el aireador está apagado.
     * @return true si se activó, false si fue pospuesto por estar el aireador encendido.
     */
    fun forzarAlimentacion(now: Long): Boolean {
        return if (!currentState.aireadorEncendido) {
            currentState = currentState.copy(
                alimentadorActivo = true,
                alimentadorPospuesto = false,
                finDuracionAlimentacion = now + DURACION_INDICADOR,
                tiempoUltimaAlimentacion = now,
                proxAlimentacionMs = INTERVALO_ALIMENTACION,
                mensajeEstado = "Alimentación forzada manual ejecutada"
            )
            true
        } else {
            currentState = currentState.copy(
                alimentadorActivo = false,
                alimentadorPospuesto = true,
                mensajeEstado = "Alimentación forzada denegada: aireador activo"
            )
            false
        }
    }

    /**
     * Permite fijar el modo manual del aireador ("AUTO", "ON", "OFF").
     */
    fun setModoAireador(modo: String, now: Long): State {
        return evaluarCiclo(now, currentState.temperatura, currentState.turbidez, modo)
    }
}
