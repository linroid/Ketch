<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="art/icon-white.svg">
    <source media="(prefers-color-scheme: light)" srcset="art/icon-app.svg">
    <img alt="Ketch" src="art/icon-app.svg" height="128">
  </picture>
</p>

<h1 align="center">Ketch</h1>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="README.zh-CN.md">简体中文</a> ·
  <b>Español</b> ·
  <a href="README.ja.md">日本語</a> ·
  <a href="README.de.md">Deutsch</a> ·
  <a href="README.fr.md">Français</a>
</p>

<p align="center">
  <b>Un gestor de descargas rápido y de código abierto para todos tus dispositivos.</b><br>
  macOS · Windows · Linux · Android · iOS · Web · Línea de comandos<br>
  Una biblioteca Kotlin Multiplatform que puedes integrar en tu propia aplicación.
</p>

<p align="center">

[![Última versión](https://img.shields.io/github/v/release/linroid/Ketch?include_prereleases&filter=v*&label=Download&logo=github)](https://github.com/linroid/Ketch/releases/latest)
[![Maven Central](https://img.shields.io/maven-central/v/com.linroid.ketch/core?label=Maven%20Central&logo=apache-maven&logoColor=white)](https://central.sonatype.com/namespace/com.linroid.ketch)
[![Aplicación web](https://img.shields.io/badge/Web_app-open-4F5DE4.svg?logo=webassembly&logoColor=white)](https://linroid.com/Ketch/)
[![Android](https://img.shields.io/badge/Android-8.0+-3DDC84.svg?logo=android&logoColor=white)](https://github.com/linroid/Ketch/releases/latest)
[![iOS](https://img.shields.io/badge/iOS-18+-000000.svg?logo=apple&logoColor=white)](app/ios/)
[![Escritorio](https://img.shields.io/badge/Desktop-macOS_|_Windows_|_Linux-DB380E.svg)](https://github.com/linroid/Ketch/releases/latest)
[![Licencia](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

</p>

<p align="center">
  <a href="#download"><b>Descargar</b></a> ·
  <a href="https://youtu.be/l8RCUYQfRrc"><b>Ver demo</b></a> ·
  <a href="#features"><b>Funciones</b></a> ·
  <a href="#getting-started"><b>Primeros pasos</b></a> ·
  <a href="docs/developers.md"><b>Para desarrolladores</b></a>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="art/showcase-dark.png">
    <source media="(prefers-color-scheme: light)" srcset="art/showcase-light.png">
    <img
      alt="Ketch en escritorio, Android, iOS y la línea de comandos: tabla de descargas con canales de conexión en tiempo real, dispositivos en Android, conexiones de una descarga en iOS y ketch en una terminal"
      src="art/showcase-light.png" width="100%">
  </picture>
</p>

<p align="center">
  <a href="https://youtu.be/l8RCUYQfRrc"><b>▶ Ver Ketch en acción</b></a>
</p>

Ketch divide cada descarga entre conexiones paralelas y muestra cada una como un canal en tiempo
real. Reúne enlaces web, servidores FTP, torrents y enlaces magnet en un solo lugar. Vincula tu
portátil, teléfono y servidor doméstico para ver y controlar las descargas de todos desde cualquiera
de ellos.

**¿Estás creando tu propia aplicación?** Integra el mismo motor que usan las aplicaciones de Ketch,
con descargas paralelas, pausa y reanudación, colas y límites de velocidad. La biblioteca Kotlin
Multiplatform está disponible en Maven Central.
[Empieza a integrar Ketch →](docs/developers.md#quick-start)

> [!WARNING]
> 🚧 Ketch está en desarrollo activo y por ahora solo tiene versiones candidatas. Puedes encontrar
> problemas; por favor, [cuéntanos lo que encuentres](https://github.com/linroid/Ketch/issues).

<a id="download"></a>

## Descargar

| Plataforma | Cómo obtenerlo |
|---|---|
| macOS (Apple silicon, Intel) | `.dmg` de la [última versión][release] |
| Windows (x64, ARM64) | `.msi` o `.zip` portátil sin instalación de la [última versión][release] ([sobre la versión portátil](docs/updates.md#the-portable-windows-app)) |
| Linux (x64, ARM64) | `.deb` de la [última versión][release] |
| Android 8.0+ | `.apk` de la [última versión][release] |
| iOS 18+ | Compila desde el código fuente con Xcode ([`app/ios`](app/ios/)) |
| Web | [linroid.com/Ketch](https://linroid.com/Ketch/), para controlar Ketch en otro dispositivo |
| Extensión del navegador | Chrome, Edge, Brave, Firefox y otros: `.zip` de la [última versión][release] ([instalación](app/browser-extension/README.md#installing)) |
| Línea de comandos y servidor | macOS, Linux y Windows: [script de instalación](#run-ketch-on-a-server) o la [última versión][release] |
| Docker | NAS y servidores domésticos x64 y ARM64: `ghcr.io/linroid/ketch` ([guía](docs/docker.md), con TrueNAS y fnOS) |

Las aplicaciones de escritorio incluyen su entorno de ejecución y la herramienta de línea de
comandos es un único binario nativo: ninguna necesita Java. Ambas se actualizan desde las versiones
publicadas aquí: la aplicación desde Ajustes → Acerca de y la línea de comandos con `ketch update`
([cómo funcionan las actualizaciones](docs/updates.md)).

[release]: https://github.com/linroid/Ketch/releases/latest

<a id="features"></a>

## Funciones

### Descargas más rápidas

- **Conexiones paralelas** — Ketch divide un archivo en rangos y los descarga a la vez. La pestaña
  Conexiones muestra cada conexión como un canal con su propia velocidad; puedes añadir o quitar
  conexiones durante la descarga.
- **Varias redes a la vez** — Reparte una descarga entre Wi-Fi, Ethernet y, en Android, datos
  móviles ([cómo funciona](docs/multiple-networks.md)).
- **Pausa y reanudación, incluso tras reiniciar** — Ketch comprueba que el archivo del servidor
  no haya cambiado antes de continuar y reintenta automáticamente las conexiones fallidas.

### Todo tipo de enlaces

- **HTTP y HTTPS**, con las cookies y el referente de la página cuando la extensión del navegador
  envía la descarga.
- **FTP y FTPS**, con conexiones paralelas y reanudación.
- **BitTorrent y enlaces magnet** — Torrents v1, v2 e híbridos, con selección de archivos y un
  motor escrito íntegramente en Kotlin ([detalles](docs/torrent.md)).
- **HLS (`.m3u8`) y DASH (`.mpd`)** — Guarda flujos de duración finita y sin cifrar como un único
  archivo multimedia. Las listas maestras HLS usan la variante de mayor ancho de banda
  ([compatibilidad y límites](docs/media.md)).
- Ketch puede abrir los enlaces magnet y archivos `.torrent` del sistema: un clic en el navegador
  o el gestor de archivos los lleva a Ketch.

### Todos tus dispositivos en una aplicación

- **Vinculación por QR** — En el dispositivo que quieras compartir, abre **Ajustes → Compartir** y
  elige **Permitir otro dispositivo**; escanea el código con el teléfono o copia el enlace de
  vinculación. También puedes elegirlo en **Buscar en la red** y autorizar la conexión cuando ambos
  muestren los mismos cuatro dígitos.
- **Cada dispositivo en la barra lateral**, con su velocidad y estado en tiempo real. Cambia entre
  ellos con un atajo o abre **Todos los dispositivos** para ver todas las descargas en una tabla.
- **Envía las descargas al dispositivo adecuado** — Añade un enlace a cualquier dispositivo,
  arrástralo sobre uno en la barra lateral o envía o mueve una descarga a otro sin cambiar de vista.
- **Sin interfaz gráfica en un NAS o servidor** — `ketch server`, también como
  [imagen Docker](docs/docker.md), ejecuta el mismo motor con una API REST y la aplicación web
  integradas; las aplicaciones lo encuentran en tu red.
- **Desde la terminal** — `ketch add`, `list`, `pause`, `resume` y `watch` gestionan las descargas
  de la aplicación o de un servidor desde una terminal o un script; `watch` emite líneas JSON
  ([CLI](cli/README.md#work-on-a-running-ketch)).

### Controla tu ancho de banda

- **Modos de velocidad** — Velocidad máxima, **Modo lento** para dejar espacio a una videollamada o
  **Automático**, que alterna entre ambos según un horario semanal.
- **Control por descarga** — Cambia límites de velocidad, conexiones y prioridades durante la
  descarga. **Urgente** pausa una descarga menos importante para empezar de inmediato.
- **Una cola a tu medida** — Limita las descargas simultáneas y por sitio, o programa una descarga
  para la hora que elijas.

### Pensado para tu forma de trabajar

- **Extensión del navegador** para Chrome, Edge, Brave, Firefox y otros: captura descargas y enlaces
  magnet y añade **Descargar con Ketch** al menú contextual, para este equipo u otro dispositivo
  ([detalles](app/browser-extension/README.md)).
- **Pega para descargar** — Pega un enlace para añadirlo, con opción de deshacer, o deja que Ketch
  sugiera descargar los enlaces que copias.
- **Accesible con el teclado** en escritorio: `⌘K` (`Ctrl+K` en Windows y Linux) abre la paleta de
  comandos, y la lista de atajos muestra todas las combinaciones.
- **Listo para abrir al terminar** — Abre el archivo, muéstralo en su carpeta o arrástralo fuera,
  directamente desde la lista.
- **Integrado en cada plataforma** — Barra de menús o bandeja, notificaciones y progreso en el Dock
  y la barra de tareas en escritorio; descargas en segundo plano en Android y en iOS 26.
- **Activo mientras descarga** — Las apps de escritorio y Android evitan que el sistema entre en
  reposo por sí mismo mientras hay descargas en curso; la pantalla puede apagarse igualmente
  (**Ajustes → General**).
- **Tu estilo y tu idioma** — Temas claro y oscuro con cuatro colores de acento, en English,
  简体中文, 繁體中文, 日本語, 한국어, Español, Português (Brasil), Deutsch y Français
  ([traducciones](docs/development/localization.md)).

### Encuentra descargas con IA (versión preliminar)

- **Descubrir** — Describe lo que buscas, como «la última ISO de Ubuntu Server», y un agente de IA
  busca en la web, comprueba los enlaces y ordena las descargas encontradas. Si una descarga falla,
  puedes **Buscar otra fuente** del mismo modo. Afina la búsqueda con mensajes de seguimiento,
  descarta lo que no quieras y retoma búsquedas desde el historial. Descubrir pide permiso antes de
  abrir un sitio web, salvo que ya lo hayas autorizado. Usa tu propio modelo: OpenAI, Anthropic,
  Gemini, Ollama o cualquier servicio compatible con OpenAI. Disponible en las aplicaciones de
  escritorio y Android y con `ketch ai-discover` ([configuración](docs/ai-discovery.md)).
- **Servidor MCP** — `ketch mcp` permite a los asistentes de IA iniciar, observar y gestionar las
  descargas de la aplicación o de un servidor mediante el
  [Model Context Protocol](cli/README.md#mcp-server).

<a id="getting-started"></a>

## Primeros pasos

1. Instala Ketch desde [Descargar](#download) y ábrelo. En teléfonos, las pantallas de bienvenida
   te preguntan dónde guardar las descargas.
2. Añade una descarga: pega un enlace (`⌘V` o `Ctrl+V`), arrastra un enlace o un archivo `.torrent`
   a la ventana, o elige **Añadir**.
3. Para controlar el ordenador desde el teléfono, abre **Ajustes → Compartir**, elige
   **Permitir otro dispositivo** y escanea el código QR con la cámara del teléfono.
4. Instala la [extensión del navegador](app/browser-extension/README.md) para enviar las descargas
   del navegador a Ketch.

<a id="run-ketch-on-a-server"></a>

### Ejecutar Ketch en un servidor

Instala la herramienta de línea de comandos en macOS o Linux (en Windows, descarga el `.zip` de la
[última versión][release]):

```bash
curl -fsSL https://raw.githubusercontent.com/linroid/Ketch/main/install.sh | bash
```

Inicia el servidor, con su API REST y aplicación web en el puerto 8642:

```bash
ketch server
```

Añádelo en las aplicaciones desde **Dispositivos → Añadir dispositivo**, o abre
`http://<dirección del servidor>:8642` en un navegador. La línea de comandos también descarga por sí
sola:

```bash
ketch https://example.com/file.zip
```

Configura el código de acceso, el puerto y otras opciones en un
[archivo de configuración](cli/README.md#configuration-file);
la [documentación de la CLI](cli/README.md) enumera todos los comandos.

En un NAS, o donde funcione Docker, inicia la imagen en su lugar. La
[guía de Docker](docs/docker.md) describe sus ajustes e incluye los pasos para TrueNAS y fnOS:

```bash
docker run -d --name ketch --restart unless-stopped -e PUID=1000 -e PGID=1000 \
  -v /srv/ketch:/config -v /srv/downloads:/downloads \
  -p 8642:8642 -p 16881:16881 -p 16881:16881/udp ghcr.io/linroid/ketch
```

## Hoja de ruta

- **Distribución antes de v1.0.0** — Versiones firmadas para Windows, firmadas y notarizadas para
  macOS, extensiones en las tiendas de navegadores y procesos de instalación y actualización
  verificados ([plan](docs/plans/v1-distribution.md))
- **Metalink** — Descargas desde varios servidores espejo a la vez, con sumas de comprobación
- **WebDAV** — Descargas desde servidores WebDAV, con reanudación
- **Más formatos HLS y DASH** — Elegir calidad, combinar pistas separadas de audio y vídeo y
  admitir cifrado HLS AES-128; ya se admiten descargas de un único flujo finito sin cifrar
- **Extracción multimedia** — Guardar contenido multimedia de páginas web
- **Detector de recursos** — Encontrar los archivos descargables de una página web
- **Sumas de comprobación** — Verificar una descarga con un hash que proporciones o publique el
  servidor
- **Proxy** — Descargar mediante un proxy HTTP, SOCKS5 o del sistema, con una lista de excepciones
- **Descargas segmentadas más rápidas** — Las conexiones que terminan antes asumen lo que les queda
  a las más lentas para no esperar a la conexión más lenta
- **Detección de bloqueos y ajustes de reintento** — Reconectar si una conexión deja de enviar datos
  y elegir tiempos de espera y número de reintentos, incluidos reintentos ilimitados
- **Archivos incompletos reconocibles** — Usar un nombre temporal durante la descarga y asignar el
  definitivo al terminar
- **Carpetas por categoría** — Guardar vídeos, música, documentos y archivos comprimidos en sus
  propias carpetas según tus reglas
- **Elegir archivos de un torrent en cualquier momento** — Seleccionarlos cuando lleguen los
  detalles de un enlace magnet y cambiar la selección durante la descarga
- **Al terminar las descargas** — Opcionalmente, cerrar Ketch, suspender o apagar el equipo cuando
  la cola quede vacía
- **Automatización** — Ejecutar un comando o llamar a un webhook cuando una descarga termine o falle
- **Transferencias entre dispositivos** — Enviar a y Mover a transfieren los datos ya descargados
  para que el otro dispositivo continúe en lugar de empezar de cero
  ([plan](docs/plans/task-transfer.md))
- **Dispositivos auxiliares** — Otros dispositivos descargan partes del mismo archivo con su propia
  conexión y pueden añadirse o retirarse durante la descarga
  ([propuesta](docs/design/multi-instance-downloads.md))

## Para desarrolladores

**Integra Ketch en tu aplicación.** Su biblioteca Kotlin Multiplatform ofrece el mismo motor que
usan las aplicaciones de Ketch, con tu propia interfaz y lógica. Añade los módulos que necesites
desde Maven Central bajo `com.linroid.ketch`.

Ejecuta el motor dentro de tu aplicación Android, iOS o JVM con `core` y `ktor`; Node.js y WASI
pueden usar `core` con un motor HTTP personalizado. También puedes usar `remote` para controlar un
servidor Ketch desde Android, iOS, la JVM o el navegador mediante la misma `KetchApi`.

- [Guía para desarrolladores](docs/developers.md) — Módulos, inicio rápido, API REST y extensiones
- [Referencia de la API](docs/api.md) — Instalación, configuración, prioridades, errores y registros
- [Arquitectura](docs/architecture.md) — Flujo de descarga y estructura de las aplicaciones

## Documentación

La documentación detallada enlazada a continuación está en inglés.

- [Línea de comandos](cli/README.md) — Descargas, servidor, MCP, descubrimiento con IA y
  configuración
- [Extensión del navegador](app/browser-extension/README.md) — Configuración, captura, permisos y
  privacidad
- [BitTorrent](docs/torrent.md) — Compatibilidad y límites de torrents y enlaces magnet
- [Varias redes](docs/multiple-networks.md) — Repartir descargas entre interfaces de red
- [Descubrimiento con IA](docs/ai-discovery.md) — Proveedores, claves, búsqueda web, acceso a
  páginas,
  chats e historial
- [Registros](docs/logging.md) — Dónde encontrarlos y qué adjuntar a un informe de error

## Contribuir

¡Tus contribuciones son bienvenidas! Abre una incidencia para comentar tu idea antes de enviar un
PR.
Consulta las [reglas de estilo](docs/development/code-style.md),
las [reglas de pruebas](docs/development/testing.md) y la
[guía de localización](docs/development/localization.md); las traducciones se envían mediante PR.

Los agentes de programación deben empezar por [AGENTS.md](AGENTS.md), las instrucciones compartidas
por todos los agentes y editores. Si tu herramienta no lo carga automáticamente, pídele que lea
`AGENTS.md` y sus reglas enlazadas antes de hacer cambios. Mantén las instrucciones compartidas en
esos archivos; los puntos de entrada de cada herramienta solo deben enlazarlos.

## Licencia

Apache-2.0
