package cl.inacap.piscicultura.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.R
import com.example.databinding.ActivityEstacionBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * Actividad del ROL ESTACIÓN DE MONITOREO.
 * Muestra el dashboard con 4 tarjetas Material de telemetría en tiempo real:
 * - Temperatura y Turbidez con alertas en color rojo fuera de rango.
 * - Estados de actuadores (Aireador y Alimentador) y contador regresivo sincronizado.
 * - Panel de comandos remotos OT hacia el Nodo Sensor.
 * - Manejo robusto de desconexiones sin crashear mediante Snackbar.
 * - Todo el I/O en Dispatchers.IO y observación de StateFlow con repeatOnLifecycle.
 */
class EstacionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEstacionBinding
    private val viewModel: EstacionViewModel by viewModels()

    private var bluetoothAdapter: BluetoothAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEstacionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()

        setupListeners()
        observeViewModel()
    }

    private fun setupListeners() {
        // Selector de dispositivo emparejado mediante diálogo
        binding.btnSelectDevice.setOnClickListener {
            showDeviceSelectionDialog()
        }

        // Botón Conectar
        binding.btnConnect.setOnClickListener {
            viewModel.conectar(bluetoothAdapter)
        }

        // Botón Desconectar
        binding.btnDisconnect.setOnClickListener {
            viewModel.desconectar()
        }

        // Comando OT: Forzar Alimentación Inmediata
        binding.btnForzarAlimentacion.setOnClickListener {
            viewModel.enviarFeed()
        }

        // Comandos OT: Modo Aireador (Auto, Manual ON, Manual OFF)
        binding.btnAireadorAuto.setOnClickListener {
            viewModel.setAireador("AUTO")
        }

        binding.btnAireadorOn.setOnClickListener {
            viewModel.setAireador("ON")
        }

        binding.btnAireadorOff.setOnClickListener {
            viewModel.setAireador("OFF")
        }
    }

    private fun showDeviceSelectionDialog() {
        val bondedDevices = viewModel.getBondedDevices()
        if (bondedDevices.isEmpty()) {
            Snackbar.make(
                binding.root,
                "No hay dispositivos Bluetooth emparejados. Empareja el celular Nodo en Ajustes de Android.",
                Snackbar.LENGTH_LONG
            ).show()
            return
        }

        val deviceNames = bondedDevices.map { device ->
            val name = try { device.name ?: device.address } catch (_: SecurityException) { device.address }
            "$name\n[${device.address}]"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("Seleccionar Nodo Sensor (ESP32)")
            .setItems(deviceNames) { _, which ->
                val selected = bondedDevices[which]
                viewModel.setSelectedDevice(selected)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun observeViewModel() {
        // Observación del StateFlow seguro dentro del ciclo de vida (repeatOnLifecycle)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        renderUi(state)
                    }
                }

                // Observación de eventos emergentes para Snackbar (caídas de conexión, comandos, ACKs)
                launch {
                    viewModel.snackbarEvent.collect { message ->
                        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun renderUi(state: EstacionViewModel.UiState) {
        // 1. Selector y Botones de Conexión
        binding.btnSelectDevice.text = state.selectedDeviceName
        binding.tvEstacionConnectionStatus.text = state.statusText

        if (state.isConnected) {
            binding.viewConnectionDot.setBackgroundResource(R.drawable.circle_status_connected)
            binding.btnConnect.isEnabled = false
            binding.btnDisconnect.isEnabled = true
            binding.btnSelectDevice.isEnabled = false
        } else if (state.isConnecting) {
            binding.viewConnectionDot.setBackgroundResource(R.drawable.circle_status_inactive)
            binding.btnConnect.isEnabled = false
            binding.btnDisconnect.isEnabled = true
            binding.btnSelectDevice.isEnabled = false
        } else {
            binding.viewConnectionDot.setBackgroundResource(R.drawable.circle_status_inactive)
            binding.btnConnect.isEnabled = true
            binding.btnDisconnect.isEnabled = false
            binding.btnSelectDevice.isEnabled = true
        }

        // 2. Indicador de Última Actualización y Datos Desactualizados (> 6s)
        val tele = state.telemetria
        if (tele.timestamp > 0L) {
            binding.tvUltimaActualizacion.text = "Última actualización hace ${state.segundosDesdeUltimaActualizacion} s"
        } else {
            binding.tvUltimaActualizacion.text = "Esperando telemetría del Nodo Sensor..."
        }

        if (state.isDatosDesactualizados) {
            binding.tvAlertaDesactualizado.visibility = View.VISIBLE
            binding.tvUltimaActualizacion.setTextColor(ContextCompat.getColor(this, R.color.status_active))
        } else {
            binding.tvAlertaDesactualizado.visibility = View.GONE
            binding.tvUltimaActualizacion.setTextColor(ContextCompat.getColor(this, R.color.md_theme_dark_onSurfaceVariant))
        }

        // 3. Tarjeta 1: Temperatura (°C) con alerta fuera de rango en rojo
        if (tele.timestamp > 0L) {
            binding.tvTempValor.text = String.format("%.1f °C", tele.temperatura)
            val fueraDeRango = (tele.temperatura < 18.0f || tele.temperatura > 26.0f)
            if (fueraDeRango) {
                binding.tvTempValor.setTextColor(ContextCompat.getColor(this, R.color.status_active))
                binding.tvTempEstadoRango.text = if (tele.temperatura < 18.0f) "Baja (<18°C)" else "Crítica (>26°C)"
                binding.tvTempEstadoRango.setTextColor(ContextCompat.getColor(this, R.color.status_active))
            } else {
                binding.tvTempValor.setTextColor(ContextCompat.getColor(this, R.color.accent_blue))
                binding.tvTempEstadoRango.text = "Óptima (18-26°C)"
                binding.tvTempEstadoRango.setTextColor(ContextCompat.getColor(this, R.color.status_inactive))
            }
        }

        // 4. Tarjeta 2: Turbidez (%) con alerta fuera de rango en rojo
        if (tele.timestamp > 0L) {
            binding.tvTurbValor.text = String.format("%.1f %%", tele.turbidez)
            val turbidezExcedida = (tele.turbidez > 60.0f)
            if (turbidezExcedida) {
                binding.tvTurbValor.setTextColor(ContextCompat.getColor(this, R.color.status_active))
                binding.tvTurbEstadoRango.text = "Turbia (>60%)"
                binding.tvTurbEstadoRango.setTextColor(ContextCompat.getColor(this, R.color.status_active))
            } else {
                binding.tvTurbValor.setTextColor(ContextCompat.getColor(this, R.color.accent_blue))
                binding.tvTurbEstadoRango.text = "Óptima (<=60%)"
                binding.tvTurbEstadoRango.setTextColor(ContextCompat.getColor(this, R.color.status_inactive))
            }
        }

        // 5. Tarjeta 3: Aireador (ACTIVADO en rojo #FF6B6B / APAGADO en verde #7CFC00)
        if (tele.aireadorEncendido) {
            binding.tvAireadorValor.text = "ACTIVADO"
            binding.tvAireadorValor.setTextColor(ContextCompat.getColor(this, R.color.status_active))
        } else {
            binding.tvAireadorValor.text = "APAGADO"
            binding.tvAireadorValor.setTextColor(ContextCompat.getColor(this, R.color.status_inactive))
        }
        binding.tvAireadorModoDisplay.text = "Modo: ${tele.modoAireador}"

        // 6. Tarjeta 4: Alimentador y Contador Regresivo Sincronizado
        when {
            tele.alimentadorActivo -> {
                binding.tvAlimentadorValor.text = "ACTIVADO"
                binding.tvAlimentadorValor.setTextColor(ContextCompat.getColor(this, R.color.status_active))
                binding.tvAlimentadorMensajeDisplay.text = "Alimentando ahora..."
                binding.tvAlimentadorMensajeDisplay.setTextColor(ContextCompat.getColor(this, R.color.status_active))
            }
            tele.alimentadorPospuesto -> {
                binding.tvAlimentadorValor.text = "POSPUESTO"
                binding.tvAlimentadorValor.setTextColor(ContextCompat.getColor(this, R.color.status_active))
                binding.tvAlimentadorMensajeDisplay.text = "En espera (aireador activo)"
                binding.tvAlimentadorMensajeDisplay.setTextColor(ContextCompat.getColor(this, R.color.status_active))
            }
            else -> {
                binding.tvAlimentadorValor.text = "EN ESPERA"
                binding.tvAlimentadorValor.setTextColor(ContextCompat.getColor(this, R.color.status_inactive))
                val segs = tele.proxAlimentacionSegundos
                binding.tvAlimentadorMensajeDisplay.text = "Próxima alimentación en $segs s"
                binding.tvAlimentadorMensajeDisplay.setTextColor(ContextCompat.getColor(this, R.color.white))
            }
        }

        // 7. Habilitación de Botones de Comandos según estado de conexión
        binding.btnForzarAlimentacion.isEnabled = state.isConnected
        binding.btnAireadorAuto.isEnabled = state.isConnected
        binding.btnAireadorOn.isEnabled = state.isConnected
        binding.btnAireadorOff.isEnabled = state.isConnected
    }
}
