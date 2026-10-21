package cl.inacap.piscicultura.bluetooth

import java.util.UUID

/**
 * Constantes de red y configuración para Bluetooth clásico RFCOMM
 */
object BluetoothConstants {
    // UUID estándar para Serial Port Profile (SPP) RFCOMM
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    const val SERVICE_NAME = "PisciculturaNodoESP32"
    const val LINE_DELIMITER = "\n"

    // Seguridad y control de flujo
    const val MAX_LINE_BYTES = 2048 // 2 KB máximo por línea JSON para prevenir desbordes
    const val HEARTBEAT_INTERVAL_MS = 5000L // PING cada 5 segundos
    const val HEARTBEAT_TIMEOUT_MS = 10000L // Caída si no hay respuesta en 10 segundos
    const val CONNECT_TIMEOUT_MS = 10000L // Timeout de conexión inicial
}
