package cl.inacap.piscicultura.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.R
import com.example.databinding.ActivityNodoBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * Actividad del ROL ESTANQUE (Simulador con sensores y actuadores).
 * Conecta los sliders de temperatura y turbidez con el NodoViewModel,
 * muestra los equipos (aireador y alimentador) con estados claros y colores accesibles,
 * y gestiona el inicio y detención de la comunicación Bluetooth.
 * Sobrevive a la rotación de pantalla sin perder estado ni desconectar la sesión.
 */
class NodoActivity : AppCompatActivity() {

    companion object {
        private const val KEY_EXIT_DIALOG_SHOWING = "key_nodo_exit_dialog_showing"
    }

    private lateinit var binding: ActivityNodoBinding
    private val viewModel: NodoViewModel by viewModels()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var isLogExpanded: Boolean = false
    private var isExitDialogShowing: Boolean = false
    private var exitConfirmDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNodoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()

        setupBackNavigation()
        setupListeners()
        observeViewModel()

        if (savedInstanceState?.getBoolean(KEY_EXIT_DIALOG_SHOWING, false) == true) {
            val state = viewModel.uiState.value
            if (state.isServerListening || state.isEstacionConectada) {
                showExitConfirmationDialog()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_EXIT_DIALOG_SHOWING, isExitDialogShowing)
    }

    private fun setupBackNavigation() {
        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleExitRequest()
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        binding.btnVolverRoles.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun handleExitRequest() {
        val state = viewModel.uiState.value
        val isActive = state.isServerListening || state.isEstacionConectada
        if (isActive) {
            showExitConfirmationDialog()
        } else {
            finish()
        }
    }

    private fun showExitConfirmationDialog() {
        exitConfirmDialog?.dismiss()
        isExitDialogShowing = true
        exitConfirmDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_exit_title)
            .setMessage(R.string.dialog_exit_message)
            .setPositiveButton(R.string.dialog_exit_confirm) { _, _ ->
                isExitDialogShowing = false
                viewModel.detenerServidor()
                finish()
            }
            .setNegativeButton(R.string.dialog_exit_stay) { dialog, _ ->
                isExitDialogShowing = false
                dialog.dismiss()
            }
            .setOnCancelListener {
                isExitDialogShowing = false
            }
            .show()
    }

    private fun setupListeners() {
        // Slider de Temperatura (0.0 a 40.0 °C)
        binding.sliderTemperatura.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                viewModel.setTemperatura(value)
            }
        }

        // Slider de Turbidez (0.0 a 100.0 %)
        binding.sliderTurbidez.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                viewModel.setTurbidez(value)
            }
        }

        // Botón de inicio / detención del Estanque
        binding.btnToggleServer.setOnClickListener {
            val currentState = viewModel.uiState.value
            if (currentState.isServerListening || currentState.isEstacionConectada) {
                viewModel.detenerServidor()
            } else {
                viewModel.iniciarServidor(bluetoothAdapter)
            }
        }

        // Botón de prueba local para forzar alimentación
        binding.btnForzarAlimentacionLocal.setOnClickListener {
            viewModel.forzarAlimentacionManual()
        }

        // Desplegable de detalles técnicos
        binding.btnToggleLog.setOnClickListener {
            isLogExpanded = !isLogExpanded
            binding.tvLogEventos.visibility = if (isLogExpanded) View.VISIBLE else View.GONE
            binding.btnToggleLog.text = if (isLogExpanded) {
                getString(R.string.nodo_btn_hide_log)
            } else {
                getString(R.string.nodo_btn_toggle_log)
            }
            binding.btnToggleLog.setIconResource(
                if (isLogExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            )
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                // 1. Sliders y etiquetas de valor
                binding.tvTempDisplay.text = String.format("%.1f °C", state.temperatura)
                val tempOutOfRange = (state.temperatura < 18.0f || state.temperatura > 26.0f)
                binding.tvTempDisplay.setTextColor(
                    ContextCompat.getColor(
                        this@NodoActivity,
                        if (tempOutOfRange) R.color.status_alert else R.color.md_theme_primary
                    )
                )

                if (Math.abs(binding.sliderTemperatura.value - state.temperatura) > 0.49f) {
                    binding.sliderTemperatura.value = state.temperatura
                }

                binding.tvTurbDisplay.text = String.format("%.1f %%", state.turbidez)
                val turbOutOfRange = (state.turbidez > 60.0f)
                binding.tvTurbDisplay.setTextColor(
                    ContextCompat.getColor(
                        this@NodoActivity,
                        if (turbOutOfRange) R.color.status_alert else R.color.md_theme_primary
                    )
                )

                if (Math.abs(binding.sliderTurbidez.value - state.turbidez) > 0.99f) {
                    binding.sliderTurbidez.value = state.turbidez
                }

                // 2. Estado de Conexión del Estanque y Botón Toggle
                if (state.isEstacionConectada) {
                    binding.tvConnectionStatus.text = getString(R.string.nodo_connection_connected)
                    binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this@NodoActivity, R.color.status_ok))
                    binding.btnToggleServer.text = getString(R.string.nodo_btn_stop)
                    binding.btnToggleServer.backgroundTintList = ContextCompat.getColorStateList(this@NodoActivity, R.color.status_alert)
                    binding.tvServerSubtext.text = "Conectado con Panel: ${state.nombreEstacionConectada}"
                } else if (state.isServerListening) {
                    binding.tvConnectionStatus.text = getString(R.string.nodo_connection_waiting)
                    binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this@NodoActivity, R.color.status_warning))
                    binding.btnToggleServer.text = getString(R.string.nodo_btn_stop)
                    binding.btnToggleServer.backgroundTintList = ContextCompat.getColorStateList(this@NodoActivity, R.color.status_alert)
                    binding.tvServerSubtext.text = "Esperando que el panel de control se conecte..."
                } else {
                    binding.tvConnectionStatus.text = getString(R.string.nodo_connection_stopped)
                    binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this@NodoActivity, R.color.md_theme_onSurfaceVariant))
                    binding.btnToggleServer.text = getString(R.string.nodo_btn_start)
                    binding.btnToggleServer.backgroundTintList = ContextCompat.getColorStateList(this@NodoActivity, R.color.md_theme_primary)
                    binding.tvServerSubtext.text = getString(R.string.nodo_server_subtext)
                }

                // 3. Tarjeta del Aireador
                if (state.aireadorEncendido) {
                    binding.tvAireadorEstado.text = getString(R.string.nodo_aerator_on)
                    binding.tvAireadorEstado.setTextColor(ContextCompat.getColor(this@NodoActivity, R.color.status_alert))
                } else {
                    binding.tvAireadorEstado.text = getString(R.string.nodo_aerator_off)
                    binding.tvAireadorEstado.setTextColor(ContextCompat.getColor(this@NodoActivity, R.color.status_ok))
                }
                binding.tvAireadorModo.text = "Modo: ${state.modoAireador}"

                // 4. Tarjeta del Alimentador
                when {
                    state.alimentadorActivo -> {
                        binding.tvAlimentadorEstado.text = getString(R.string.nodo_feeder_dispensing)
                        binding.tvAlimentadorEstado.setTextColor(ContextCompat.getColor(this@NodoActivity, R.color.status_alert))
                        binding.tvAlimentadorCuenta.text = "Dispensando alimento ahora..."
                    }
                    state.alimentadorPospuesto -> {
                        binding.tvAlimentadorEstado.text = getString(R.string.nodo_feeder_postponed)
                        binding.tvAlimentadorEstado.setTextColor(ContextCompat.getColor(this@NodoActivity, R.color.status_warning))
                        binding.tvAlimentadorCuenta.text = getString(R.string.nodo_feeder_postponed_help)
                    }
                    else -> {
                        val segs = (state.proxAlimentacionMs / 1000L).coerceAtLeast(0L)
                        binding.tvAlimentadorEstado.text = getString(R.string.nodo_feeder_waiting)
                        binding.tvAlimentadorEstado.setTextColor(ContextCompat.getColor(this@NodoActivity, R.color.status_ok))
                        binding.tvAlimentadorCuenta.text = "Próxima comida en: $segs s"
                    }
                }

                // 5. Mensaje de diagnóstico y log de eventos
                binding.tvMensajeLogica.text = state.mensajeEstado
                binding.tvLogEventos.text = state.eventLogs.joinToString("\n")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exitConfirmDialog?.dismiss()
        exitConfirmDialog = null
        if (isFinishing) {
            viewModel.detenerServidor()
        }
    }
}
