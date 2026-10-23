package cl.inacap.piscicultura.ui

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.R
import cl.inacap.piscicultura.bluetooth.PermissionHelper
import cl.inacap.piscicultura.security.AuthHelper
import cl.inacap.piscicultura.security.EncryptedSessionManager
import com.example.databinding.ActivityRoleSelectBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Actividad para selección de rol (Estanque vs Panel de control).
 * Gestiona el flujo integral de permisos en tiempo de ejecución para Bluetooth clásico (RFCOMM):
 * - Detección por versión de Android (12+ vs legacy).
 * - Diálogo explicativo (Rationale) si el sistema lo sugiere.
 * - Manejo ante rechazo sin cerrar la app (estado amigable con botones de reintento y ajustes).
 * - Verificación de encendido del adaptador Bluetooth y hardware compatible.
 * - Cierre de sesión seguro con limpieza de Firebase Auth y EncryptedSharedPreferences.
 */
class RoleSelectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRoleSelectBinding
    private lateinit var sessionManager: EncryptedSessionManager

    // Rol pendiente que el usuario intentó abrir antes de solicitar permisos o encender Bluetooth
    private var pendingTargetClass: Class<*>? = null
    private var isHelpExpanded: Boolean = true

    // Launcher para solicitar los permisos en tiempo de ejecución
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        val allGranted = permissionsMap.values.isNotEmpty() && permissionsMap.values.all { it }
        if (allGranted) {
            hidePermissionWarning()
            checkBluetoothEnabledAndProceed()
        } else {
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

        // Mostrar información del operador
        val email = sessionManager.getSessionEmail().ifEmpty { "Operador" }
        binding.tvWelcomeUser.text = "${getString(R.string.role_operator_label)} $email"
        binding.tvSessionType.text = "Sesión activa y segura"

        setupListeners()
    }

    override fun onResume() {
        super.onResume()
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

        // Selección de Rol: ESTANQUE (SENSOR)
        binding.btnLaunchNodo.setOnClickListener {
            handleRoleSelection(NodoActivity::class.java)
        }

        // Selección de Rol: PANEL DE CONTROL
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

        // Tarjeta plegable de ayuda
        binding.layoutHelpHeader.setOnClickListener {
            isHelpExpanded = !isHelpExpanded
            binding.layoutHelpContent.visibility = if (isHelpExpanded) View.VISIBLE else View.GONE
            binding.imgHelpChevron.setImageResource(
                if (isHelpExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            )
        }
    }

    private fun handleRoleSelection(targetClass: Class<*>) {
        pendingTargetClass = targetClass

        // 1. Validar si el dispositivo soporta Bluetooth
        if (!PermissionHelper.isBluetoothSupported(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Bluetooth No Disponible")
                .setMessage("Este dispositivo no cuenta con Bluetooth, el cual es imprescindible para conectar el estanque con el panel de control.")
                .setPositiveButton("Aceptar", null)
                .show()
            return
        }

        // 2. Validar si ya cuenta con los permisos
        if (PermissionHelper.hasBluetoothPermissions(this)) {
            hidePermissionWarning()
            checkBluetoothEnabledAndProceed()
        } else {
            if (PermissionHelper.shouldShowRationale(this)) {
                showRationaleDialog()
            } else {
                requestBluetoothPermissions()
            }
        }
    }

    private fun showRationaleDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.permissions_title))
            .setMessage(getString(R.string.permissions_message))
            .setPositiveButton(getString(R.string.permissions_btn_retry)) { _, _ ->
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

    private fun checkBluetoothEnabledAndProceed() {
        try {
            if (PermissionHelper.isBluetoothEnabled(this)) {
                hidePermissionWarning()
                proceedToTargetActivity()
            } else {
                requestEnableBluetooth()
            }
        } catch (_: SecurityException) {
            showPermissionDeniedState()
        }
    }

    private fun requestEnableBluetooth() {
        try {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        } catch (_: SecurityException) {
            showPermissionDeniedState()
        } catch (_: Exception) {
            Toast.makeText(this, "Por favor activa el Bluetooth en los ajustes de tu celular.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showPermissionDeniedState() {
        binding.cardPermissionWarning.visibility = View.VISIBLE
        binding.tvWarningTitle.text = getString(R.string.permissions_title)
        binding.tvWarningMessage.text = getString(R.string.permissions_message)

        val permanentlyDenied = !PermissionHelper.shouldShowRationale(this)
        binding.btnOpenSettings.visibility = if (permanentlyDenied) View.VISIBLE else View.GONE
        binding.btnRetryPermissions.visibility = View.VISIBLE
        binding.btnEnableBluetooth.visibility = View.GONE
    }

    private fun showBluetoothDisabledState() {
        binding.cardPermissionWarning.visibility = View.VISIBLE
        binding.tvWarningTitle.text = getString(R.string.permissions_bluetooth_off_title)
        binding.tvWarningMessage.text = getString(R.string.permissions_bluetooth_off_msg)
        binding.btnRetryPermissions.visibility = View.GONE
        binding.btnOpenSettings.visibility = View.GONE
        binding.btnEnableBluetooth.visibility = View.VISIBLE
    }

    private fun hidePermissionWarning() {
        binding.cardPermissionWarning.visibility = View.GONE
    }

    private fun proceedToTargetActivity() {
        val target = pendingTargetClass ?: return
        val intent = Intent(this, target)
        startActivity(intent)
    }
}
