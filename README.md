# Monitoreo de Piscicultura (Salmonicultura) a Pequeña Escala - Prototipo IoT/OT

Aplicación móvil nativa en Android (Kotlin) para la asignatura **Aplicaciones Móviles para IoT**. Este sistema permite el monitoreo y control en tiempo real de estanques de cultivo de salmónidos mediante comunicación inalámbrica punto a punto **Bluetooth clásico (RFCOMM)** entre dos dispositivos físicos.

---

## 1. Problemática Resuelta

En la salmonicultura a pequeña escala, mantener condiciones estables de calidad de agua es crítico para la supervivencia del pez:
* **Temperatura del agua:** Debe situarse estrictamente entre **18.0 °C y 26.0 °C**. Temperaturas menores a 18 °C reducen drásticamente el metabolismo, y temperaturas mayores a 26 °C provocan estrés térmico y déficit crítico de oxígeno disuelto.
* **Turbidez:** No debe exceder el **60.0 %**, ya que sólidos suspendidos y sedimentos lesionan las branquias de los salmónidos y reducen la oxigenación.

### Simulación del Hardware y Roles (ESP32 vs Celular)
Para validar la arquitectura OT/IoT sin depender de componentes físicos durante la etapa de pruebas, la misma aplicación corre en dos smartphones con rol seleccionable:

1. **Rol Estanque (sensor) [nodo sensor que simula el ESP32]:**
   * **Simulador de Sensores:** Reemplaza el microcontrolador ESP32.
     * **Slider de Temperatura (0 a 40 °C):** Reemplaza y simula la lectura digital del sensor de temperatura **DHT22**.
     * **Slider de Turbidez (0 a 100 %):** Reemplaza y simula la lectura analógica de turbidez obtenida mediante un **potenciómetro**.
   * **Equipos del Estanque (Actuadores simulados):**
     * **Aireador (Oxigenación):** Simula el actuador de oxigenación (**LED indicador** en el prototipo físico). Se enciende automáticamente si la temperatura cae por debajo de 18 °C, si supera los 26 °C, o si la turbidez supera el 60 %. Permite control manual desde el panel (Automático, Encender, Apagar).
     * **Alimentador Automático:** Simula la compuerta de dosificación de pellets accionada por un servomotor **SG90**.
       * **Regla de intervalo:** Cada **15 segundos** (`15000 ms`) intenta dispensar alimento.
       * **Interbloqueo de seguridad:** Solo dispensa si el **aireador está apagado**. Si el aireador está encendido, el alimentador se **pospone** de inmediato, sin reiniciar el temporizador de 15 segundos, y reintenta la verificación en cada ciclo de evaluación (**cada 2 segundos**) hasta que el aireador se apague.
   * **Servidor Bluetooth:** Aloja un socket servidor RFCOMM iniciado con el botón **"Iniciar Estanque"** que transmite telemetría periódica cada 2 segundos hacia el panel conectado.

2. **Rol Panel de control (estación de monitoreo):**
   * Actúa como cliente Bluetooth RFCOMM que se conecta al estanque.
   * Muestra un panel con 4 tarjetas con los valores en vivo de temperatura, turbidez, estado de equipos y cuenta regresiva sincronizada para la próxima comida.
   * Permite enviar órdenes directas hacia el estanque: **Dar alimento ahora** y **Cambiar modo del aireador (Automático, Encender, Apagar)**.
   * **Comportamiento sin conexión:** Si no hay conexión o no se han recibido datos, muestra valores neutros (`--.- °C`, `--.- %`, `SIN CONEXIÓN`) en color gris atenuado, el botón **Desconectar** aparece deshabilitado y sin borde rojo fuerte, y los controles remotos se atenúan (`alpha = 0.5f`).

---

## 2. Decisiones Técnicas

* **UUID Estándar SPP (`00001101-0000-1000-8000-00805F9B34FB`):**
  Se adoptó el UUID universal del perfil de puerto serie (Serial Port Profile - SPP) para garantizar máxima compatibilidad con el stack Bluetooth clásico de Android y asegurar una transición transparente en la Unidad 3, donde el panel se conectará directamente a la librería estándar `BluetoothSerial.h` de la placa ESP32 sin requerir drivers ni perfiles propietarios.
* **Seguridad en Firebase y Archivo de Configuración:**
  El archivo real `app/google-services.json` contiene identificadores del proyecto y **no se incluye en el repositorio por seguridad** (está registrado en `.gitignore`). Para desplegar el proyecto, el usuario debe generar su propio archivo en Firebase Console o utilizar el entregado por el docente/evaluador. En el repositorio se provee `app/google-services.json.example` únicamente como referencia de estructura.
* **Privacidad de Usuario en Pantalla:**
  Para salvaguardar la privacidad en terreno y entornos compartidos de trabajo, la pantalla de selección de rol muestra el saludo estático **"Hola, usuario"** y el estado **"Sesión activa y segura"**, sin proyectar correos electrónicos ni información sensible en la interfaz gráfica.
* **Cifrado Local con Android Keystore:**
  La sesión autenticada se preserva localmente mediante `EncryptedSharedPreferences` respaldado por `MasterKey` con esquema `AES256_GCM` para valores y `AES256_SIV` para llaves, con caducidad estricta de 24 horas.

---

## 3. Justificación de la Arquitectura

* **Desarrollo Nativo en Kotlin con Material Design 3:** Ofrece control directo sobre las APIs de bajo nivel del sistema operativo (`BluetoothAdapter`, `BluetoothServerSocket`, `EncryptedSharedPreferences`), optimizando el consumo de batería y la reactividad de la interfaz.
* **Arquitectura MVVM con StateFlow y Corrutinas:**
  * Separación estricta entre la interfaz (`NodoActivity`, `EstacionActivity`) y la lógica de negocio/comunicación (`NodoViewModel`, `EstacionViewModel`).
  * Los sockets Bluetooth y las rutinas de lectura/escritura residen en los `ViewModel`, permitiendo que la rotación de pantalla **no destruya la conexión activa ni reinicie los contadores**.
  * Todas las operaciones de I/O, red y sockets corren fuera del hilo principal en `Dispatchers.IO`.
* **Bluetooth RFCOMM frente a Wi-Fi / MQTT:**
  * Las pisciculturas en ríos o zonas rurales carecen frecuentemente de routers, cobertura celular o infraestructura de red local.
  * Bluetooth clásico RFCOMM permite un enlace inalámbrico serial punto a punto, robusto, de muy bajo consumo energético, que **funciona 100 % sin conexión a Internet**.
* **Autenticación en la Nube con Firebase Auth y Persistencia Cifrada Local:**
  * Autenticación corporativa mediante Firebase Authentication (correo y contraseña) sobre canales seguros HTTPS/TLS.
  * Una vez autenticado, la sesión se guarda de forma segura con `EncryptedSharedPreferences` con validez de 24 horas.
  * Si el usuario pierde conexión en terreno, la sesión local cifrada le permite seguir operando el estanque por Bluetooth sin requerir Internet.

---

## 4. Diseño y Usabilidad

La interfaz fue diseñada bajo principios de diseño centrado en el usuario (UCD) para ser operada con total claridad por personas sin formación informática previa:

* **Lenguaje Simple y Directo:** Se eliminó la jerga técnica innecesaria ("operador", "sistema", "actuadores"), reemplazándola por términos naturales ("Usuario", "Estanque (sensor)", "Panel de control", "Equipos de oxígeno y alimento").
* **Una Sola Acción Principal:** Cada pantalla guía la atención del usuario mediante un botón principal prominente ("Ingresar", "Iniciar Estanque", "Conectar") y acciones secundarias visualmente diferenciadas (Outlined/Text buttons).
* **Semáforo Visual Intuitivo:**
  * **Verde:** Condiciones óptimas en el agua (temperatura 18 a 26 °C, turbidez &le; 60 %) y equipos en reposo normal.
  * **Rojo:** Alerta que requiere atención (temperatura fuera de rango, agua turbia, aireador encendido o dispensando alimento).
  * **Azul Informativo:** Banners de espera y estado de sincronización (`bg_banner_info`), reservando los colores de advertencia exclusivamente para situaciones anómalas.
* **Representación Honesta del Estado Desconectado:**
  Si el panel de control no está conectado al estanque o aún no recibe telemetría, las tarjetas muestran valores neutros (`--.- °C`, `--.- %`, `SIN CONEXIÓN`) en color gris tenue. No se muestran falsos estados verdes, ni modos automáticos, ni cuentas regresivas simuladas. El botón **Desconectar** se visualiza deshabilitado (`alpha = 0.4f`, borde gris neutro) y los botones de control remoto se atenúan (`alpha = 0.5f`) y deshabilitan hasta restablecer el enlace.
* **Soporte Completo de Tema Claro y Oscuro con Barras del Sistema Adaptativas:**
  Configuración integral en `values/themes.xml` y `values-night/themes.xml` mediante `android:windowLightStatusBar` y `android:windowLightNavigationBar`, garantizando íconos oscuros sobre fondo claro en modo diurno e íconos claros sobre fondo oscuro en modo nocturno, con total legibilidad y respeto de insets.

---

## 5. Especificación del Protocolo Inalámbrico

* **Capa de Transporte:** Bluetooth Clásico (RFCOMM / Serial Port Profile SPP).
* **UUID de Servicio Fijo:** `00001101-0000-1000-8000-00805F9B34FB` (UUID estándar SPP).
* **Formato de Trama:** Texto JSON en una sola línea codificado en UTF-8 y terminado con el delimitador salto de línea `\n`.
* **Límite de Seguridad por Trama:** Máximo **2048 bytes (2 KB)** para prevenir desbordes o corrupción de memoria.
* **Frecuencia de Envío de Datos:** Cada **2000 ms** (`2 segundos`) desde el Estanque hacia el Panel.
* **Mecanismo de Heartbeat (Latido):**
  * El Panel o Estanque envía un pulso `{"type":"PING"}\n` cada **5000 ms** (`5 segundos`).
  * El receptor responde inmediatamente con `{"type":"PONG"}\n`.
  * Si transcurren más de **10000 ms** (`10 segundos`) sin recibir ningún paquete, se asume desconexión por pérdida de alcance y se libera el socket de manera segura.
* **Tolerancia a Fallos:** Toda trama corrupta o con formato JSON inválido es descartada silenciosamente sin provocar crasheos en la aplicación.

### Tabla de Mensajes del Protocolo

| Tipo (`type`) | Emisor | Receptor | Descripción | Estructura y Campos | Ejemplo JSON (`\n` al final) |
| :--- | :---: | :---: | :--- | :--- | :--- |
| `HELLO` | Ambos | Ambos | Saludo inicial al establecer el enlace | `role`: `"NODO"` o `"ESTACION"`<br>`user`: Identificador/Correo | `{"type":"HELLO","role":"NODO","user":"usuario@salmon.cl"}` |
| `DATA` | Estanque | Panel | Telemetría periódica del estanque (cada 2s) | `ts`: Epoch ms<br>`temp`: Float (°C)<br>`turb`: Float (%)<br>`aireador`: Boolean<br>`alimentador`: Boolean<br>`proxAlimMs`: Long (ms restantes)<br>`pospuesto`: Boolean<br>`manual`: Boolean | `{"type":"DATA","ts":1771675200000,"temp":21.5,"turb":22.0,"aireador":false,"alimentador":false,"proxAlimMs":11000,"pospuesto":false,"manual":false}` |
| `CMD` (FEED) | Panel | Estanque | Orden de forzar alimentación | `cmd`: `"FEED"` | `{"type":"CMD","cmd":"FEED"}` |
| `CMD` (AIREADOR)| Panel | Estanque | Configurar modo del aireador | `cmd`: `"AIREADOR"`<br>`value`: `"ON"`, `"OFF"` o `"AUTO"` | `{"type":"CMD","cmd":"AIREADOR","value":"ON"}` |
| `ACK` | Estanque | Panel | Confirmación de ejecución de comando | `ref`: Comando referenciado<br>`ok`: Boolean (resultado de ejecución) | `{"type":"ACK","ref":"FEED","ok":true}` |
| `PING` | Ambos | Ambos | Pulso de verificación de enlace (cada 5s) | `type`: `"PING"` | `{"type":"PING"}` |
| `PONG` | Ambos | Ambos | Respuesta a pulso de verificación | `type`: `"PONG"` | `{"type":"PONG"}` |

---

## 6. Medidas de Seguridad (Alineadas a ISO 27400 - Seguridad en IoT)

1. **Autenticación Robusta sobre Canales Seguros (Control de Acceso):**
   * Registro y validación de usuarios delegados a Firebase Authentication mediante HTTPS/TLS con cifrado de transporte.
   * Validación estricta en cliente: contraseñas de al menos 8 caracteres con combinación obligatoria de letras y números.
2. **Cero Almacenamiento ni Emisión de Contraseñas (Protección de Credenciales):**
   * La contraseña **nunca** se almacena en `SharedPreferences`, ni en bases de datos locales, ni en bundles de guardado de estado, ni se imprime en logs (`Log.d`, `println`).
3. **Persistencia Cifrada con Android Keystore:**
   * Utilización de `EncryptedSharedPreferences` respaldado por `MasterKey` con esquema `AES256_GCM` para valores y `AES256_SIV` para llaves.
   * Solo se almacenan datos no sensibles: correo del usuario autenticado y timestamp de inicio.
   * Política de caducidad estricta de **24 horas**. Al cerrar sesión, se ejecuta `FirebaseAuth.signOut()` y se borran todas las claves cifradas.
4. **Principio de Mínimo Privilegio y Manejo de Permisos en Runtime:**
   * Solicitud adaptativa de permisos según API: `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` (con bandera `neverForLocation`) y `BLUETOOTH_ADVERTISE` en Android 12+; `ACCESS_FINE_LOCATION` en versiones previas.
   * Diálogo explicativo previo (*Rationale*). Si el usuario rechaza los permisos, la aplicación **no se cierra**, sino que informa el estado con opción a reintentar o abrir Ajustes.
5. **Aislamiento y Protección contra Fugas de Datos:**
   * `android:allowBackup="false"` declarado en el `AndroidManifest.xml` para prevenir la extracción de datos mediante respaldos de ADB.
6. **Defensa en Profundidad en el Buffer RFCOMM:**
   * Validación del límite máximo de trama a 2 KB para prevenir ataques de desbordamiento de búfer o inyección de tramas continuas.

---

## 7. Instrucciones de Compilación y Ejecución

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
   * Descarga el archivo `google-services.json` y colócalo en la raíz del módulo `app/`:
     ```text
     app/google-services.json
     ```
     *(Nota: este archivo está en `.gitignore` por seguridad; si no dispones de uno propio, revisa `app/google-services.json.example` como referencia de su estructura).*

2. **Compilación del APK en Android Studio:**
   * Abre el proyecto en Android Studio y permite que Gradle sincronice las dependencias.
   * Ve al menú superior: **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
   * Localiza el APK generado en `app/build/outputs/apk/debug/app-debug.apk`.

3. **Emparejamiento Físico Previo por Bluetooth:**
   * En **AMBOS** celulares físicos, activa el Bluetooth y empareja uno con el otro desde **Ajustes > Bluetooth** (solo se realiza una sola vez).

4. **Instalación y Despliegue:**
   * Instala el APK en ambos celulares físicos.
   * Abre la app en los dos celulares e inicia sesión o crea tu cuenta.
   * **Celular 1:** Selecciona **"Estanque (sensor)"** y pulsa **"Iniciar Estanque"**.
   * **Celular 2:** Selecciona **"Panel de control"**, pulsa **"Seleccionar dispositivo emparejado"**, elige el celular del estanque de la lista y pulsa **"Conectar"**. ¡Listo!

---

## 8. Capturas de Pantalla

Las evidencias visuales del prototipo en funcionamiento se encuentran organizadas en la carpeta `captures/`:

* `captures/01_login.png`: Pantalla de inicio de sesión e ingreso seguro ("Ingresar", "Crear cuenta") con validaciones amigables.
* `captures/02_seleccion_rol.png`: Pantalla de selección de rol ("Estanque (sensor)" vs "Panel de control") con saludo "Hola, usuario" y guía paso a paso desplegable.
* `captures/03_nodo.png`: Pantalla del Estanque (sensor) simulando temperatura y turbidez con sliders interactivos, estado de equipos y botón "Iniciar Estanque".
* `captures/04_estacion.png`: Dashboard del Panel de control (estación) con semáforo visual, telemetría en tiempo real y controles a distancia ("Dar alimento ahora", modo aireador).
* `captures/05_permisos.png`: Flujo transparente de solicitud, rationale y manejo tolerante de permisos Bluetooth en Android.
* `captures/06_modo_claro.png`: Demostración del tema claro con barras del sistema con íconos oscuros nítidos (`windowLightStatusBar`, `windowLightNavigationBar`) y alto contraste.

---

## 9. Limitaciones Conocidas y Mejoras Futuras (UA3)

* **Emulación de Sensores:** Actualmente el estanque se controla mediante sliders interactivos y temporizadores de corrutinas en el celular que corre como Estanque.
* **Integración Física con Microcontrolador ESP32 (Siguiente Unidad):** La versión final enlazará el Panel de control Android directamente con el microcontrolador ESP32 mediante su módulo Bluetooth Classic (`BluetoothSerial.h`), leyendo el sensor DHT22 real, sensor analógico de turbidez y controlando el servo SG90 y relé del aireador.
* **Enlace RFCOMM y Saludo Inicial sin Cifrado en Capa de Aplicación:** Aunque la sesión local está cifrada con AES-256 en Android Keystore y las conexiones con Firebase viajan sobre TLS, el saludo `HELLO` y las tramas Bluetooth RFCOMM entre ambos dispositivos viajan en texto claro (JSON) sobre el perfil SPP estándar. Para la Unidad 3 se proyecta incorporar cifrado asimétrico o intercambio de claves Diffie-Hellman en la capa de aplicación.

---

## 10. Plan de Pruebas Manuales para Demostración en Sala

A continuación se detallan las 12 pruebas estructuradas para validar todas las funcionalidades del prototipo:

| N° | Caso de Prueba / Paso Realizado | Resultado Esperado |
| :---: | :--- | :--- |
| **1** | **Registro e inicio de sesión exitoso:** Ingresar correo con formato válido y clave de 8 caracteres con letra y número. Presionar "Crear Cuenta". | Se crea la cuenta en Firebase Auth, se inicia sesión, se persiste la sesión cifrada y redirige a la pantalla de selección de rol. |
| **2** | **Login con credenciales incorrectas:** Ingresar correo existente con una contraseña errónea. Presionar "Ingresar". | No ingresa; se muestra mensaje genérico *"Correo o contraseña incorrectos. Revisa tus datos e intenta de nuevo"* sin revelar cuál campo falló. |
| **3** | **Login sin conexión a Internet:** Desactivar Wi-Fi y datos móviles en la pantalla de login. Presionar "Ingresar". | La app no crashea; muestra advertencia *"Sin conexión a Internet. Revisa tu red Wi-Fi o datos móviles para ingresar"* y permanece en la pantalla. |
| **4** | **Persistencia de sesión cifrada (Offline):** Habiendo iniciado sesión, cerrar la app completamente de la multitarea y volver a abrirla sin Internet. | La app detecta la sesión cifrada válida (< 24 h) en `EncryptedSharedPreferences` y abre directamente la pantalla de selección de rol sin pedir credenciales. |
| **5** | **Cierre de sesión seguro:** En la pantalla de selección de rol, pulsar el botón "Cerrar sesión". | Se ejecuta `FirebaseAuth.signOut()`, se borra la clave y correo en `EncryptedSharedPreferences`, y redirige a `LoginActivity` limpiando el stack de navegación. |
| **6** | **Rechazo inicial de permisos Bluetooth:** En la selección de rol, seleccionar un rol y pulsar "Ahora no" o rechazar el permiso en el diálogo. | La app **no se cierra**; permanece abierta y muestra una tarjeta de advertencia amigable con el botón "Dar permiso". |
| **7** | **Rechazo permanente de permisos:** Rechazar permisos marcando "No volver a preguntar". | La tarjeta de advertencia muestra el botón "Abrir ajustes", el cual abre directamente la configuración de permisos de la app en Ajustes de Android. |
| **8** | **Conexión Bluetooth en vivo entre ambos celulares:** En el Celular 1 (Estanque) pulsar "Iniciar Estanque". En el Celular 2 (Panel) presionar "Seleccionar dispositivo emparejado", elegir Celular 1 y presionar "Conectar". | El Celular 1 pasa a *"Conectado con Panel"*. El Celular 2 pasa a *"Conectado"* (punto verde) y comienza a recibir telemetría cada 2 segundos. |
| **9** | **Comando de alimentación a distancia:** En el Panel con aireador apagado, presionar "Dar alimento ahora". | El Panel envía `{"type":"CMD","cmd":"FEED"}`. El Estanque activa el alimentador (3 s), envía telemetría de activación y el Panel muestra *"Dispensando alimento..."*. |
| **10** | **Interbloqueo y modo manual del aireador:** En el Panel, pulsar "Encender" del aireador. Luego presionar "Dar alimento ahora". | El aireador pasa a ENCENDIDO (rojo). Al presionar el alimento, el Estanque pospone la acción y el Panel muestra *"En espera (aireador encendido)"* y estado POSPUESTO. |
| **11** | **Desconexión y estado neutral:** Estando conectados y transmitiendo datos, presionar "Desconectar" o apagar el Bluetooth en el Estanque. | El Panel no se bloquea ni crashea; detecta la desconexión, emite un aviso, muestra las tarjetas en estado neutro gris (`--.- °C`, `--.- %`, `SIN CONEXIÓN`), el botón "Desconectar" pasa a deshabilitado atenuado y los controles remotos se atenúan a `alpha = 0.5f`. |
| **12** | **Rotación de pantalla en ambos celulares:** Con la conexión Bluetooth activa y transmitiendo, rotar ambos celulares a modo horizontal y vertical. | Ningún celular se desconecta; los sliders del Estanque mantienen sus valores y el dashboard del Panel sigue actualizándose sin interrupción. |
