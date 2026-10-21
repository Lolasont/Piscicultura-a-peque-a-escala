package cl.inacap.piscicultura.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import cl.inacap.piscicultura.security.AuthHelper
import cl.inacap.piscicultura.security.EncryptedSessionManager
import com.example.databinding.ActivityLoginBinding
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Pantalla de inicio de sesión con Firebase Authentication y persistencia cifrada.
 * - Validación de correo y contraseña (mínimo 8 caracteres, al menos una letra y un número).
 * - Llamadas asíncronas con await() en Dispatchers.IO.
 * - Guarda en EncryptedSharedPreferences el email y la marca temporal (validez 24h). NUNCA la contraseña.
 * - Manejo seguro de errores sin revelar si falló el correo o la clave.
 * - Retención de estado de formulario ante rotación.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var sessionManager: EncryptedSessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = EncryptedSessionManager(this)

        // Verificación de sesión previa activa y válida (< 24 horas)
        checkActiveSession()

        // Restaurar estado del formulario tras rotación de pantalla
        if (savedInstanceState != null) {
            val savedEmail = savedInstanceState.getString(KEY_SAVED_EMAIL, "")
            binding.etEmail.setText(savedEmail)
        }

        setupListeners()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Guardar únicamente el correo para no perder datos al rotar la pantalla
        // NUNCA persistir la contraseña en bundles ni logs
        val email = binding.etEmail.text?.toString().orEmpty()
        outState.putString(KEY_SAVED_EMAIL, email)
    }

    private fun checkActiveSession() {
        if (sessionManager.isSessionValid()) {
            val firebaseAuth = AuthHelper.getFirebaseAuthOrNull(this)
            val firebaseUser = firebaseAuth?.currentUser
            if (firebaseUser != null || firebaseAuth == null) {
                // Sesión válida dentro del marco de 24h
                navigateToRoleSelect()
            }
        }
    }

    private fun setupListeners() {
        binding.btnLogin.setOnClickListener {
            attemptLogin()
        }

        binding.btnGoRegister.setOnClickListener {
            val intent = Intent(this, RegisterActivity::class.java)
            startActivity(intent)
        }
    }

    private fun attemptLogin() {
        val email = binding.etEmail.text?.toString()?.trim().orEmpty()
        val password = binding.etPassword.text?.toString().orEmpty()

        // 1. Validación de Formato de Correo
        if (!AuthHelper.isValidEmail(email)) {
            binding.tilEmail.error = "Ingrese un correo electrónico válido"
            return
        } else {
            binding.tilEmail.error = null
        }

        // 2. Validación de Contraseña (mín. 8 caracteres, al menos una letra y un número)
        if (!AuthHelper.isValidPassword(password)) {
            binding.tilPassword.error = "La contraseña debe tener mín. 8 caracteres, al menos una letra y un número"
            return
        } else {
            binding.tilPassword.error = null
        }

        // 3. Verificación previa de conexión a Internet
        if (!AuthHelper.isNetworkAvailable(this)) {
            Toast.makeText(
                this,
                "Sin conexión a Internet. Verifique su red móvil o Wi-Fi para iniciar sesión.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // 4. Ejecución asíncrona en Dispatchers.IO sin bloquear el hilo principal
        setLoadingState(true)

        lifecycleScope.launch {
            try {
                val firebaseAuth = AuthHelper.getFirebaseAuthOrNull(this@LoginActivity)

                if (firebaseAuth != null) {
                    // Autenticación con Firebase Auth usando await() en hilo secundario
                    withContext(Dispatchers.IO) {
                        firebaseAuth.signInWithEmailAndPassword(email, password).await()
                    }

                    // Guardar email y timestamp en almacenamiento cifrado (NUNCA la clave)
                    sessionManager.saveSession(email)

                    Toast.makeText(this@LoginActivity, "Sesión iniciada correctamente", Toast.LENGTH_SHORT).show()
                    navigateToRoleSelect()
                } else {
                    // En caso de entorno sin google-services.json configurado aún en el IDE
                    sessionManager.saveSession(email)
                    Toast.makeText(
                        this@LoginActivity,
                        "Acceso local habilitado (recuerde vincular google-services.json en Android Studio)",
                        Toast.LENGTH_LONG
                    ).show()
                    navigateToRoleSelect()
                }
            } catch (e: FirebaseNetworkException) {
                Toast.makeText(
                    this@LoginActivity,
                    "Error de red: No fue posible conectar con los servidores de autenticación.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: FirebaseAuthInvalidUserException) {
                // Manejo genérico sin revelar si falló el correo o la contraseña
                showGenericAuthError()
            } catch (e: FirebaseAuthInvalidCredentialsException) {
                // Manejo genérico sin revelar si falló el correo o la contraseña
                showGenericAuthError()
            } catch (e: Exception) {
                val errorMsg = e.message.orEmpty().lowercase()
                if (errorMsg.contains("network") || errorMsg.contains("timeout") || errorMsg.contains("connection")) {
                    Toast.makeText(this@LoginActivity, "No hay conexión a Internet. Verifique su red.", Toast.LENGTH_LONG).show()
                } else {
                    showGenericAuthError()
                }
            } finally {
                setLoadingState(false)
            }
        }
    }

    private fun showGenericAuthError() {
        Toast.makeText(
            this,
            "Credenciales inválidas o cuenta no registrada. Verifique sus datos.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun setLoadingState(isLoading: Boolean) {
        binding.progressBarLogin.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !isLoading
        binding.btnGoRegister.isEnabled = !isLoading
        binding.tilEmail.isEnabled = !isLoading
        binding.tilPassword.isEnabled = !isLoading
    }

    private fun navigateToRoleSelect() {
        val intent = Intent(this, RoleSelectActivity::class.java)
        startActivity(intent)
        finish()
    }

    companion object {
        private const val KEY_SAVED_EMAIL = "saved_state_email"
    }
}
