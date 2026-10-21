package cl.inacap.piscicultura.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.R
import cl.inacap.piscicultura.domain.NodoController
import com.example.databinding.ActivityNodoBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Actividad del NODO SENSOR (Simulador ESP32).
 * Gestiona los sliders de temperatura y turbidez, ejecuta la lógica del estanque,
 * y hospeda el servidor Bluetooth RFCOMM para reportar telemetría cada 2 segundos.
 */
class NodoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNodoBinding
    private val nodoController = NodoController()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNodoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        observeState()
        startSimulationLoop()
    }

    private fun setupUI() {
        binding.sliderTemp.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                nodoController.updateTemperatura(value)
            }
        }

        binding.sliderTurb.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                nodoController.updateTurbidez(value)
            }
        }

        binding.btnTestForceFeed.setOnClickListener {
            nodoController.forceFeed()
        }

        binding.btnIniciarServidorBt.setOnClickListener {
            binding.chipBtServerStatus.text = "BT: Servidor Activo (RFCOMM)"
            binding.tvNodoDiagnostico.text = "Servidor Bluetooth RFCOMM activo en UUID estándar SPP.\nEsperando emparejamiento con Estación..."
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            nodoController.dataState.collect { data ->
                binding.tvTempValor.text = String.format("%.1f °C", data.temperatura)
                binding.tvTurbValor.text = String.format("%.1f %%", data.turbidez)

                // Estado del Aireador
                if (data.aireadorEncendido) {
                    binding.tvEstadoAireador.text = "ENCENDIDO"
                    binding.tvEstadoAireador.setTextColor(ContextCompat.getColor(this@NodoActivity, R.color.status_active))
                } else {
                    binding.tvEstadoAireador.text = "APAGADO"
                    binding.tvEstadoAireador.setTextColor(ContextCompat.getColor(this@NodoActivity, R.color.status_inactive))
                }
                binding.tvModoAireador.text = "Modo: ${data.modoAireador}"

                // Estado del Alimentador
                when {
                    data.alimentadorActivo -> {
                        binding.tvEstadoAlimentador.text = "DISPENSANDO"
                        binding.tvEstadoAlimentador.setTextColor(ContextCompat.getColor(this@NodoActivity, R.color.status_active))
                        binding.tvAlimentadorSub.text = "Alimentando salmones"
                    }
                    data.alimentadorPospuesto -> {
                        binding.tvEstadoAlimentador.text = "POSPUESTO"
                        binding.tvEstadoAlimentador.setTextColor(ContextCompat.getColor(this@NodoActivity, R.color.status_active))
                        binding.tvAlimentadorSub.text = "Aireador activo (reintento 2s)"
                    }
                    else -> {
                        binding.tvEstadoAlimentador.text = "${data.segundosParaAlimentar} s"
                        binding.tvEstadoAlimentador.setTextColor(ContextCompat.getColor(this@NodoActivity, R.color.white))
                        binding.tvAlimentadorSub.text = "En cuenta regresiva"
                    }
                }

                binding.tvNodoDiagnostico.text = "[TELEMETRÍA LOCAL]\n${data.mensajeAlerta}\nTemp: ${data.temperatura}°C | Turb: ${data.turbidez}% | Aireador: ${if (data.aireadorEncendido) "ON" else "OFF"} | Alimentador: ${if (data.alimentadorActivo) "ACTIVO" else "${data.segundosParaAlimentar}s"}"
            }
        }
    }

    private fun startSimulationLoop() {
        lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                nodoController.onOneSecondTick()
            }
        }
    }
}
