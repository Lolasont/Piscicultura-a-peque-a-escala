package cl.inacap.piscicultura.data.model

/**
 * Perfil de Operador autenticado mediante Firebase Authentication.
 * CERO contraseñas ni hashes almacenados localmente.
 */
data class User(
    val email: String,
    val uid: String = "",
    val rol: String = "OPERADOR"
)
