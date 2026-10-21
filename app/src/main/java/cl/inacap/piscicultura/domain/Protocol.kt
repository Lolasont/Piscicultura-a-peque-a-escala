package cl.inacap.piscicultura.domain

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName

/**
 * Protocolo de comunicación IoT/OT para salmonicultura sobre Bluetooth RFCOMM.
 * Formato: Mensajes JSON de una sola línea terminados en '\n'.
 * Límite estricto de tamaño: 2048 bytes (2 KB).
 */
object Protocol {

    private val gson = Gson()
    const val MAX_PAYLOAD_SIZE = 2048

    // Tipos de mensajes
    const val TYPE_DATA = "DATA"
    const val TYPE_CMD = "CMD"
    const val TYPE_HELLO = "HELLO"
    const val TYPE_ACK = "ACK"
    const val TYPE_PING = "PING"
    const val TYPE_PONG = "PONG"

    // Comandos soportados
    const val CMD_FEED = "FEED"
    const val CMD_AIREADOR = "AIREADOR"

    // Valores para comando AIREADOR
    const val VAL_ON = "ON"
    const val VAL_OFF = "OFF"
    const val VAL_AUTO = "AUTO"

    // Roles
    const val ROLE_NODO = "NODO"
    const val ROLE_ESTACION = "ESTACION"

    /**
     * 1) Mensaje de Telemetría periódica del Nodo hacia la Estación
     */
    data class DataMessage(
        @SerializedName("type") val type: String = TYPE_DATA,
        @SerializedName("ts") val ts: Long = System.currentTimeMillis(),
        @SerializedName("temp") val temp: Float,
        @SerializedName("turb") val turb: Float,
        @SerializedName("aireador") val aireador: Boolean,
        @SerializedName("alimentador") val alimentador: Boolean,
        @SerializedName("proxAlimMs") val proxAlimMs: Long,
        @SerializedName("pospuesto") val pospuesto: Boolean,
        @SerializedName("manual") val manual: Boolean
    )

    /**
     * 2 y 3) Mensaje de Comando desde la Estación hacia el Nodo
     */
    data class CommandMessage(
        @SerializedName("type") val type: String = TYPE_CMD,
        @SerializedName("cmd") val cmd: String,
        @SerializedName("value") val value: String? = null
    )

    /**
     * 4) Mensaje de Saludo y Negociación de Rol al conectar
     */
    data class HelloMessage(
        @SerializedName("type") val type: String = TYPE_HELLO,
        @SerializedName("role") val role: String,
        @SerializedName("user") val user: String
    )

    /**
     * 5) Mensaje de Confirmación de recepción y ejecución de comando
     */
    data class AckMessage(
        @SerializedName("type") val type: String = TYPE_ACK,
        @SerializedName("ref") val ref: String,
        @SerializedName("ok") val ok: Boolean
    )

    /**
     * 6) Mensaje de Heartbeat (Latido)
     */
    data class HeartbeatMessage(
        @SerializedName("type") val type: String // "PING" o "PONG"
    )

    /**
     * Serializa cualquier objeto a una sola línea JSON terminada en '\n'.
     */
    fun toJson(payload: Any): String {
        return gson.toJson(payload) + "\n"
    }

    /**
     * Parsea de forma segura una línea entrante validando longitud y sintaxis JSON sin crashear.
     * Retorna un par (Tipo, JsonObject) o null si la línea es inválida.
     */
    fun parseSafe(line: String): Pair<String, JsonObject>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_SIZE) {
            // Ignorar tramas excesivas para evitar ataques o desbordes de memoria
            return null
        }

        return try {
            val jsonElement = JsonParser.parseString(trimmed)
            if (!jsonElement.isJsonObject) return null
            val obj = jsonElement.asJsonObject
            val type = obj.get("type")?.asString ?: return null
            Pair(type, obj)
        } catch (_: Exception) {
            null // Ignora JSON malformado sin crashear
        }
    }

    fun parseData(obj: JsonObject): DataMessage? = try {
        gson.fromJson(obj, DataMessage::class.java)
    } catch (_: Exception) { null }

    fun parseCommand(obj: JsonObject): CommandMessage? = try {
        gson.fromJson(obj, CommandMessage::class.java)
    } catch (_: Exception) { null }

    fun parseHello(obj: JsonObject): HelloMessage? = try {
        gson.fromJson(obj, HelloMessage::class.java)
    } catch (_: Exception) { null }

    fun parseAck(obj: JsonObject): AckMessage? = try {
        gson.fromJson(obj, AckMessage::class.java)
    } catch (_: Exception) { null }
}
