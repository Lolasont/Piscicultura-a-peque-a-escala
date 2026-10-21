package cl.inacap.piscicultura.security

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Patterns
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth

/**
 * Utilidades de validación y seguridad para autenticación con Firebase.
 */
object AuthHelper {

    /**
     * Valida que el correo tenga formato estándar.
     */
    fun isValidEmail(email: String): Boolean {
        val clean = email.trim()
        return clean.isNotEmpty() && Patterns.EMAIL_ADDRESS.matcher(clean).matches()
    }

    /**
     * Valida los requisitos de contraseña: mínimo 8 caracteres, al menos una letra y al menos un número.
     */
    fun isValidPassword(password: String): Boolean {
        if (password.length < 8) return false
        val hasLetter = password.any { it.isLetter() }
        val hasDigit = password.any { it.isDigit() }
        return hasLetter && hasDigit
    }

    /**
     * Verifica la disponibilidad de conexión a Internet antes de realizar peticiones de red.
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Obtiene la instancia de FirebaseAuth si el SDK está inicializado con google-services.json.
     */
    fun getFirebaseAuthOrNull(context: Context): FirebaseAuth? {
        return try {
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                FirebaseAuth.getInstance()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
