# Unichat

Español · [English](README.en.md)

Bandeja de mensajes unificada para escritorio — **WhatsApp, Telegram, Signal, Instagram, Messenger,
Discord, Slack, Google Messages, X y LinkedIn en una sola app** — hecha en **Java 21 + JavaFX** para
Windows y macOS (Linux, compilándola). Inspirada en Beeper (misma idea y flujo de trabajo); el código es propio.

![Unichat en modo oscuro](docs/screenshots/oscuro.png)

| Claro | Paleta de comandos | Conectar una red |
|---|---|---|
| ![](docs/screenshots/claro.png) | ![](docs/screenshots/comandos.png) | ![](docs/screenshots/conectar-red.png) |

## Instalar

Descarga la última versión en **[Releases](https://github.com/vaoan/unichat/releases/latest)**. No necesita Java:
viene dentro.

| Sistema | Archivo | Servidor local incluido |
|---|---|---|
| Windows 10/11 (x64) | `Unichat-<versión>.msi` (instalador) o `Unichat-<versión>-windows.zip` (portable: descomprime y abre `Unichat\Unichat.exe`) | Sí (Signal todavía no) |
| macOS 13.3+ (Ventura) con Apple Silicon (M1 o posterior) | `Unichat-<versión>.dmg` | Sí |
| Mac Intel, Linux | Compílalo (ver [Compilar](#compilar)) | No: solo con un servidor propio ([docs/BRIDGES.md](docs/BRIDGES.md)) |

**La primera vez que la abres** aparece un aviso, porque la app todavía no está firmada con un certificado pagado:

- **macOS** («no se puede abrir porque Apple no puede comprobar…»): abre Ajustes del Sistema → Privacidad y
  seguridad, baja hasta el aviso de Unichat y pulsa **Abrir igualmente**. Solo se pide una vez.
- **Windows** («Windows protegió su PC»): pulsa **Más información** → **Ejecutar de todas formas**.

Para comprobar que el archivo es el publicado, compáralo con `sha256sums.txt` del mismo release
(`shasum -a 256 <archivo>` en Mac, `Get-FileHash <archivo>` en Windows).

Unichat avisa cuando hay una versión nueva (Ajustes → Privacidad para apagarlo; Ajustes → Acerca de para buscar
ahora).

## Riesgos de usar puentes

Unichat se conecta a cada red con los puentes de [mautrix](https://github.com/mautrix), que hacen lo mismo que la
app oficial o la web de esa red, pero **ninguna red los autoriza**. WhatsApp, Instagram, Facebook, LinkedIn o X
pueden pedirte que vuelvas a iniciar sesión, limitar tu cuenta o, en casos raros, suspenderla. Lo mismo pasa con
Beeper y con cualquier app no oficial. Si una cuenta es importante para tu trabajo, piénsalo antes de conectarla.

Qué sale de tu computador y a dónde: [docs/PRIVACIDAD.md](docs/PRIVACIDAD.md).

## Cómo funciona

Unichat es un cliente **Matrix**. Cada red llega mediante un *bridge* [mautrix](https://github.com/mautrix)
(igual que hacía Beeper). En Windows y en Mac con Apple Silicon, Unichat trae su propio servidor Matrix y lo
instala, arranca y vigila sin que hagas nada: descarga cada puente la primera vez que conectas esa red
([docs/SERVIDOR-NATIVO.md](docs/SERVIDOR-NATIVO.md)). También puede usar un servidor tuyo: en `server/` está todo
para montarlo con Docker en un comando ([docs/BRIDGES.md](docs/BRIDGES.md)).

¿Solo quieres verla? En la pantalla de inicio pulsa **Probar el modo demo**: datos de ejemplo en
memoria, con mensajes entrantes, "escribiendo…" y confirmaciones de lectura simulados.

## Funciones

- **Bandeja unificada** con insignia de red en cada avatar, fijados arriba, favoritos en cuadrícula.
- Filtros: Todos · No leídos · Favoritos · Prioridad baja · Archivados · por red (la barra lateral muestra
  solo las redes que tienes conectadas).
- **Hilos** con burbujas agrupadas, separadores de día, respuestas, reacciones, edición, borrado,
  emojis grandes, enlaces, **negrita**/*cursiva*/`código`, imágenes, archivos, video y audio.
- **Estado de las cuentas, claro y en vivo**: si una red deja de funcionar, una franja arriba de la lista lo dice.
  Roja con "Volver a vincular" si se cerró la sesión; ámbar mientras Unichat la recupera solo. En la barra lateral
  la red queda marcada. Con el servidor local, Unichat reinicia por su cuenta un puente que no responde, que lleva
  10 minutos desconectado o que se desconecta una y otra vez.
- **Telegram con tus propias claves**: Telegram exige que cada persona saque un `api_id` y `api_hash` gratis en
  my.telegram.org. Unichat los pide, con los pasos, al conectar Telegram; se cambian en Ajustes → Redes.
- **Servidores** (Discord, foros de Telegram): el encabezado de un canal muestra su servidor; un clic abre la lista
  de canales. Al abrir una notificación de un canal, la lista muestra su servidor con el canal marcado.
- **Favoritos**: clic derecho (o la tecla de menú) sobre un emoji, sticker o GIF, en el chat o en el panel lateral,
  para agregarlo o quitarlo de favoritos o de recientes. La pestaña **Favoritos** del panel los junta todos.
- **Escribir con IA**: en Ajustes → Escribir con IA puedes activar dos botones que usan Claude Code o Codex, si tienes
  alguno instalado. Uno escribe un borrador siguiendo tus instrucciones y con el chat como contexto; al pulsarlo, ese chat
  va al servicio de ese programa. El otro corrige la ortografía, las tildes y la redacción de lo que escribiste, cambiando
  lo mínimo; solo envía ese texto. El resultado queda en el cuadro de texto para que lo revises: nunca se envía solo.
- **Notas de voz a texto**: "Transcribir" bajo cada audio (o la tecla T). Usa Whisper, que viene con Unichat y
  corre en tu computador, gratis y sin enviar el audio a ningún servicio; el modelo (60 o 190 MB) se descarga
  la primera vez. En Ajustes → Voz a texto puedes elegir modelo e idioma, o poner tu propio comando
  (`{archivo}`, `{idioma}`). Claude Code y Codex no sirven para esto: no reciben audio.
- **Medios bajo demanda**: fotos, videos y archivos se descargan solo cuando los abres; lo descargado ocupa como
  máximo lo que elijas en Ajustes → Privacidad (1 GB por defecto).
- Estados de entrega (enviando → enviado → entregado → leído) e indicador de "escribiendo…".
- Compositor: Enter envía (configurable), adjuntar o arrastrar archivos, selector de emojis,
  ↑ edita tu último mensaje, borradores por chat. El clip ofrece archivo, **fotos o videos** (se envían como foto aunque sean
  WebP o de 24 MP: Unichat las pasa a JPEG de 2560 px, como hacen las apps oficiales; si no, Telegram las manda como
  archivo), documento (el original, sin comprimir), **muchos archivos** o **muchas fotos** (como foto): van uno por uno, en orden, con una barra arriba (como la del audio) que dice
  cuál va, a qué chat, cuánto falta y muestra la foto; se puede cancelar lo que falta.
- Acciones por chat: marcar leído/no leído, favorito, fijar, silenciar, prioridad baja, archivar,
  **recordatorios** (1 h, 3 h, mañana, el lunes), salir.
- Panel de información: participantes, multimedia compartida, descripción.
- **Ajustes → Cuentas** (también con el botón + de la barra): todas tus cuentas, una por fila; **Agregar cuenta** → eliges la red
  → un asistente con instrucciones, el código QR grande o el campo para pegar cookies/códigos. Puedes conectar
  varias cuentas de cada red (p. ej. WhatsApp personal y de trabajo), con alias, etiqueta en cada chat, filtro
  por cuenta y desconexión individual.
- **⌘K / Ctrl+K**: saltar a cualquier chat o ejecutar comandos.
- Notificaciones de escritorio y contador en la bandeja del sistema / dock; se queda en segundo plano al cerrar.
- Ajustes: tema claro/oscuro/sistema, color de acento, tamaño de letra, lista compacta,
  notificaciones, privacidad (confirmaciones de lectura, "escribiendo"), atajos.

## Accesibilidad

En **Ajustes → Accesibilidad**:

- **Tamaño de texto** de 85 % a 160 % (también con Ctrl/⌘ + / − / 0).
- **Alto contraste** (sigue el modo de alto contraste de Windows o «Aumentar contraste» de macOS, o se fuerza):
  colores sólidos, bordes marcados, burbujas delimitadas y color de acento oscurecido hasta cumplir WCAG AA (4.5:1).
- **Reducir movimiento** (sigue «Mostrar animaciones» de Windows / «Reducir movimiento» de macOS).
- **Fuente más legible** (Verdana), **más interlineado**, **indicador de foco reforzado**.
- **Acciones de mensaje siempre visibles** (sin depender del ratón).
- **Leer mensajes nuevos en voz alta** con la voz del sistema.

Lectores de pantalla (Narrador, VoiceOver, Orca): chats, mensajes, botones de solo ícono, imágenes,
reacciones e interruptores tienen descripción hablada.

Todo se puede usar **solo con teclado**: F6 / Shift+F6 salta entre barra lateral, lista, mensajes y campo de
texto; en los mensajes, ↑/↓ recorre, **R** responde, **E** edita, **Enter** abre las acciones, **1–6** reacciona,
**Esc** vuelve al campo de texto. El foco se ve al navegar con Tab.

## Compilar

Requisito: **JDK 21+** (Maven no hace falta, se usa el wrapper).

```bash
./run-dev.sh            # macOS / Linux: ejecutar desde el código
./mvnw test             # tests
./package.sh            # macOS: release/Unichat.app + .dmg · Linux: app-image (+ .deb)
```

Cada push a `main` compila los paquetes en GitHub Actions (artefactos `unichat-windows` y `unichat-macos` del run).
Para publicar una versión: cambia `<version>` en `pom.xml`, haz commit y empuja el tag `v<versión>`
(`git tag v1.1.0 && git push origin v1.1.0`); el workflow `build` crea el release con los paquetes y
`sha256sums.txt`. Un tag con sufijo (`v1.1.0-beta.1`) sale como pre-release y la app no lo ofrece como actualización.
El servidor y los puentes de Windows los compila aparte el workflow `natives` (ver
[docs/SERVIDOR-NATIVO.md](docs/SERVIDOR-NATIVO.md#compilaciones-propias)).

```powershell
.\mvnw.cmd javafx:run   # Windows: ejecutar desde el código
.\package.ps1           # Windows: release\Unichat\Unichat.exe + zip (+ .msi con WiX 3)
```

Prueba de teclado y lector de pantalla con la UI real (necesita pantalla):

```bash
./mvnw test -Dtest=KeyboardUiTest -Dunichat.uiTests=true
```

Prueba de integración contra un servidor real (usuarios `alice` y `bob`, contraseña `pass1234`):

```bash
UNICHAT_IT_HOMESERVER=http://localhost:8008 ./mvnw test -Dtest=MatrixLiveIT
```

## Atajos

| Atajo | Acción |
|---|---|
| ⌘K / Ctrl+K | Buscar chats y comandos |
| F6 / Shift+F6 | Siguiente / anterior zona (barra, lista, mensajes, texto) |
| ⌘+ ⌘− ⌘0 / Ctrl+ Ctrl− Ctrl+0 | Tamaño de texto |
| Alt+↑ / Alt+↓ | Chat anterior / siguiente (con Shift: solo no leídos) |
| ⌘E / Ctrl+E | Archivar / desarchivar |
| ⌘⇧U / Ctrl+Shift+U | Marcar leído / no leído |
| ⌘1…4 / Ctrl+1…4 | Todos · No leídos · Favoritos · Prioridad baja |
| ⌘⇧I / Ctrl+Shift+I | Panel de información |
| ⌘I / Ctrl+I | Borrador con IA |
| ⌘O / Ctrl+O | Corregir ortografía y redacción con IA |
| ⌘, / Ctrl+, | Ajustes |

## Datos locales

Ajustes y sesión en `%APPDATA%\Unichat` (Windows), `~/Library/Application Support/Unichat` (macOS) o
`~/.config/unichat` (Linux). El token de acceso se guarda en texto plano en `session.properties`, que no se copia entre equipos. Para
llevarte tus datos a otro equipo copia `settings.properties`, `preferences.json` (archivados, silenciados, favoritos y
carpetas) y `media-library.json`. Ahí mismo:
`cache/media` (fotos y videos vistos, con límite), `models/` (modelo de voz) y `transcripts/` (transcripciones).
Si vuelves a una versión anterior de Unichat te pedirá iniciar sesión otra vez, porque la sesión pasó a
`session.properties`; no se pierde nada.

## Limitaciones conocidas

- Sin cifrado de extremo a extremo de Matrix: configura los bridges sin cifrado (el `setup` ya lo hace).
- El estado de cada cuenta conectada es aproximado (se deduce de los mensajes del bot del bridge).
- Cada arranque hace una sincronización inicial completa.

## Code signing policy

Free code signing provided by [SignPath.io](https://about.signpath.io), certificate by [SignPath Foundation](https://signpath.org).

La solicitud a SignPath Foundation está en curso: hasta que se apruebe, los instaladores de Windows salen sin firmar
(ver [Instalar](#instalar)). Cuando se apruebe, solo se firma lo que compila GitHub Actions desde este repositorio
(`.github/workflows/build.yml`), y cada release se firma con la aprobación a mano de quien la aprueba.

- Autores y revisores (committers and reviewers): [vaoan](https://github.com/vaoan)
- Aprobadores (approvers): [vaoan](https://github.com/vaoan)

Privacidad: Unichat no envía nada a quien lo hizo. Lo que sale de tu computador, a dónde y cómo apagarlo está en
[docs/PRIVACIDAD.md](docs/PRIVACIDAD.md), y el instalador de Windows lo muestra antes de instalar.

## Licencia

El código de Unichat es [0BSD](LICENSE): úsalo, cópialo, modifícalo y distribúyelo como quieras, sin necesidad de
dar crédito. Eso cubre solo este código. Las piezas de terceros conservan sus propias licencias: JavaFX
(GPLv2 con excepción de classpath), las bibliotecas Java (Apache 2.0 y BSD), whisper.cpp (MIT), libwebp (BSD), tuwunel
y libolm (Apache 2.0). Los puentes de mautrix (AGPL-3.0) no vienen en la app: se descargan aparte al conectar cada red,
de sus releases oficiales o, en Windows, del release `natives-*` de este repositorio, compilados sin cambios desde
el código de cada versión (enlazado en ese release).
La lista completa, con los textos de licencia, está en [THIRD-PARTY.md](THIRD-PARTY.md), que también va dentro de los paquetes.

---

No afiliado a Beeper ni a Automattic. Las marcas de cada red pertenecen a sus dueños.
