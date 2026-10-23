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
 * Actividad del ROL PANEL DE CONTROL.
 * Muestra el dashboard con 4 tarjetas de telemetría en tiempo real:
 * - Temperatura y Turbidez con alertas visuales claras fuera de rango.
 * - Banner resumen estilo semáforo (Todo en orden / Atención requerida).
 * - Estados de equipos (Aireador y Alimentador) y cuenta regresiva sincronizada.
 * - Panel de control a distancia hacia el Estanque.
 * - Manejo robusto de desconexiones sin crashear mediante Snackbar amigable.
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
        // Selector de celular del estanque mediante diálogo
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

        // Control a distancia: Dar Alimento Ahora
        binding.btnForzarAlimentacion.setOnClickListener {
            viewModel.enviarFeed()
        }

        // Controles de Modo del Aireador (Automático, Encender, Apagar)
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
                "No hay celulares vinculados. Vincula el celular del estanque en los Ajustes de Bluetooth de tu teléfono.",
                Snackbar.LENGTH_LONG
            ).show()
            return
        }

        val deviceNames = bondedDevices.map { device ->
            val name = try { device.name ?: device.address } catch (_: SecurityException) { device.address }
            name
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.estacion_device_prompt))
            .setItems(deviceNames) { _, which ->
                val selected = bondedDevices[which]
                viewModel.setSelectedDevice(selected)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        renderUi(state)
                    }
                }

                launch {
                    viewModel.snackbarEvent.collect { message ->
                        val friendlyMessage = when {
                            message.contains("Desconectado") || message.contains("caída") ->
                                "Se perdió la conexión con el estanque. Presiona 'Conectar' para reanudar."
                            message.contains("Conectado con éxito") ->
                                "¡Conexión establecida con el estanque!"
                            message.contains("Alimentación forzada") ->
                                "Orden de alimentación enviada con éxito."
                            else -> message
                        }
                        Snackbar.make(binding.root, friendlyMessage, Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun renderUi(state: EstacionViewModel.UiState) {
        // 1. Selector y Botones de Conexión
        binding.btnSelectDevice.text = state.selectedDeviceName.ifEmpty { getString(R.string.estacion_device_select_hint) }
        binding.tvEstacionConnectionStatus.text = when {
            state.isConnected -> "Conectado"
            state.isConnecting -> "Conectando..."
            else -> "Desconectado"
        }

        if (state.isConnected) {
            binding.viewConnectionDot.setBackgroundResource(R.drawable.circle_status_connected)
            binding.tvEstacionConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.status_ok))
            binding.btnConnect.isEnabled = false
            binding.btnDisconnect.isEnabled = true
            binding.btnSelectDevice.isEnabled = false
        } else if (state.isConnecting) {
            binding.viewConnectionDot.setBackgroundResource(R.drawable.circle_status_inactive)
            binding.tvEstacionConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.status_warning))
            binding.btnConnect.isEnabled = false
            binding.btnDisconnect.isEnabled = true
            binding.btnSelectDevice.isEnabled = false
        } else {
            binding.viewConnectionDot.setBackgroundResource(R.drawable.circle_status_inactive)
            binding.tvEstacionConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onSurfaceVariant))
            binding.btnConnect.isEnabled = true
            binding.btnDisconnect.isEnabled = false
            binding.btnSelectDevice.isEnabled = true
        }

        // 2. Banner Resumen Estilo Semáforo
        val tele = state.telemetria
        if (state.isConnected && tele.timestamp > 0L) {
            val tempOutOfRange = (tele.temperatura < 18.0f || tele.temperatura > 26.0f)
            val turbOutOfRange = (tele.turbidez > 60.0f)

            if (tempOutOfRange || turbOutOfRange) {
                binding.cardStatusBanner.setBackgroundResource(R.drawable.bg_banner_alert)
                binding.imgBannerStatus.setImageResource(R.drawable.ic_warning)
                binding.imgBannerStatus.imageTintList = ContextCompat.getColorStateList(this, R.color.status_alert)
                binding.tvBannerTitle.text = getString(R.string.estacion_banner_alert_title)
                binding.tvBannerTitle.setTextColor(ContextCompat.getColor(this, R.color.status_alert))
                binding.tvBannerDesc.text = when {
                    tempOutOfRange && turbOutOfRange -> "Temperatura y turbidez fuera del rango óptimo para los salmones."
                    tempOutOfRange -> "La temperatura del agua está fuera del rango ideal (18 a 26 °C)."
                    else -> "La turbidez del agua supera el 60 % recomendado."
                }
            } else {
                binding.cardStatusBanner.setBackgroundResource(R.drawable.bg_banner_ok)
                binding.imgBannerStatus.setImageResource(R.drawable.ic_check_circle)
                binding.imgBannerStatus.imageTintList = ContextCompat.getColorStateList(this, R.color.status_ok)
                binding.tvBannerTitle.text = getString(R.string.estacion_banner_ok_title)
                binding.tvBannerTitle.setTextColor(ContextCompat.getColor(this, R.color.status_ok))
                binding.tvBannerDesc.text = getString(R.string.estacion_banner_ok_desc)
            }
        } else if (state.isConnected) {
            binding.cardStatusBanner.setBackgroundResource(R.drawable.bg_banner_warning)
            binding.imgBannerStatus.setImageResource(R.drawable.ic_info)
            binding.imgBannerStatus.imageTintList = ContextCompat.getColorStateList(this, R.color.status_warning)
            binding.tvBannerTitle.text = "Conectado al estanque"
            binding.tvBannerTitle.setTextColor(ContextCompat.getColor(this, R.color.status_warning))
            binding.tvBannerDesc.text = "Esperando la primera lectura de los sensores..."
        } else {
            binding.cardStatusBanner.setBackgroundResource(R.drawable.bg_banner_warning)
            binding.imgBannerStatus.setImageResource(R.drawable.ic_warning)
            binding.imgBannerStatus.imageTintList = ContextCompat.getColorStateList(this, R.color.status_warning)
            binding.tvBannerTitle.text = getString(R.string.estacion_banner_waiting_title)
            binding.tvBannerTitle.setTextColor(ContextCompat.getColor(this, R.color.status_warning))
            binding.tvBannerDesc.text = getString(R.string.estacion_banner_waiting_desc)
        }

        // 3. Indicador de Última Actualización y Datos Desactualizados (> 6s)
        if (tele.timestamp > 0L) {
            binding.tvUltimaActualizacion.text = "Actualizado hace ${state.segundosDesdeUltimaActualizacion} s"
        } else {
            binding.tvUltimaActualizacion.text = getString(R.string.estacion_waiting_telemetry)
        }

        if (state.isDatosDesactualizados) {
            binding.tvAlertaDesactualizado.visibility = View.VISIBLE
            binding.tvUltimaActualizacion.setTextColor(ContextCompat.getColor(this, R.color.status_warning))
        } else {
            binding.tvAlertaDesactualizado.visibility = View.GONE
            binding.tvUltimaActualizacion.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onSurfaceVariant))
        }

        // 4. Tarjeta 1: Temperatura (°C) con alerta fuera de rango
        if (tele.timestamp > 0L) {
            binding.tvTempValor.text = String.format("%.1f °C", tele.temperatura)
            val fueraDeRango = (tele.temperatura < 18.0f || tele.temperatura > 26.0f)
            if (fueraDeRango) {
                binding.tvTempValor.setTextColor(ContextCompat.getColor(this, R.color.status_alert))
                binding.tvTempEstadoRango.text = if (tele.temperatura < 18.0f) "Baja (<18°C)" else "Alta (>26°C)"
                binding.tvTempEstadoRango.setTextColor(ContextCompat.getColor(this, R.color.status_alert))
            } else {
                binding.tvTempValor.setTextColor(ContextCompat.getColor(this, R.color.md_theme_primary))
                binding.tvTempEstadoRango.text = "Normal (18 a 26 °C)"
                binding.tvTempEstadoRango.setTextColor(ContextCompat.getColor(this, R.color.status_ok))
            }
        } else {
            binding.tvTempValor.text = "--.- °C"
            binding.tvTempValor.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onSurfaceVariant))
            binding.tvTempEstadoRango.text = "Sin datos"
            binding.tvTempEstadoRango.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onSurfaceVariant))
        }

        // 5. Tarjeta 2: Turbidez (%) con alerta fuera de rango
        if (tele.timestamp > 0L) {
            binding.tvTurbValor.text = String.format("%.1f %%", tele.turbidez)
            val turbidezExcedida = (tele.turbidez > 60.0f)
            if (turbidezExcedida) {
                binding.tvTurbValor.setTextColor(ContextCompat.getColor(this, R.color.status_alert))
                binding.tvTurbEstadoRango.text = "Agua turbia (>60%)"
                binding.tvTurbEstadoRango.setTextColor(ContextCompat.getColor(this, R.color.status_alert))
            } else {
                binding.tvTurbValor.setTextColor(ContextCompat.getColor(this, R.color.md_theme_primary))
                binding.tvTurbEstadoRango.text = "Normal (<=60%)"
                binding.tvTurbEstadoRango.setTextColor(ContextCompat.getColor(this, R.color.status_ok))
            }
        } else {
            binding.tvTurbValor.text = "--.- %"
            binding.tvTurbValor.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onSurfaceVariant))
            binding.tvTurbEstadoRango.text = "Sin datos"
            binding.tvTurbEstadoRango.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onSurfaceVariant))
        }

        // 6. Tarjeta 3: Aireador
        if (tele.aireadorEncendido) {
            binding.tvAireadorValor.text = "ENCENDIDO"
            binding.tvAireadorValor.setTextColor(ContextCompat.getColor(this, R.color.status_alert))
        } else {
            binding.tvAireadorValor.text = "APAGADO"
            binding.tvAireadorValor.setTextColor(ContextCompat.getColor(this, R.color.status_ok))
        }
        binding.tvAireadorModoDisplay.text = "Modo: ${tele.modoAireador}"

        // 7. Tarjeta 4: Alimentador y Cuenta Regresiva Sincronizada
        when {
            tele.alimentadorActivo -> {
                binding.tvAlimentadorValor.text = "DISPENSANDO"
                binding.tvAlimentadorValor.setTextColor(ContextCompat.getColor(this, R.color.status_alert))
                binding.tvAlimentadorMensajeDisplay.text = "Dispensando alimento..."
                binding.tvAlimentadorMensajeDisplay.setTextColor(ContextCompat.getColor(this, R.color.status_alert))
            }
            tele.alimentadorPospuesto -> {
                binding.tvAlimentadorValor.text = "POSPUESTO"
                binding.tvAlimentadorValor.setTextColor(ContextCompat.getColor(this, R.color.status_warning))
                binding.tvAlimentadorMensajeDisplay.text = "En espera (aireador encendido)"
                binding.tvAlimentadorMensajeDisplay.setTextColor(ContextCompat.getColor(this, R.color.status_warning))
            }
            else -> {
                binding.tvAlimentadorValor.text = "EN ESPERA"
                binding.tvAlimentadorValor.setTextColor(ContextCompat.getColor(this, R.color.status_ok))
                val segs = tele.proxAlimentacionSegundos
                binding.tvAlimentadorMensajeDisplay.text = "Próxima comida en: $segs s"
                binding.tvAlimentadorMensajeDisplay.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onSurfaceVariant))
            }
        }

        // 8. Habilitación de Botones de Comandos según estado de conexión
        binding.btnForzarAlimentacion.isEnabled = state.isConnected
        binding.btnAireadorAuto.isEnabled = state.isConnected
        binding.btnAireadorOn.isEnabled = state.isConnected
        binding.btnAireadorOff.isEnabled = state.isConnected
    }
}
