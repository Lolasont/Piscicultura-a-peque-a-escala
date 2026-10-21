package cl.inacap.piscicultura.ui

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import cl.inacap.piscicultura.bluetooth.PermissionHelper
import cl.inacap.piscicultura.security.AuthHelper
import cl.inacap.piscicultura.security.EncryptedSessionManager
import com.example.databinding.ActivityRoleSelectBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Actividad para selección de rol (Nodo Sensor vs Estación de Monitoreo).
 * Gestiona el flujo integral de permisos en tiempo de ejecución para Bluetooth clásico (RFCOMM):
 * - Detección por versión de Android (12+ vs legacy).
 * - Diálogo explicativo (Rationale) si el sistema lo sugiere.
 * - Manejo ante rechazo sin cerrar la app (estado claro con botones de reintento y ajustes).
 * - Verificación de encendido del adaptador Bluetooth y hardware compatible.
 * - Cierre de sesión seguro con limpieza de Firebase Auth y EncryptedSharedPreferences.
 */
class RoleSelectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRoleSelectBinding
    private lateinit var sessionManager: EncryptedSessionManager

    // Rol pendiente que el usuario intentó abrir antes de solicitar permisos o encender Bluetooth
    private var pendingTargetClass: Class<*>? = null

    // Launcher para solicitar los permisos en tiempo de ejecución
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        val allGranted = permissionsMap.values.isNotEmpty() && permissionsMap.values.all { it }
        if (allGranted) {
            hidePermissionWarning()
            // Una vez concedidos, verificar si el Bluetooth está encendido
            checkBluetoothEnabledAndProceed()
        } else {
            // El usuario rechazó los permisos: la app NO se cierra
            showPermissionDeniedState()
        }
    }

    // Launcher para solicitar el encendido del adaptador Bluetooth
    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            hidePermissionWarning()
            proceedToTargetActivity()
        } else {
            showBluetoothDisabledState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRoleSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = EncryptedSessionManager(this)

        // Mostrar información de la sesión activa cifrada
        val email = sessionManager.getSessionEmail().ifEmpty { "Operador" }
        binding.tvWelcomeUser.text = "Operador: $email"
        binding.tvSessionType.text = "Sesión Firebase activa (cifrada con Keystore AES256 - 24h)"

        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        // Si el usuario regresa de la pantalla de Ajustes del sistema, reevaluar el estado
        if (PermissionHelper.hasBluetoothPermissions(this)) {
            if (PermissionHelper.isBluetoothEnabled(this)) {
                hidePermissionWarning()
            } else if (binding.cardPermissionWarning.visibility == View.VISIBLE) {
                showBluetoothDisabledState()
            }
        }
    }

    private fun setupListeners() {
        // Cierre de sesión seguro
        binding.btnLogout.setOnClickListener {
            val auth = AuthHelper.getFirebaseAuthOrNull(this)
            auth?.signOut()
            sessionManager.clearSession()

            Toast.makeText(this, "Sesión cerrada correctamente", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        }

        // Selección de Rol: NODO SENSOR (Simula ESP32)
        binding.btnLaunchNodo.setOnClickListener {
            handleRoleSelection(NodoActivity::class.java)
        }

        // Selección de Rol: ESTACIÓN DE MONITOREO
        binding.btnLaunchEstacion.setOnClickListener {
            handleRoleSelection(EstacionActivity::class.java)
        }

        // Botón de reintento de permisos tras rechazo
        binding.btnRetryPermissions.setOnClickListener {
            requestBluetoothPermissions()
        }

        // Botón para ir a Ajustes de la app si se marcó "No volver a preguntar"
        binding.btnOpenSettings.setOnClickListener {
            PermissionHelper.openAppSettings(this)
        }

        // Botón para solicitar encender el adaptador Bluetooth
        binding.btnEnableBluetooth.setOnClickListener {
            requestEnableBluetooth()
        }
    }

    /**
     * Valida hardware, permisos y encendido de Bluetooth antes de abrir la pantalla del rol seleccionado.
     */
    private fun handleRoleSelection(targetClass: Class<*>) {
        pendingTargetClass = targetClass

        // 1. Validar si el dispositivo físico soporta Bluetooth
        if (!PermissionHelper.isBluetoothSupported(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Hardware No Compatible")
                .setMessage("Este dispositivo no cuenta con adaptador Bluetooth, el cual es imprescindible para comunicar el Nodo Sensor y la Estación.")
                .setPositiveButton("Aceptar", null)
                .show()
            return
        }

        // 2. Validar si ya cuenta con todos los permisos requeridos
        if (PermissionHelper.hasBluetoothPermissions(this)) {
            hidePermissionWarning()
            checkBluetoothEnabledAndProceed()
        } else {
            // Verificar si se debe mostrar justificación previa (Rationale)
            if (PermissionHelper.shouldShowRationale(this)) {
                showRationaleDialog()
            } else {
                requestBluetoothPermissions()
            }
        }
    }

    /**
     * Muestra diálogo explicativo antes de solicitar los permisos al usuario.
     */
    private fun showRationaleDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Permisos de Bluetooth Requeridos")
            .setMessage("La aplicación necesita permisos de Bluetooth (y ubicación en Android <= 11) para establecer la comunicación RFCOMM entre el Nodo Sensor y la Estación de control de salmones.")
            .setPositiveButton("Conceder") { _, _ ->
                requestBluetoothPermissions()
            }
            .setNegativeButton("Ahora no") { _, _ ->
                showPermissionDeniedState()
            }
            .setCancelable(false)
            .show()
    }

    private fun requestBluetoothPermissions() {
        val permissions = PermissionHelper.getRequiredPermissions()
        permissionLauncher.launch(permissions)
    }

    /**
     * Valida que el Bluetooth esté encendido usando BluetoothAdapter de forma segura.
     */
    private fun checkBluetoothEnabledAndProceed() {
        try {
            if (PermissionHelper.isBluetoothEnabled(this)) {
                hidePermissionWarning()
                proceedToTargetActivity()
            } else {
                requestEnableBluetooth()
            }
        } catch (_: SecurityException) {
            // Ante falta de permisos en tiempo de ejecución en Android 12+
            showPermissionDeniedState()
        }
    }

    /**
     * Solicita encender el Bluetooth mediante el Intent oficial del sistema.
     */
    private fun requestEnableBluetooth() {
        try {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        } catch (_: SecurityException) {
            showPermissionDeniedState()
        } catch (_: Exception) {
            Toast.makeText(this, "Por favor enciende el Bluetooth en los ajustes de tu celular.", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Muestra estado claro de permisos rechazados SIN cerrar la aplicación.
     */
    private fun showPermissionDeniedState() {
        binding.cardPermissionWarning.visibility = View.VISIBLE
        binding.tvWarningTitle.text = "Permisos de Bluetooth Necesarios"
        binding.tvWarningMessage.text = "Has rechazado los permisos de Bluetooth. Para operar el enlace RFCOMM del estanque debes concederlos."

        // Si el usuario marcó "No volver a preguntar", shouldShowRationale devuelve false
        val permanentlyDenied = !PermissionHelper.shouldShowRationale(this)
        binding.btnOpenSettings.visibility = if (permanentlyDenied) View.VISIBLE else View.GONE
        binding.btnRetryPermissions.visibility = View.VISIBLE
        binding.btnEnableBluetooth.visibility = View.GONE
    }

    /**
     * Muestra estado de advertencia cuando el Bluetooth está apagado en el dispositivo.
     */
    private fun showBluetoothDisabledState() {
        binding.cardPermissionWarning.visibility = View.VISIBLE
        binding.tvWarningTitle.text = "Bluetooth Desactivado"
        binding.tvWarningMessage.text = "El adaptador Bluetooth está apagado. Es necesario activarlo para emparejar el Nodo Sensor con la Estación."
        binding.btnRetryPermissions.visibility = View.GONE
        binding.btnOpenSettings.visibility = View.GONE
        binding.btnEnableBluetooth.visibility = View.VISIBLE
    }

    private fun hidePermissionWarning() {
        binding.cardPermissionWarning.visibility = View.GONE
    }

    /**
     * Navega a la actividad seleccionada (Nodo o Estación).
     */
    private fun proceedToTargetActivity() {
        val target = pendingTargetClass ?: return
        val intent = Intent(this, target)
        startActivity(intent)
    }
}
