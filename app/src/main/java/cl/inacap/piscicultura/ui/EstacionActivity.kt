package cl.inacap.piscicultura.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.R
import cl.inacap.piscicultura.data.model.PisciculturaData
import com.example.databinding.ActivityEstacionBinding

/**
 * Actividad de la ESTACIÓN DE MONITOREO.
 * Muestra el dashboard en tiempo real con datos de temperatura, turbidez,
 * estado del aireador y alimentador, y permite el envío de comandos OT.
 */
class EstacionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEstacionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEstacionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        // Mostrar datos iniciales por defecto en el dashboard
        updateDashboard(
            PisciculturaData(
                temperatura = 21.5f,
                turbidez = 18.0f,
                aireadorEncendido = false,
                modoAireador = "AUTO",
                alimentadorActivo = false,
                segundosParaAlimentar = 14,
                alimentadorPospuesto = false,
                mensajeAlerta = "Estación iniciada. Lista para vincular con Nodo Sensor."
            )
        )
    }

    private fun setupListeners() {
        binding.btnConectarBt.setOnClickListener {
            binding.tvBtConnectionStatus.text = "Buscando dispositivos vinculados..."
            Toast.makeText(this, "Iniciando escaneo Bluetooth RFCOMM", Toast.LENGTH_SHORT).show()
        }

        binding.btnComandoFeedNow.setOnClickListener {
            Toast.makeText(this, "Comando enviado: Forzar Alimentación", Toast.LENGTH_SHORT).show()
        }

        binding.btnModoAireadorAuto.setOnClickListener {
            Toast.makeText(this, "Modo Aireador cambiado a: AUTO", Toast.LENGTH_SHORT).show()
        }

        binding.btnModoAireadorOn.setOnClickListener {
            Toast.makeText(this, "Modo Aireador cambiado a: MANUAL ON", Toast.LENGTH_SHORT).show()
        }

        binding.btnModoAireadorOff.setOnClickListener {
            Toast.makeText(this, "Modo Aireador cambiado a: MANUAL OFF", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateDashboard(data: PisciculturaData) {
        binding.tvEstacionTemp.text = String.format("%.1f °C", data.temperatura)
        binding.tvEstacionTurb.text = String.format("%.1f %%", data.turbidez)

        // Badge Temperatura
        when {
            data.temperatura < 18.0f -> {
                binding.tvTempBadge.text = "Alerta: Fría (<18°C)"
                binding.tvTempBadge.setTextColor(ContextCompat.getColor(this, R.color.status_active))
            }
            data.temperatura > 26.0f -> {
                binding.tvTempBadge.text = "Alerta: Caliente (>26°C)"
                binding.tvTempBadge.setTextColor(ContextCompat.getColor(this, R.color.status_active))
            }
            else -> {
                binding.tvTempBadge.text = "Óptima (18-26°C)"
                binding.tvTempBadge.setTextColor(ContextCompat.getColor(this, R.color.status_inactive))
            }
        }

        // Badge Turbidez
        if (data.turbidez > 60.0f) {
            binding.tvTurbBadge.text = "Crítica (>60%)"
            binding.tvTurbBadge.setTextColor(ContextCompat.getColor(this, R.color.status_active))
        } else {
            binding.tvTurbBadge.text = "Óptima (<60%)"
            binding.tvTurbBadge.setTextColor(ContextCompat.getColor(this, R.color.status_inactive))
        }

        // Aireador
        if (data.aireadorEncendido) {
            binding.tvEstacionAireador.text = "ENCENDIDO"
            binding.tvEstacionAireador.setTextColor(ContextCompat.getColor(this, R.color.status_active))
        } else {
            binding.tvEstacionAireador.text = "APAGADO"
            binding.tvEstacionAireador.setTextColor(ContextCompat.getColor(this, R.color.status_inactive))
        }
        binding.tvEstacionModoAireador.text = "Modo: ${data.modoAireador}"

        // Alimentador
        when {
            data.alimentadorActivo -> {
                binding.tvEstacionAlimentador.text = "DISPENSANDO"
                binding.tvEstacionAlimentador.setTextColor(ContextCompat.getColor(this, R.color.status_active))
                binding.tvEstacionAlimentadorSub.text = "Alimentando salmones"
            }
            data.alimentadorPospuesto -> {
                binding.tvEstacionAlimentador.text = "POSPUESTO"
                binding.tvEstacionAlimentador.setTextColor(ContextCompat.getColor(this, R.color.status_active))
                binding.tvEstacionAlimentadorSub.text = "Esperando que aireador pare"
            }
            else -> {
                binding.tvEstacionAlimentador.text = "${data.segundosParaAlimentar} s"
                binding.tvEstacionAlimentador.setTextColor(ContextCompat.getColor(this, R.color.white))
                binding.tvEstacionAlimentadorSub.text = "En cuenta regresiva"
            }
        }

        binding.tvEstacionDiagnostico.text = "[ESTADO RECIBIDO]\n${data.mensajeAlerta}"
        binding.tvUltimaActualizacion.text = "Activo"
    }
}
