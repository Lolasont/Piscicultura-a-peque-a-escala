package cl.inacap.piscicultura.bluetooth

import java.util.UUID

/**
 * Constantes para comunicación Bluetooth Clásico RFCOMM
 */
object BluetoothConstants {
    // UUID estándar para Serial Port Profile (SPP) RFCOMM
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    const val SERVICE_NAME = "PisciculturaNodoESP32"
    const val LINE_DELIMITER = "\n"

    // Códigos de petición de permisos
    const val REQUEST_BLUETOOTH_PERMISSIONS = 1001
}
