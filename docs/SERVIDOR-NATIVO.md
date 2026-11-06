# Servidor nativo (sin Docker)

Unichat necesita un servidor Matrix y un "puente" por cada red (WhatsApp, Telegram, …). Antes eso corría en
Docker (`server/`). El servidor nativo hace lo mismo con programas normales del sistema, sin Docker:

| Pieza | Qué es | De dónde sale |
|---|---|---|
| **tuwunel** | Servidor Matrix (un solo ejecutable, en Rust) | Se compila en GitHub Actions y **viaja dentro del instalador de Unichat** |
| **Puentes mautrix** | Un ejecutable por red | **Se descargan** de los releases oficiales de mautrix, verificados con `sha256sums.txt` |
| **SQLite** | Base de datos (un archivo por componente) | Incluida en los ejecutables; no hay que instalar nada |
| **libolm** (solo Mac) | Librería que los puentes oficiales de Mac necesitan para arrancar | Se compila con `native/build-libolm.sh` y **viaja dentro de Unichat**; se copia junto a los puentes |
| **gif2webp** (libwebp 1.6.0, licencia BSD) | Convierte stickers animados GIF a WebP animado para chats que solo aceptan WebP (WhatsApp) | `package.sh` (`native/fetch-gif2webp.sh`) y `package.ps1` descargan el binario oficial de Google con su SHA-256 fijado y **viaja dentro de Unichat**; sin él, el sticker se envía fijo (PNG) |

> Los puentes oficiales de mautrix para Mac enlazan `libolm.3.dylib`, que Homebrew ya no ofrece. Sin ella no arrancan
> (`Library not loaded: @rpath/libolm.3.dylib`). Unichat la lleva consigo; el cifrado del puente está apagado, así que
> solo se carga. A futuro: compilar los puentes con `-tags goolm` (Go puro, sin libolm), como en Windows.

## Objetivo: que se instale solo

Al iniciar Unichat se revisa qué falta y se instala sin preguntar:

1. **Servidor:** si `bin/tuwunel` no existe o es de otra versión, se copia el que trae la app.
2. **Puentes:** solo los de las redes que el usuario agregó. Al agregar una red nueva, su puente se descarga en
   ese momento (con barra de progreso). Si un puente fijado en `versions.env` cambió de versión, se descarga el
   nuevo y se reinicia.
3. **Configuración:** se genera la primera vez y en cada arranque se vuelven a aplicar solo las claves de
   Unichat (las contraseñas y tokens se conservan).
4. **Cuenta:** la primera vez se crea el usuario y Unichat inicia sesión solo; el usuario nunca ve una
   contraseña ni una URL de servidor.
5. **Arranque y vigilancia:** Unichat arranca el servidor, espera a que responda, arranca los puentes y los
   reinicia si se caen. Al salir de Unichat (menú de la bandeja → Salir) se detienen.

Con eso, **actualizar Unichat = recibir todo**: nuevas versiones de puentes, del servidor y cambios de
configuración vienen con la app.

> Estado (26-oct-2026): la lógica existe como scripts de referencia para Mac (`native/setup-mac.sh` y
> `native/run-mac.sh`). El siguiente paso es pasarla a Java dentro de Unichat para que corra al arrancar.

## Dónde queda cada cosa

- macOS: `~/Library/Application Support/Unichat/server/`
- Windows: `%LOCALAPPDATA%\Unichat\server\`

```
server/
  bin/                 tuwunel y un ejecutable por puente (+ archivo .version con la versión instalada)
  secrets.env          token de registro y token de doble puppeting (privado)
  CREDENCIALES.txt     usuario y contraseña de la cuenta (privado)
  tuwunel/             tuwunel.toml + base de datos (db/)
  appservices/         registro de cada puente (tuwunel los lee de aquí al arrancar)
  <red>/               config.yaml, registration.yaml, <red>.db (SQLite) y bridge.log (filtrado) de cada puente
  run/                 PID de cada proceso en marcha
  bridges.active       puentes activos
```

## Versiones fijadas: `native/versions.env`

Una línea por componente: `<red>=<repo>@<release>:<prefijo del ejecutable>`. El archivo que se baja es
`<prefijo>-<sistema>-<arquitectura>` (por ejemplo `mautrix-whatsapp-darwin-arm64`) y se compara con el
`sha256sums.txt` del mismo release; si no coincide, no se instala.

**Actualizar un puente = cambiar su versión aquí** (y, si su changelog cambió el formato de configuración,
ajustar las claves que aplica el instalador). La skill de mantenimiento de puentes hará ese trabajo:
leer changelogs oficiales, subir versiones, migrar configuración, probar y publicar.

## Configuración que aplica Unichat a cada puente

La misma que el stack Docker (`server/setup.sh`), cambiando Postgres por SQLite y nombres de contenedor por
`127.0.0.1`:

- Prefijo de comandos `!<red>` (Unichat lo usa para hablar con cada bot; ver `Network.protocolId()`).
- Permisos: el dominio propio es `user`, `@yo:localhost` es `admin`.
- Cifrado de puente desactivado (todo es local).
- **Doble puppeting** con el appservice `doublepuppet`: lo que envías desde el teléfono aparece como tuyo.
- **Historial ("backfill") activado desde el principio**: 50 mensajes al crear un chat, hasta 500 al
  ponerse al día. Importante: un chat creado con el backfill apagado no se rellena después (pasó con Telegram
  en Docker); por eso va activado antes del primer inicio de sesión.
- WhatsApp: solo los 50 chats más recientes al conectar; los archivados en el teléfono llegan a "Archivados"
  (etiqueta `u.archived`, en un solo sentido).
- Telegram: cada persona usa su propio `api_id`/`api_hash` de my.telegram.org; Unichat no trae ninguno. Los pide al
  conectar Telegram y se cambian en Ajustes → Redes (`TelegramKeys`, guardados en `settings.properties`). Sin claves
  válidas Telegram no arranca, pero el servidor y las demás redes sí.
- Instagram: mautrix-meta v0.2607.0 en modo instagram (la última versión que funciona para Instagram).
- **Medios bajo demanda (`direct_media`)**: los puentes ya no copian fotos, videos ni archivos al servidor. Cada
  mensaje lleva un `mxc://media-<red>.localhost/…` y Unichat lo pide al propio puente
  (`127.0.0.1:<puerto>/_matrix/client/v1/media/download/…`, ver `LocalServer.directMediaBase`) solo al abrirlo;
  el puente lo trae de la red en ese momento. No hace falta federación. Antes, cada adjunto de cada canal y cada
  resincronización de Telegram se guardaba entero (11 GB en 4 días). Los mensajes anteriores al cambio siguen
  apuntando a su copia local. Inconveniente: un archivo que la red ya borró (WhatsApp los borra tras un tiempo)
  solo vuelve si el teléfono lo conserva (`direct_media_auto_request`).
- **Estado en vivo** (`status_endpoint`): cada puente avisa los cambios de conexión de sus cuentas a
  `127.0.0.1:<puerto libre>/status/<puente>` (servido por Unichat, `StatusEndpoint`; solo acepta el `as_token` de
  ese puente). `BridgeStates` los guarda y la lista de cuentas los usa en vez de preguntar `list-logins`.
- **Vigilante** (`Watchdog`): cada 30 s pregunta al servidor y a cada puente si responden. Reinicia uno que no
  responde 3 veces seguidas, cuyas cuentas llevan 10 min desconectadas, o que se desconectó 5 veces en 10 min
  (mautrix-discord acumula suscripciones a servidores en cada reconexión hasta que Discord lo corta con 4008). Entre
  reinicios espera 5 min, el doble cada vez, hasta 1 h. Una sesión cerrada no se reinicia: solo la arregla volver
  a vincular. El tiempo se mide sin contar el Mac dormido. **Sin internet** no reinicia un puente desconectado (solo uno
  colgado): mautrix-discord, si arranca sin red, falla una vez y no reintenta. Para saberlo intenta abrir una conexión a
  `discord.com`, `web.whatsapp.com` o `telegram.org`, sin enviar nada. **Al volver internet** reinicia los puentes de una vez, sin esperas, y cada uno se pone al día; Facebook e Instagram no, porque un reinicio les puede costar la sesión.
- **Sesiones de Meta** (`MetaSessions`): en algunas redes (Claro, en Colombia) `www.facebook.com` redirige a `web.facebook.com`. mautrix-meta manda sus cookies como cabecera, Go las descarta en esa redirección, Facebook responde con el inicio de sesión y el puente **borra las cookies**, aunque seguían siendo válidas. Por eso: se guarda una copia privada de las cookies (`<puente>/session-backup.json`, solo legible por ti, como la base de datos); no se reinicia uno de estos puentes mientras haya esa redirección; y si el puente borró las cookies de una cuenta que sigue listada, se devuelven en cuanto la red lo permita, hasta 2 veces (si la sesión de verdad se cerró, Facebook las rechaza y hay que vincular de nuevo). Una cuenta desvinculada a propósito ya no está listada, y su copia se elimina.
- Lo descargado por Unichat (`cache/media`) tiene un límite (Ajustes → Privacidad, 1 GB por defecto): se borra
  primero lo que hace más tiempo no se ve (`MediaCache`).

El servidor (`tuwunel.toml`): solo escucha en `127.0.0.1:8008`, sin federación, sin presencia ("en línea"),
registro solo con token secreto, y lee los registros de los puentes de `appservices/`.

## Plataformas

| | Mac Apple Silicon | Windows x64 | Mac Intel |
|---|---|---|---|
| Servidor tuwunel | ✅ compila (sin cambios, 26 min en CI) | ✅ compila y arranca sin las funciones de Linux (`--no-default-features` sin `jemalloc`/`io_uring`/`systemd`; 82 min en CI, `tuwunel.exe` de 123 MB) | ❌ fuera de alcance |
| Puentes Go (WhatsApp, Telegram, Meta, Slack, Google Messages, X, LinkedIn, Discord) | ✅ binarios oficiales | ✅ compilan con `go build -tags goolm` (no hay binarios oficiales; los compilaría nuestro CI) | ❌ |
| Signal | ✅ binario oficial | ⚠️ la parte Rust compila; falla al enlazar con Go (pendiente) | ❌ |

- **Mac Intel queda fuera**: GitHub retiró sus máquinas `macos-13` (dic-2025) y quitará las Intel restantes en
  2027; Apple ya no da soporte a Intel.
- Detalles de las pruebas de compilación: rama `spike/native-builds`, archivo `spike/NOTES.md`.

## Pasar de Docker al servidor nativo

Los chats no se migran desde Synapse. Al cambiar hay que volver a iniciar sesión en cada red una vez; los chats
se crean de nuevo ya con historial. Lo marcado solo en Unichat (favoritos, archivados, prioridad baja) se
pierde; los mensajes no, porque están en cada red.

## Uso manual (desarrollo, Mac)

```sh
# Una vez: compilar tuwunel (o usar el que trae la app)
cargo build --release --locked          # en un clon de tuwunel en la versión de versions.env
TUWUNEL_BIN=<ruta>/target/release/tuwunel native/setup-mac.sh     # descarga puentes y genera configs
native/run-mac.sh start                 # arranca todo; crea la cuenta la primera vez
native/run-mac.sh status
native/run-mac.sh stop
```
Requiere `yq` (`brew install yq`). Telegram toma las claves de `server/.env` si existen.

## Logs y reinstalación (Mac)

La app instalada no tiene consola; todo queda en archivos:

| Qué | Archivo |
|---|---|
| Unichat | `~/Library/Application Support/Unichat/logs/unichat.log` (el anterior: `unichat.log.1`, al pasar 5 MB) |
| Servidor | `~/Library/Application Support/Unichat/server/tuwunel/tuwunel.log` |
| Cada puente | `~/Library/Application Support/Unichat/server/<red>/bridge.log` (JSON) |

**Sin datos personales:** los puentes y el servidor escriben a su salida estándar y Unichat la filtra antes de
guardarla (`Redactor`): quita IDs de Matrix, salas y eventos, enlaces de archivos, números de teléfono e IDs
numéricos largos, correos, tokens y los valores de campos como nombre, texto, título o usuario. El log de Unichat
pasa por el mismo filtro. Los puentes registran en nivel `info` (no `debug`).

**Limpieza:** cada archivo rota al pasar de 5 MB (se guarda una copia `.1`). Al abrir Unichat y luego una vez al
día se borran los registros que no se escribieron en los últimos N días (Ajustes → Privacidad → Registros: 1, 7 o
30 días; por defecto 7). "Borrar registros ahora" vacía los que están en uso y borra el resto. Nunca se tocan bases
de datos ni configuraciones.

Seguir el log en vivo: `tail -f "$HOME/Library/Application Support/Unichat/logs/unichat.log"`.

Arreglar y reinstalar: `TUWUNEL_BIN=<ruta a tuwunel> ./install-mac.sh` empaqueta, cierra Unichat si está
abierto y reemplaza `/Applications/Unichat.app`. Los datos (sesión, servidor, chats) no se tocan.
