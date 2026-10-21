# Monitoreo de Piscicultura (Salmonicultura) a Pequeña Escala - Prototipo IoT/OT

Aplicación móvil nativa en Android (Kotlin) para la asignatura **Aplicaciones Móviles para IoT**. Este sistema permite el monitoreo y control en tiempo real de estanques de cultivo de salmónidos mediante comunicación inalámbrica punto a punto **Bluetooth clásico (RFCOMM)** entre dos dispositivos físicos.

---

## 1. Problemática Resuelta

En la salmonicultura a pequeña escala, mantener condiciones estables de calidad de agua es crítico para la supervivencia del pez:
* **Temperatura del agua:** Debe situarse estrictamente entre **18.0 °C y 26.0 °C**. Temperaturas menores a 18 °C reducen drásticamente el metabolismo, y temperaturas mayores a 26 °C provocan estrés térmico y déficit crítico de oxígeno disuelto.
* **Turbidez:** No debe exceder el **60.0 %**, ya que sólidos suspendidos y sedimentos lesionan las branquias de los salmónidos y reducen la oxigenación.

### Simulación del Hardware y Roles (ESP32 vs Celular)
Para validar la arquitectura OT/IoT sin depender de componentes físicos durante la etapa de pruebas, la misma aplicación corre en dos smartphones con rol seleccionable:

1. **Rol NODO SENSOR (Simulador del ESP32):**
   * **Slider de Temperatura (0 a 40 °C):** Reemplaza y simula la lectura digital del sensor de temperatura **DHT22**.
   * **Slider de Turbidez (0 a 100 %):** Reemplaza y simula la lectura analógica de turbidez obtenida mediante un **potenciómetro**.
   * **Aireador:** Simula el actuador de oxigenación (**LED indicador** en el prototipo físico). Se enciende automáticamente si la temperatura cae por debajo de 18 °C, si supera los 26 °C, o si la turbidez supera el 60 %. Permite anulación manual (ON / OFF / AUTO).
   * **Alimentador Automático:** Simula la compuerta de dosificación de pellets accionada por un servomotor **SG90**.
     * **Regla estricta:** Cada **15 segundos** (`15000 ms`) intenta dispensar alimento.
     * **Interbloqueo de seguridad:** Solo dispensa si el **aireador está apagado**. Si el aireador está encendido, el alimentador se **pospone** de inmediato, sin reiniciar el temporizador de 15 segundos, y reintenta la verificación en cada ciclo de evaluación (**cada 2 segundos**) hasta que el aireador se apague.
   * **Servidor Bluetooth:** Aloja un socket servidor RFCOMM que transmite telemetría periódica cada 2 segundos.

2. **Rol ESTACIÓN DE MONITOREO (Dashboard de Supervisión OT):**
   * Actúa como cliente Bluetooth RFCOMM que se conecta al Nodo Sensor.
   * Muestra un panel con 4 tarjetas Material con los valores en vivo de temperatura, turbidez, estado de actuadores y cuenta regresiva sincronizada para la próxima alimentación.
   * Permite enviar comandos manuales hacia el estanque: **Forzar alimentación inmediata** y **Conmutar modo del aireador (AUTO, ON, OFF)**.

---

## 2. Justificación de la Arquitectura

* **Desarrollo Nativo en Kotlin con Material Design 3:** Ofrece control directo sobre las APIs de bajo nivel del sistema operativo (`BluetoothAdapter`, `BluetoothServerSocket`, `EncryptedSharedPreferences`), optimizando el rendimiento y proporcionando una interfaz moderna, accesible y de alta densidad visual.
* **Arquitectura MVVM con StateFlow y Corrutinas:**
  * Separación estricta entre la interfaz (`NodoActivity`, `EstacionActivity`) y la lógica de negocio/comunicación (`NodoViewModel`, `EstacionViewModel`).
  * Los sockets Bluetooth y las rutinas de lectura/escritura residen en los `ViewModel`, permitiendo que la rotación de pantalla **no destruya la conexión activa ni reinicie los contadores**.
  * Todas las operaciones de I/O, red y sockets corren fuera del hilo principal en `Dispatchers.IO`.
* **Bluetooth RFCOMM frente a Wi-Fi / MQTT:**
  * Las pisciculturas en ríos o zonas rurales carecen frecuentemente de routers, cobertura celular o infraestructura de red local.
  * Bluetooth clásico RFCOMM permite un enlace inalámbrico serial punto a punto, robusto, de muy bajo consumo energético, que **funciona 100 % sin conexión a Internet**.
* **Autenticación en la Nube con Firebase Auth y Persistencia Cifrada Local:**
  * Autenticación corporativa mediante Firebase Authentication (correo y contraseña) sobre canales seguros HTTPS/TLS.
  * Una vez autenticado, la sesión se guarda de forma segura con `EncryptedSharedPreferences` (MasterKey Keystore `AES256_GCM`) con validez de 24 horas.
  * Si el operario pierde conexión en terreno, la sesión local cifrada le permite seguir operando el estanque por Bluetooth sin requerir Internet.

---

## 3. Especificación del Protocolo Inalámbrico

* **Capa de Transporte:** Bluetooth Clásico (RFCOMM / Serial Port Profile SPP).
* **UUID de Servicio Fijo:** `00001101-0000-1000-8000-00805F9B34FB` (UUID estándar SPP).
* **Formato de Trama:** Texto JSON en una sola línea codificado en UTF-8 y terminado con el delimitador salto de línea `\n`.
* **Límite de Seguridad por Trama:** Máximo **2048 bytes (2 KB)** para prevenir desbordes o corrupción de memoria.
* **Frecuencia de Envío de Datos:** Cada **2000 ms** (`2 segundos`) desde el Nodo hacia la Estación.
* **Mecanismo de Heartbeat (Latido):**
  * La Estación/Nodo envía un pulso `{"type":"PING"}\n` cada **5000 ms** (`5 segundos`).
  * El receptor responde inmediatamente con `{"type":"PONG"}\n`.
  * Si transcurren más de **10000 ms** (`10 segundos`) sin recibir ningún paquete, se asume desconexión por pérdida de alcance y se libera el socket de manera segura.
* **Tolerancia a Fallos:** Toda trama corrupta o con formato JSON inválido es descartada silenciosamente sin provocar crasheos en la aplicación.

### Tabla de Mensajes del Protocolo

| Tipo (`type`) | Emisor | Receptor | Descripción | Estructura y Campos | Ejemplo JSON (`\n` al final) |
| :--- | :---: | :---: | :--- | :--- | :--- |
| `HELLO` | Ambos | Ambos | Saludo inicial al establecer el enlace | `role`: `"NODO"` o `"ESTACION"`<br>`user`: Identificador/Correo | `{"type":"HELLO","role":"NODO","user":"operador@salmon.cl"}` |
| `DATA` | Nodo | Estación | Telemetría periódica del estanque (cada 2s) | `ts`: Epoch ms<br>`temp`: Float (°C)<br>`turb`: Float (%)<br>`aireador`: Boolean<br>`alimentador`: Boolean<br>`proxAlimMs`: Long (ms restantes)<br>`pospuesto`: Boolean<br>`manual`: Boolean | `{"type":"DATA","ts":1771675200000,"temp":21.5,"turb":22.0,"aireador":false,"alimentador":false,"proxAlimMs":11000,"pospuesto":false,"manual":false}` |
| `CMD` (FEED) | Estación | Nodo | Orden de forzar alimentación | `cmd`: `"FEED"` | `{"type":"CMD","cmd":"FEED"}` |
| `CMD` (AIREADOR)| Estación | Nodo | Configurar modo del aireador | `cmd`: `"AIREADOR"`<br>`value`: `"ON"`, `"OFF"` o `"AUTO"` | `{"type":"CMD","cmd":"AIREADOR","value":"ON"}` |
| `ACK` | Nodo | Estación | Confirmación de ejecución de comando | `ref`: Comando referenciado<br>`ok`: Boolean (resultado de ejecución) | `{"type":"ACK","ref":"FEED","ok":true}` |
| `PING` | Ambos | Ambos | Pulso de verificación de enlace (cada 5s) | `type`: `"PING"` | `{"type":"PING"}` |
| `PONG` | Ambos | Ambos | Respuesta a pulso de verificación | `type`: `"PONG"` | `{"type":"PONG"}` |

---

## 4. Medidas de Seguridad (Alineadas a ISO 27400 - Seguridad en IoT)

1. **Autenticación Robusta sobre Canales Seguros (Cláusula de Control de Acceso):**
   * Registro y validación de usuarios delegados a Firebase Authentication mediante HTTPS/TLS con cifrado de transporte.
   * Validación estricta en cliente: contraseñas de al menos 8 caracteres con combinación obligatoria de letras y números.
2. **Cero Almacenamiento ni Emisión de Contraseñas (Protección de Credenciales):**
   * La contraseña **nunca** se almacena en `SharedPreferences`, ni en bases de datos locales, ni en bundles de guardado de estado, ni se imprime en logs (`Log.d`, `println`).
3. **Persistencia Cifrada con Android Keystore:**
   * Utilización de `EncryptedSharedPreferences` respaldado por `MasterKey` con esquema `AES256_GCM` para valores y `AES256_SIV` para llaves.
   * Solo se almacenan datos no sensibles: correo del operador autenticado y timestamp de inicio.
   * Política de caducidad estricta de **24 horas**. Al cerrar sesión, se ejecuta `FirebaseAuth.signOut()` y se borran todas las claves cifradas.
4. **Principio de Mínimo Privilegio y Manejo de Permisos en Runtime:**
   * Solicitud adaptativa de permisos según API: `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` (con bandera `neverForLocation`) y `BLUETOOTH_ADVERTISE` en Android 12+; `ACCESS_FINE_LOCATION` en versiones previas.
   * Diálogo explicativo previo (*Rationale*). Si el usuario rechaza los permisos, la aplicación **no se cierra**, sino que informa el estado con opción a reintentar o abrir Ajustes.
5. **Aislamiento y Protección contra Fugas de Datos:**
   * `android:allowBackup="false"` declarado en el `AndroidManifest.xml` para prevenir la extracción de datos mediante respaldos de ADB.
6. **Defensa en Profundidad en el Buffer RFCOMM:**
   * Validación del límite máximo de trama a 2 KB para prevenir ataques de desbordamiento de búfer o inyección de tramas continuas.

---

## 5. Instrucciones de Compilación y Ejecución

### Requisitos del Sistema
* **Android Studio:** Ladybug (2024.2.1) o superior.
* **Java Development Kit (JDK):** Versión 11 o 17.
* **Dispositivos de Prueba:** 2 teléfonos celulares físicos con Android 8.0 (API 26) o superior y soporte para Bluetooth.

### Paso a Paso para la Configuración

1. **Configuración del Proyecto en Firebase Console:**
   * Ve a [Firebase Console](https://console.firebase.google.com/) y crea un proyecto llamado `piscicultura-iot`.
   * En el menú lateral, ve a **Compilación > Authentication > Sign-in method**.
   * Habilita el proveedor **Correo electrónico/contraseña**.
   * Registra una aplicación Android con el paquete exacto: `cl.inacap.piscicultura`.
   * Descarga el archivo `google-services.json` y pégalo en la carpeta:
     ```text
     piscicultura/app/google-services.json
     ```

2. **Compilación del APK en Android Studio:**
   * Abre el proyecto en Android Studio y permite que Gradle sincronice las dependencias.
   * Ve al menú superior: **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
   * Localiza el APK generado en `app/build/outputs/apk/debug/app-debug.apk`.

3. **Emparejamiento Físico Previo por Bluetooth:**
   * En ambos celulares físicos, ve a **Ajustes de Android > Bluetooth**.
   * Activa el Bluetooth en ambos y realiza el **emparejamiento previo** entre los dos celulares (ingresando el código PIN de confirmación si lo solicita Android).

4. **Instalación y Despliegue:**
   * Instala el APK en ambos celulares físicos.
   * Inicia sesión o regístrate en ambos con un correo y contraseña válida.
   * **Celular 1:** Selecciona **"NODO SENSOR (ESP32)"** y pulsa **"Iniciar Servidor"**.
   * **Celular 2:** Selecciona **"ESTACIÓN DE MONITOREO"**, presiona **"Seleccionar dispositivo emparejado"**, elige el Celular 1 de la lista y pulsa **"Conectar"**.

*(Nota de Entrega: Las capturas de pantalla de la demostración van ubicadas en la carpeta `captures/` mostrando: Pantalla de Login, Selección de Rol, Pantalla del Nodo Sensor, Dashboard de la Estación y Flujo de Permisos).*

---

## 6. Limitaciones Conocidas y Mejoras Futuras (UA3)

* **Emulación de Sensores:** Actualmente el estanque se controla mediante sliders interactivos y temporizadores de corrutinas en el celular que corre como Nodo.
* **Integración Física con Microcontrolador ESP32 (Siguiente Unidad):** La versión final enlazará la Estación Android directamente con el microcontrolador ESP32 mediante su módulo Bluetooth Classic (`BluetoothSerial.h`), leyendo el sensor DHT22 real, sensor analógico de turbidez y controlando el servo SG90 y relé del aireador.
* **Enlace Cifrado sobre RFCOMM:** Aunque la sesión está cifrada con AES-256 en almacenamiento local, se proyecta incorporar cifrado asimétrico o intercambio Diffie-Hellman en el protocolo de mensajes RFCOMM para evitar escuchas no autorizadas en el medio físico.

---

## 7. Plan de Pruebas Manuales para Demostración en Sala

A continuación se detallan las 12 pruebas estructuradas para validar todas las funcionalidades del prototipo:

| N° | Caso de Prueba / Paso Realizado | Resultado Esperado |
| :---: | :--- | :--- |
| **1** | **Registro e inicio de sesión exitoso:** Ingresar correo con formato válido y clave de 8 caracteres con letra y número. Presionar "Crear Cuenta en Firebase". | Se crea la cuenta en Firebase Auth, se inicia sesión, se persiste la sesión cifrada y redirige a `RoleSelectActivity`. |
| **2** | **Login con credenciales incorrectas:** Ingresar correo existente con una contraseña errónea. Presionar "Iniciar Sesión". | No ingresa; se muestra Toast genérico *"Credenciales inválidas o cuenta no registrada. Verifique sus datos"* sin revelar cuál falló. |
| **3** | **Login sin conexión a Internet:** Desactivar Wi-Fi y datos móviles en la pantalla de login. Presionar "Iniciar Sesión". | La app no crashea; muestra mensaje de advertencia *"Sin conexión a Internet. Verifique su red móvil o Wi-Fi"* y permanece en la pantalla. |
| **4** | **Persistencia de sesión cifrada (Offline):** Habiendo iniciado sesión, cerrar la app completamente de la multitarea y volver a abrirla sin Internet. | La app detecta la sesión cifrada válida (< 24 h) en `EncryptedSharedPreferences` y abre directamente `RoleSelectActivity` sin pedir credenciales. |
| **5** | **Cierre de sesión seguro:** En `RoleSelectActivity`, pulsar el botón "Cerrar Sesión". | Se ejecuta `FirebaseAuth.signOut()`, se borra la clave y email en `EncryptedSharedPreferences`, y redirige a `LoginActivity` limpiando el stack de navegación. |
| **6** | **Rechazo inicial de permisos Bluetooth:** En `RoleSelectActivity`, seleccionar un rol y pulsar "Ahora no" o rechazar el permiso en el diálogo. | La app **no se cierra**; permanece abierta y muestra una tarjeta roja de advertencia con el botón "Reintentar Permisos". |
| **7** | **Rechazo permanente de permisos:** Rechazar permisos marcando "No volver a preguntar". | La tarjeta de advertencia muestra el botón "Abrir Ajustes", el cual abre directamente la configuración de permisos de la app en Ajustes de Android. |
| **8** | **Conexión Bluetooth en vivo entre ambos celulares:** En el Celular 1 (Nodo) iniciar servidor. En el Celular 2 (Estación) seleccionar Celular 1 y conectar. | El Celular 1 pasa a *"Conectado a Estación"*. El Celular 2 pasa a *"Conectado a Nodo"* (punto verde) y comienza a recibir telemetría cada 2 segundos. |
| **9** | **Comando de alimentación forzada:** En la Estación con aireador apagado, presionar "Forzar Alimentación Inmediata". | La Estación envía `{"type":"CMD","cmd":"FEED"}`. El Nodo activa el alimentador (3 s), envía telemetría de activación y la Estación muestra *"Alimentando ahora..."*. |
| **10** | **Interbloqueo y modo manual del aireador:** En la Estación, pulsar "MANUAL ON" del aireador. Luego presionar "Forzar Alimentación". | El aireador pasa a ACTIVADO (rojo). Al presionar forzar alimentación, el Nodo deniega la acción y la Estación muestra *"En espera (aireador activo)"* y estado POSPUESTO. |
| **11** | **Desconexión forzada por apagado de Bluetooth:** Estando conectados y transmitiendo datos, apagar el Bluetooth en el Celular 1 (Nodo). | La Estación no se bloquea ni crashea; detecta la caída por `IOException`/timeout, emite un Snackbar informando la desconexión, y deja disponible el botón para reconectar. |
| **12** | **Rotación de pantalla en ambos celulares:** Con la conexión Bluetooth activa y transmitiendo, rotar ambos celulares a modo horizontal y vertical. | Ningún celular se desconecta; los sliders del Nodo mantienen sus valores y el dashboard de la Estación sigue actualizándose sin interrupción. |
