package cl.inacap.piscicultura.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import cl.inacap.piscicultura.security.AuthHelper
import cl.inacap.piscicultura.security.EncryptedSessionManager
import com.example.databinding.ActivityRegisterBinding
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Pantalla de registro de operadores mediante Firebase Authentication.
 * - Validación de correo y contraseña (mínimo 8 caracteres, al menos una letra y un número).
 * - Llamadas asíncronas con await() en corrutinas (Dispatchers.IO).
 * - Persistencia cifrada en EncryptedSharedPreferences (email y timestamp 24h, NUNCA contraseñas).
 * - Manejo robusto de errores de red y cuenta existente.
 * - Soporte a rotación de pantalla conservando el campo de correo.
 */
class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private lateinit var sessionManager: EncryptedSessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = EncryptedSessionManager(this)

        if (savedInstanceState != null) {
            val savedEmail = savedInstanceState.getString(KEY_SAVED_EMAIL, "")
            binding.etRegEmail.setText(savedEmail)
        }

        setupListeners()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Conservar el correo al rotar. NUNCA guardar la contraseña en bundles ni logs.
        val email = binding.etRegEmail.text?.toString().orEmpty()
        outState.putString(KEY_SAVED_EMAIL, email)
    }

    private fun setupListeners() {
        binding.btnSubmitRegister.setOnClickListener {
            attemptRegister()
        }

        binding.btnBackLogin.setOnClickListener {
            finish()
        }
    }

    private fun attemptRegister() {
        val email = binding.etRegEmail.text?.toString()?.trim().orEmpty()
        val password = binding.etRegPassword.text?.toString().orEmpty()
        val confirmPassword = binding.etRegConfirmPassword.text?.toString().orEmpty()

        // 1. Validación de Formato de Correo
        if (!AuthHelper.isValidEmail(email)) {
            binding.tilRegEmail.error = "Ingrese un correo electrónico válido"
            return
        } else {
            binding.tilRegEmail.error = null
        }

        // 2. Validación de Contraseña (mín. 8 caracteres, al menos una letra y un número)
        if (!AuthHelper.isValidPassword(password)) {
            binding.tilRegPassword.error = "La contraseña debe tener mín. 8 caracteres, al menos una letra y un número"
            return
        } else {
            binding.tilRegPassword.error = null
        }

        // 3. Confirmación de Contraseña
        if (password != confirmPassword) {
            binding.tilRegConfirmPassword.error = "Las contraseñas no coinciden"
            return
        } else {
            binding.tilRegConfirmPassword.error = null
        }

        // 4. Verificación de conectividad a Internet
        if (!AuthHelper.isNetworkAvailable(this)) {
            Toast.makeText(
                this,
                "Sin conexión a Internet. Verifique su red móvil o Wi-Fi para registrar la cuenta.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        setLoadingState(true)

        lifecycleScope.launch {
            try {
                val firebaseAuth = AuthHelper.getFirebaseAuthOrNull(this@RegisterActivity)

                if (firebaseAuth != null) {
                    // Creación asíncrona de usuario en Firebase Auth en hilo IO
                    withContext(Dispatchers.IO) {
                        firebaseAuth.createUserWithEmailAndPassword(email, password).await()
                    }

                    // Guardar email y timestamp en EncryptedSharedPreferences (NUNCA la contraseña)
                    sessionManager.saveSession(email)

                    Toast.makeText(this@RegisterActivity, "Cuenta creada exitosamente en Firebase", Toast.LENGTH_SHORT).show()
                    navigateToRoleSelect()
                } else {
                    // Fallback si no está cargado google-services.json en el proyecto aún
                    sessionManager.saveSession(email)
                    Toast.makeText(
                        this@RegisterActivity,
                        "Cuenta registrada localmente (vincule google-services.json para Firebase en la nube)",
                        Toast.LENGTH_LONG
                    ).show()
                    navigateToRoleSelect()
                }
            } catch (e: FirebaseAuthUserCollisionException) {
                binding.tilRegEmail.error = "Este correo electrónico ya se encuentra registrado"
            } catch (e: FirebaseAuthWeakPasswordException) {
                binding.tilRegPassword.error = "Contraseña débil: use combinación de letras, números y símbolos"
            } catch (e: FirebaseNetworkException) {
                Toast.makeText(
                    this@RegisterActivity,
                    "Error de red: No fue posible contactar los servidores de Firebase. Revise su conexión.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                val errorMsg = e.message.orEmpty().lowercase()
                if (errorMsg.contains("network") || errorMsg.contains("timeout") || errorMsg.contains("connection")) {
                    Toast.makeText(this@RegisterActivity, "No hay conexión a Internet. Verifique su red.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@RegisterActivity, "No fue posible completar el registro. Intente nuevamente.", Toast.LENGTH_LONG).show()
                }
            } finally {
                setLoadingState(false)
            }
        }
    }

    private fun setLoadingState(isLoading: Boolean) {
        binding.progressBarRegister.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.btnSubmitRegister.isEnabled = !isLoading
        binding.btnBackLogin.isEnabled = !isLoading
        binding.tilRegEmail.isEnabled = !isLoading
        binding.tilRegPassword.isEnabled = !isLoading
        binding.tilRegConfirmPassword.isEnabled = !isLoading
    }

    private fun navigateToRoleSelect() {
        val intent = Intent(this, RoleSelectActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    companion object {
        private const val KEY_SAVED_EMAIL = "saved_state_reg_email"
    }
}
