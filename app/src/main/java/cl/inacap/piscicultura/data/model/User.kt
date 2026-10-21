package cl.inacap.piscicultura.data.model

/**
 * Entidad de Usuario para autenticación local cifrada
 */
data class User(
    val username: String,
    val passwordHash: String,
    val fullName: String = "",
    val rol: String = "OPERADOR"
)
