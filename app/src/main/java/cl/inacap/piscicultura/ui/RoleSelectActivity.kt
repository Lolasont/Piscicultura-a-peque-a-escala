package cl.inacap.piscicultura.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import cl.inacap.piscicultura.bluetooth.PermissionHelper
import cl.inacap.piscicultura.security.AuthHelper
import cl.inacap.piscicultura.security.EncryptedSessionManager
import com.example.databinding.ActivityRoleSelectBinding

/**
 * Actividad para seleccionar el rol del dispositivo físico:
 * 1. NODO SENSOR (simula ESP32 con sliders y servidor Bluetooth)
 * 2. ESTACIÓN (dashboard de telemetría y cliente Bluetooth)
 */
class RoleSelectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRoleSelectBinding
    private lateinit var sessionManager: EncryptedSessionManager

    private var targetRole: Class<*>? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            targetRole?.let { navigateTo(it) }
        } else {
            Toast.makeText(
                this,
                "Permisos Bluetooth no concedidos. Algunas funciones de comunicación no estarán disponibles.",
                Toast.LENGTH_LONG
            ).show()
            // Permitir entrar de todas maneras para modo simulación local sin cerrar la app
            targetRole?.let { navigateTo(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRoleSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = EncryptedSessionManager(this)

        val email = sessionManager.getSessionEmail().ifEmpty { "Operador" }
        binding.tvWelcomeUser.text = "Operador: $email"
        binding.tvSessionType.text = "Sesión Firebase activa (cifrada con Keystore AES256 - 24h)"

        setupListeners()
    }

    private fun setupListeners() {
        binding.btnLogout.setOnClickListener {
            // Limpieza completa de Firebase Auth y EncryptedSharedPreferences
            val auth = AuthHelper.getFirebaseAuthOrNull(this)
            auth?.signOut()
            sessionManager.clearSession()

            Toast.makeText(this, "Sesión cerrada correctamente", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        binding.btnLaunchNodo.setOnClickListener {
            checkPermissionsAndLaunch(NodoActivity::class.java)
        }

        binding.btnLaunchEstacion.setOnClickListener {
            checkPermissionsAndLaunch(EstacionActivity::class.java)
        }
    }

    private fun checkPermissionsAndLaunch(destination: Class<*>) {
        targetRole = destination
        if (PermissionHelper.hasBluetoothPermissions(this)) {
            navigateTo(destination)
        } else {
            permissionLauncher.launch(PermissionHelper.getRequiredBluetoothPermissions())
        }
    }

    private fun navigateTo(destination: Class<*>) {
        val intent = Intent(this, destination)
        startActivity(intent)
    }
}
