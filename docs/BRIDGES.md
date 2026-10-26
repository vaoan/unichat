# Servidor propio y bridges

Unichat es un cliente Matrix. Las redes (WhatsApp, Telegram, Signal, Instagram, Messenger, Discord,
Slack, Google Messages, X y LinkedIn) llegan a través de **bridges mautrix** instalados en tu propio
servidor Matrix. La carpeta `server/` trae todo lo necesario para levantarlo con Docker.

## Qué incluye

| Servicio | Imagen | Bot | Usuarios "puppet" |
|---|---|---|---|
| Synapse (servidor Matrix) | `matrixdotorg/synapse` | — | — |
| Postgres | `postgres:16-alpine` | — | — |
| WhatsApp | `dock.mau.dev/mautrix/whatsapp` | `@whatsappbot` | `@whatsapp_…` |
| Telegram | `dock.mau.dev/mautrix/telegram` | `@telegrambot` | `@telegram_…` |
| Signal | `dock.mau.dev/mautrix/signal` | `@signalbot` | `@signal_…` |
| Instagram | `dock.mau.dev/mautrix/meta:v26.07` (modo instagram) | `@instagrambot` | `@instagram_…` |
| Messenger | `dock.mau.dev/mautrix/meta` | `@facebookbot` | `@facebook_…` |
| Discord | `dock.mau.dev/mautrix/discord` | `@discordbot` | `@discord_…` |
| Slack | `dock.mau.dev/mautrix/slack` | `@slackbot` | `@slack_…` |
| Google Messages | `dock.mau.dev/mautrix/gmessages` | `@gmessagesbot` | `@gmessages_…` |
| X (Twitter) | `dock.mau.dev/mautrix/twitter` | `@twitterbot` | `@twitter_…` |
| LinkedIn | `dock.mau.dev/mautrix/linkedin` | `@linkedinbot` | `@linkedin_…` |

Unichat reconoce cada red por estos nombres (ver `Network.java`), así que **no los cambies**.
Cada bridge usa además el prefijo de comandos `!<red>` (`!whatsapp`, `!facebook`, `!twitter`,
`!gmessages`…): Unichat lo antepone a los comandos que envía para que funcionen en cualquier chat con el bot.

**Instagram**: mautrix-meta dejó de soportar Instagram después de la versión v26.07, así que ese
contenedor está fijado a `v26.07`. Messenger usa la última versión.

## Requisitos

- Docker Desktop (Windows/macOS) o Docker Engine + Compose v2 (Linux), **en marcha**.
- ~2–3 GB de RAM libres.
- Para Telegram (opcional): un `api_id` y `api_hash` propios (ver más abajo).

## Puesta en marcha

macOS / Linux:

```bash
cd server
ADMIN_USER=yo ./setup.sh localhost
```

Windows (PowerShell):

```powershell
cd server
.\setup.ps1 -Domain localhost -AdminUser yo
```

El script (se puede repetir sin miedo; solo reaplica lo necesario y reinicia lo que cambió):

1. Crea `server/.env` con contraseñas aleatorias (git lo ignora; no lo compartas).
2. Genera la configuración de Synapse (`data/synapse/homeserver.yaml`), la conecta a Postgres y
   relaja los límites de mensajes/inicios de sesión (es un servidor personal).
3. Genera la configuración de cada bridge, le pone los nombres de bot/puppet de Unichat,
   **desactiva el cifrado**, activa el *double puppeting* y crea su `registration.yaml`.
4. Registra los bridges en Synapse y levanta todo con `docker compose up -d`
   (los bridges esperan a que Postgres y Synapse estén sanos).
5. La primera vez crea tu usuario con una contraseña aleatoria y la guarda en
   **`server/CREDENCIALES.txt`** (git lo ignora).

Para elegir solo algunas redes: `BRIDGES="whatsapp signal" ./setup.sh` o
`.\setup.ps1 -Bridges whatsapp,signal`.

Crear otro usuario más tarde:

```bash
docker compose exec synapse register_new_matrix_user -c /data/homeserver.yaml -a http://localhost:8008
```

### Telegram (activar después)

Telegram exige claves de API propias. Sin ellas el script **deja Telegram desactivado** (no se
registra en Synapse ni arranca su contenedor) y el resto funciona normal. Para activarlo:

1. Entra en <https://my.telegram.org> → *API development tools* y crea una app: obtienes `api_id`
   (número) y `api_hash`.
2. Vuelve a ejecutar el script con ellas (se guardan en `server/.env`, así que solo hace falta una vez):

   ```bash
   cd server
   TELEGRAM_API_ID=123456 TELEGRAM_API_HASH=abcdef0123... ./setup.sh
   ```

   ```powershell
   cd server
   $env:TELEGRAM_API_ID="123456"; $env:TELEGRAM_API_HASH="abcdef0123..."; .\setup.ps1
   ```

   También puedes añadir a mano `TELEGRAM_API_ID=...` y `TELEGRAM_API_HASH=...` en `server/.env`
   y ejecutar `./setup.sh` (o `.\setup.ps1`) sin nada más. El script añade `COMPOSE_PROFILES=telegram`
   al `.env` para que `docker compose up -d` también arranque Telegram.

Para desactivarlo de nuevo, borra esas dos líneas de `server/.env` y vuelve a ejecutar el script.

### Sobre el dominio

El dominio forma parte de tu ID (`@yo:localhost`) y **no se puede cambiar después**.
`localhost` sirve para usar Unichat en el mismo equipo. Si quieres usarlo desde otros dispositivos,
usa un dominio real, publica el puerto 8008 detrás de un proxy HTTPS (Caddy, nginx, Traefik) y sirve
`https://tu-dominio/.well-known/matrix/client` con `{"m.homeserver":{"base_url":"https://matrix.tu-dominio"}}`.
Con `localhost` el servidor no puede federarse, así que los avatares de los bots (alojados en
`maunium.net`) no se cargan; es solo cosmético.

## Iniciar sesión en Unichat

En la pantalla de inicio de Unichat escribe:

- **Servidor**: `localhost:8008` (o tu dominio; Unichat consulta `.well-known` automáticamente).
- **Usuario**: `yo` (o el ID completo `@yo:localhost`).
- **Contraseña**: la de `server/CREDENCIALES.txt`.

## Conectar cada red

Pulsa **Conectar una red** (botón **+** de la barra lateral, la paleta de comandos o
*Ajustes → Cuentas*) y elige la red. Unichat abre (o crea) el chat con el bot del bridge y le envía
el comando de inicio de sesión. Sigue las instrucciones del bot **en ese chat**:

| Red | Comando que envía Unichat | Qué responde el bot / qué haces tú |
|---|---|---|
| WhatsApp | `!whatsapp login qr` | Un **código QR**. En el móvil: WhatsApp → *Dispositivos vinculados* → *Vincular dispositivo* y escanéalo. Alternativa: `!whatsapp login phone` (código de emparejamiento). |
| Telegram | `!telegram login qr` | Un **código QR**. En el móvil: Telegram → *Ajustes → Dispositivos → Vincular dispositivo de escritorio*. Si tienes verificación en dos pasos, el bot pedirá la contraseña. Alternativa: `!telegram login phone`. |
| Signal | `!signal login` | Un **código QR**. En el móvil: Signal → *Ajustes → Dispositivos vinculados* → **+**. |
| Google Messages | `!gmessages login` | Pide las **cookies de tu cuenta Google** (abre el enlace que da el bot, inicia sesión, copia las cookies como JSON o el comando cURL desde las herramientas de desarrollador y pégalo en el chat). Después confirma el **emoji** que aparece en Mensajes del móvil. Ya no existe el emparejamiento por QR. |
| Instagram | `!instagram login` | Pide las **cookies** de instagram.com (JSON o cURL desde las herramientas de desarrollador). |
| Messenger | `!facebook login facebook` | Pide las **cookies** de facebook.com. Alternativas: `!facebook login messenger` (cookies de messenger.com) o `!facebook login messenger-lite` (usuario y contraseña). |
| Discord | `!discord login` | Un **código QR**: escanéalo desde la app móvil de Discord (*Ajustes → Escanear código QR*). Alternativa: `!discord login-token`. |
| Slack | `!slack login email` | Pide el **correo** de tu cuenta de Slack y luego el código que te llega por email. Alternativa: `!slack login token` (token `xoxc-…` + cookie `d` `xoxd-…`). |
| X | `!twitter login cookies` | Pide las **cookies** `auth_token` y `ct0` (abre x.com en una ventana de incógnito, cópialas y cierra el navegador **antes** de pegarlas). Alternativa: `!twitter login password`. |
| LinkedIn | `!linkedin login cookies` | Pide las **cookies** de linkedin.com (mejor desde una ventana de incógnito). Alternativa: `!linkedin login password`. |

Los QR caducan en un minuto o dos: si no te da tiempo, vuelve a pulsar **Conectar una red**.
Para abandonar un inicio de sesión escribe `cancel` en el chat del bot; `help` muestra todos los comandos.

Cuando el bot confirma el inicio de sesión, tus chats aparecen solos en la bandeja de Unichat
(los bridges te invitan a cada sala y Unichat acepta las invitaciones automáticamente).

**Desconectar**: en *Ajustes → Cuentas → Desconectar* Unichat envía `list-logins` y después
`logout <id>` a cada sesión que encuentre (o `logout` si el bridge no tiene ese comando).
Es "mejor esfuerzo": si el bot pide algo más, termina el proceso en su chat.

## Limitaciones

- **Sin cifrado de extremo a extremo (E2EE) en Matrix**: Unichat no implementa E2EE. Por eso el
  script desactiva el cifrado en todos los bridges (`encryption.allow: false`). El tráfico entre
  la red original y el bridge sigue cifrado como siempre; lo que no se cifra es la parte entre tu
  servidor y Unichat, por eso conviene que el servidor sea tuyo. Las salas Matrix ya cifradas
  muestran "🔒 Mensaje cifrado no compatible".
- **Instagram, Messenger, X, LinkedIn, Slack y Google Messages** usan cookies de la web: pueden
  caducar y la red podría bloquear la cuenta si detecta un uso inusual. Úsalo bajo tu responsabilidad.
- **Instagram** depende de una versión antigua de mautrix-meta (v26.07); si Meta cambia algo, puede
  dejar de funcionar sin arreglo.
- **WhatsApp y Signal** exigen que el teléfono principal se conecte de vez en cuando.
- El estado de las cuentas en Unichat es aproximado: se deduce de las salas del bridge y de los
  mensajes del bot.
- Los avisos de lectura públicos solo se envían si están activados en *Ajustes → Privacidad*;
  si no, Unichat envía un recibo privado (`m.read.private`) para limpiar el contador de no leídos.

## Arrancar, parar y mantener

Los contenedores tienen `restart: unless-stopped`: vuelven solos al abrir Docker Desktop.
Si Docker se reinicia, los bridges pueden reiniciarse un par de veces hasta que Postgres y Synapse
estén listos; es normal.

```bash
cd server
docker compose ps                                # estado
docker compose stop                              # parar todo (los datos quedan en server/data)
docker compose start                             # volver a arrancar
docker compose logs -f whatsapp                  # ver el log de un bridge
docker compose pull && ./setup.sh                # actualizar imágenes y reaplicar la configuración
docker compose down                              # parar y borrar contenedores (no los datos)
```

Copia de seguridad: para el stack y guarda la carpeta `server/data/`, `server/.env` y
`server/CREDENCIALES.txt`.

Si cambias nombres de bot o puppets en un `config.yaml`, borra el `registration.yaml` de ese bridge
y vuelve a ejecutar el script para regenerarlo.

## Varias cuentas de la misma red

Los bridges nuevos (WhatsApp, Signal, Instagram, Messenger, Google Messages, Slack, X, LinkedIn, Telegram)
admiten varias sesiones. En Unichat: **Ajustes → Cuentas → Agregar cuenta → elige la red** (el botón + de la barra lateral lleva ahí); se abre un asistente
con el QR o el campo para pegar cookies/códigos (el chat técnico con el bot queda oculto, con un enlace
para verlo). También con clic derecho sobre la red en la barra lateral. En **Ajustes → Redes conectadas** cada cuenta tiene su nombre
(alias) y su botón **Desconectar**, que cierra solo esa sesión (`logout <id>`).

Unichat lee la lista de sesiones con `list-logins` (al conectar o desconectar, y cada pocas horas al
abrir la app). Limitación: los bridges no siempre indican a qué sesión pertenece cada chat; cuando no se
sabe, los chats aparecen juntos bajo la red y el filtro por cuenta no se muestra (para no ocultar chats).

## Historial (backfill)

`setup` activa el backfill en todos los bridges (últimos 50 mensajes por chat al vincular y hasta 500 mensajes
perdidos tras reinicios). WhatsApp manda el historial **una sola vez, al vincular**: si vinculaste una cuenta
antes de activar el backfill, desconéctala en **Cuentas** y vuelve a vincularla para recibir tus chats.
