package cl.inacap.piscicultura.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Gestor de persistencia de sesión cifrada mediante EncryptedSharedPreferences (MasterKey AES256_GCM).
 * Requisitos de seguridad OT/IoT:
 * 1. CERO contraseñas en texto plano ni cifradas (nunca se almacena la contraseña).
 * 2. Guarda el email del operador autenticado y la marca temporal de inicio de sesión.
 * 3. Validez de sesión estricta de 24 horas.
 * 4. Limpieza total de sesión y claves al cerrar sesión.
 */
class EncryptedSessionManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_FILENAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /**
     * Guarda la sesión autenticada. NUNCA guarda contraseñas.
     */
    fun saveSession(email: String) {
        val cleanEmail = email.trim().lowercase()
        securePrefs.edit()
            .putString(KEY_USER_EMAIL, cleanEmail)
            .putLong(KEY_SESSION_TIMESTAMP, System.currentTimeMillis())
            .putBoolean(KEY_HAS_ACTIVE_SESSION, true)
            .apply()
    }

    /**
     * Valida si existe una sesión activa y si no han transcurrido más de 24 horas (86.400.000 ms).
     */
    fun isSessionValid(): Boolean {
        val hasActive = securePrefs.getBoolean(KEY_HAS_ACTIVE_SESSION, false)
        val email = securePrefs.getString(KEY_USER_EMAIL, null)
        val timestamp = securePrefs.getLong(KEY_SESSION_TIMESTAMP, 0L)

        if (!hasActive || email.isNullOrEmpty() || timestamp <= 0L) {
            return false
        }

        val elapsed = System.currentTimeMillis() - timestamp
        val isWithin24Hours = elapsed in 0 until SESSION_EXPIRATION_MS

        if (!isWithin24Hours) {
            // Sesión expirada por política de seguridad de 24h
            clearSession()
            return false
        }

        return true
    }

    fun getSessionEmail(): String {
        return securePrefs.getString(KEY_USER_EMAIL, "") ?: ""
    }

    fun getSessionTimestamp(): Long {
        return securePrefs.getLong(KEY_SESSION_TIMESTAMP, 0L)
    }

    /**
     * Limpia completamente el almacenamiento cifrado de sesión.
     */
    fun clearSession() {
        securePrefs.edit()
            .remove(KEY_USER_EMAIL)
            .remove(KEY_SESSION_TIMESTAMP)
            .remove(KEY_HAS_ACTIVE_SESSION)
            .apply()
    }

    companion object {
        private const val PREFS_FILENAME = "piscicultura_secure_session_v2"
        private const val KEY_USER_EMAIL = "encrypted_session_email"
        private const val KEY_SESSION_TIMESTAMP = "encrypted_session_timestamp"
        private const val KEY_HAS_ACTIVE_SESSION = "encrypted_has_active_session"

        // 24 horas en milisegundos = 24 * 60 * 60 * 1000
        const val SESSION_EXPIRATION_MS = 24L * 60L * 60L * 1000L
    }
}
