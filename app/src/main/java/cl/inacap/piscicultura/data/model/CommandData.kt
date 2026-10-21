package cl.inacap.piscicultura.data.model

import com.google.gson.annotations.SerializedName

/**
 * Comandos de control enviados desde la Estación al Nodo Sensor
 */
data class CommandData(
    @SerializedName("cmd")
    val comando: String, // "FEED_NOW", "AERATOR_AUTO", "AERATOR_ON", "AERATOR_OFF"

    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        const val CMD_FEED_NOW = "FEED_NOW"
        const val CMD_AERATOR_AUTO = "AERATOR_AUTO"
        const val CMD_AERATOR_ON = "AERATOR_ON"
        const val CMD_AERATOR_OFF = "AERATOR_OFF"
    }
}
