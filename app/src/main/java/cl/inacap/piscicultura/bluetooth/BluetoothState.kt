package cl.inacap.piscicultura.bluetooth

/**
 * Estados de la máquina de estados de conexión Bluetooth
 */
sealed class BluetoothState {
    object Idle : BluetoothState()
    data class Listening(val serviceName: String) : BluetoothState()
    data class Connecting(val deviceName: String, val address: String) : BluetoothState()
    data class Connected(val deviceName: String, val address: String) : BluetoothState()
    data class Disconnected(val reason: String = "Desconectado") : BluetoothState()
    data class Error(val message: String) : BluetoothState()
}
