package cl.inacap.piscicultura.data.model

import com.google.gson.annotations.SerializedName

/**
 * Modelo de telemetría del estanque de piscicultura (salmonicultura).
 * Se envía periódicamente (cada 2 segundos) desde el Nodo Sensor hacia la Estación vía Bluetooth.
 */
data class PisciculturaData(
    @SerializedName("temp")
    val temperatura: Float = 20.0f,

    @SerializedName("turb")
    val turbidez: Float = 15.0f,

    @SerializedName("aerador")
    val aireadorEncendido: Boolean = false,

    @SerializedName("modo_aerador")
    val modoAireador: String = "AUTO", // "AUTO", "MANUAL_ON", "MANUAL_OFF"

    @SerializedName("alimentador")
    val alimentadorActivo: Boolean = false,

    @SerializedName("seg_alimentacion")
    val segundosParaAlimentar: Int = 15,

    @SerializedName("alimentador_pospuesto")
    val alimentadorPospuesto: Boolean = false,

    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @SerializedName("mensaje")
    val mensajeAlerta: String = "Condiciones normales"
)
