package cl.inacap.piscicultura.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.R
import com.example.databinding.ActivityNodoBinding
import kotlinx.coroutines.launch

/**
 * Actividad del ROL NODO SENSOR (Simulador ESP32).
 * Conecta los sliders de temperatura y turbidez con el NodoViewModel,
 * muestra los actuadores (aireador y alimentador) con código de colores,
 * y gestiona el inicio y detención del servidor Bluetooth RFCOMM.
 * Sobrevive a la rotación de pantalla sin perder estado ni desconectar la sesión.
 */
class NodoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNodoBinding
    private val viewModel: NodoViewModel by viewModels()

    private var bluetoothAdapter: BluetoothAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNodoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()

        setupListeners()
        observeViewModel()
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

        // Botón de encendido / apagado del Servidor Bluetooth RFCOMM
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
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                // 1. Sliders y etiquetas de valor (sin disparar ciclos si el usuario no los toca)
                binding.tvTempDisplay.text = String.format("%.1f °C", state.temperatura)
                if (Math.abs(binding.sliderTemperatura.value - state.temperatura) > 0.49f) {
                    binding.sliderTemperatura.value = state.temperatura
                }

                binding.tvTurbDisplay.text = String.format("%.1f %%", state.turbidez)
                if (Math.abs(binding.sliderTurbidez.value - state.turbidez) > 0.99f) {
                    binding.sliderTurbidez.value = state.turbidez
                }

                // 2. Estado del Servidor Bluetooth y Botón Toggle
                binding.tvConnectionStatus.text = state.serverStatusText

                if (state.isEstacionConectada) {
                    binding.btnToggleServer.text = "Desconectar"
                    binding.btnToggleServer.backgroundTintList = ContextCompat.getColorStateList(this@NodoActivity, R.color.status_active)
                    binding.tvServerSubtext.text = "Conectado a: ${state.nombreEstacionConectada}"
                } else if (state.isServerListening) {
                    binding.btnToggleServer.text = "Detener"
                    binding.btnToggleServer.backgroundTintList = ContextCompat.getColorStateList(this@NodoActivity, R.color.status_active)
                    binding.tvServerSubtext.text = "En espera de Estación..."
                } else {
                    binding.btnToggleServer.text = "Iniciar Servidor"
                    binding.btnToggleServer.backgroundTintList = ContextCompat.getColorStateList(this@NodoActivity, R.color.accent_blue)
                    binding.tvServerSubtext.text = "Servidor detenido"
                }

                // 3. Tarjeta del Aireador (ACTIVADO en rojo #FF6B6B / APAGADO en verde #7CFC00)
                if (state.aireadorEncendido) {
                    binding.tvAireadorEstado.text = "ACTIVADO"
                    binding.tvAireadorEstado.setTextColor(ContextCompat.getColor(this@NodoActivity, R.color.status_active))
                } else {
                    binding.tvAireadorEstado.text = "APAGADO"
                    binding.tvAireadorEstado.setTextColor(ContextCompat.getColor(this@NodoActivity, R.color.status_inactive))
                }
                binding.tvAireadorModo.text = "Modo: ${state.modoAireador}"

                // 4. Tarjeta del Alimentador (ACTIVADO en rojo #FF6B6B / en espera)
                when {
                    state.alimentadorActivo -> {
                        binding.tvAlimentadorEstado.text = "DISPENSANDO"
                        binding.tvAlimentadorEstado.setTextColor(ContextCompat.getColor(this@NodoActivity, R.color.status_active))
                        binding.tvAlimentadorCuenta.text = "Alimentando salmones..."
                    }
                    state.alimentadorPospuesto -> {
                        binding.tvAlimentadorEstado.text = "POSPUESTO"
                        binding.tvAlimentadorEstado.setTextColor(ContextCompat.getColor(this@NodoActivity, R.color.status_active))
                        binding.tvAlimentadorCuenta.text = "En espera (aireador activo)"
                    }
                    else -> {
                        val segs = (state.proxAlimentacionMs / 1000L).coerceAtLeast(0L)
                        binding.tvAlimentadorEstado.text = "EN ESPERA"
                        binding.tvAlimentadorEstado.setTextColor(ContextCompat.getColor(this@NodoActivity, R.color.status_inactive))
                        binding.tvAlimentadorCuenta.text = "Próxima en: $segs s"
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
        // Si el usuario sale de la actividad de forma permanente (isFinishing), detiene el servidor
        if (isFinishing) {
            viewModel.detenerServidor()
        }
    }
}
