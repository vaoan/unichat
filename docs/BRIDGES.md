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
| Instagram | `dock.mau.dev/mautrix/meta` (modo instagram) | `@instagrambot` | `@instagram_…` |
| Messenger | `dock.mau.dev/mautrix/meta` (modo facebook) | `@facebookbot` | `@facebook_…` |
| Discord | `dock.mau.dev/mautrix/discord` | `@discordbot` | `@discord_…` |
| Slack | `dock.mau.dev/mautrix/slack` | `@slackbot` | `@slack_…` |
| Google Messages | `dock.mau.dev/mautrix/gmessages` | `@gmessagesbot` | `@gmessages_…` |
| X (Twitter) | `dock.mau.dev/mautrix/twitter` | `@twitterbot` | `@twitter_…` |
| LinkedIn (opcional) | `dock.mau.dev/mautrix/linkedin` | `@linkedinbot` | `@linkedin_…` |

Unichat reconoce cada red por estos nombres (ver `Network.java`), así que **no los cambies**.

## Requisitos

- Docker Desktop (Windows/macOS) o Docker Engine + Compose v2 (Linux).
- ~2 GB de RAM libres.
- Para Telegram: un `api_id` y `api_hash` propios, creados en <https://my.telegram.org> → *API development tools*.

## Puesta en marcha

macOS / Linux:

```bash
cd server
TELEGRAM_API_ID=123456 TELEGRAM_API_HASH=abcdef... ./setup.sh localhost
```

Windows (PowerShell):

```powershell
cd server
$env:TELEGRAM_API_ID="123456"; $env:TELEGRAM_API_HASH="abcdef..."
.\setup.ps1 -Domain localhost
```

El script:

1. Crea `server/.env` con contraseñas aleatorias (no lo subas a ningún sitio).
2. Genera la configuración de Synapse (`data/synapse/homeserver.yaml`) y la conecta a Postgres.
3. Genera la configuración de cada bridge, le pone los nombres de bot/puppet de Unichat,
   **desactiva el cifrado**, activa el *double puppeting* y crea su `registration.yaml`.
4. Registra los bridges en Synapse y levanta todo con `docker compose up -d`.
5. Ofrece crear tu usuario (por defecto `admin`; cambia con `ADMIN_USER=...` o `-AdminUser`).

Para elegir solo algunas redes: `BRIDGES="whatsapp telegram" ./setup.sh` o
`.\setup.ps1 -Bridges whatsapp,telegram`. LinkedIn es opcional: añádelo a esa lista.

Crear otro usuario más tarde:

```bash
docker compose exec synapse register_new_matrix_user -c /data/homeserver.yaml -a http://localhost:8008
```

### Sobre el dominio

El dominio forma parte de tu ID (`@admin:localhost`) y **no se puede cambiar después**.
`localhost` sirve para usar Unichat en el mismo equipo. Si quieres usarlo desde otros dispositivos,
usa un dominio real, publica el puerto 8008 detrás de un proxy HTTPS (Caddy, nginx, Traefik) y sirve
`https://tu-dominio/.well-known/matrix/client` con `{"m.homeserver":{"base_url":"https://matrix.tu-dominio"}}`.

## Iniciar sesión en Unichat

En la pantalla de inicio de Unichat escribe:

- **Servidor**: `localhost:8008` (o tu dominio; Unichat consulta `.well-known` automáticamente).
- **Usuario**: `admin` (o el ID completo `@admin:localhost`).
- **Contraseña**: la que pusiste al crear el usuario.

## Conectar cada red

En **Ajustes → Cuentas** pulsa *Añadir* en la red. Unichat abre (o crea) el chat con el bot del
bridge y le envía `login`. Sigue las instrucciones del bot en ese chat:

| Red | Cómo se inicia sesión |
|---|---|
| WhatsApp | El bot envía un **código QR**: escanéalo en WhatsApp → *Dispositivos vinculados*. También existe `login phone` (código de emparejamiento). |
| Signal | Código QR: en Signal → *Ajustes → Dispositivos vinculados*. |
| Google Messages | Código QR: en Mensajes → *Emparejamiento de dispositivos*. |
| Telegram | El bot pide tu número de teléfono y luego el código que te llega por Telegram (y la contraseña 2FA si la tienes). |
| Instagram / Messenger | El bot pide las **cookies** de tu sesión web (sigue el enlace de ayuda que envía el bot). |
| Discord | QR desde la app móvil de Discord, o `login-token` con tu token. |
| Slack | Token + cookie `d` de tu sesión web (el bot explica cómo obtenerlos). |
| X | Cookies de tu sesión web (`auth_token`, `ct0`). |
| LinkedIn | Cookies de tu sesión web. |

Cuando el bot confirma el inicio de sesión, tus chats aparecen solos en la bandeja de Unichat
(los bridges te invitan a cada sala y Unichat acepta las invitaciones automáticamente).
Si el bot responde con la lista de métodos de inicio de sesión, escribe en ese chat el que quieras,
por ejemplo `login qr`. Escribe `help` para ver todos los comandos.

**Desconectar**: en *Ajustes → Cuentas → Desconectar* Unichat envía `list-logins` y después
`logout <id>` a cada sesión que encuentre (o `logout` si el bridge no tiene ese comando).
Es "mejor esfuerzo": si el bot pide algo más, termina el proceso en su chat.

## Limitaciones

- **Sin cifrado de extremo a extremo (E2EE) en Matrix**: Unichat no implementa E2EE. Por eso el
  script desactiva el cifrado en todos los bridges (`encryption.allow: false`). El tráfico entre
  la red original y el bridge sigue cifrado como siempre; lo que no se cifra es la parte entre tu
  servidor y Unichat, por eso conviene que el servidor sea tuyo. Las salas Matrix ya cifradas
  muestran "🔒 Mensaje cifrado no compatible".
- **Telegram** necesita `api_id`/`api_hash` propios. Sin ellos el contenedor de Telegram se
  reinicia en bucle; vuelve a ejecutar el script con las variables puestas.
- **Instagram, Messenger, X, LinkedIn y Slack** usan cookies de la web: pueden caducar y la red
  podría bloquear la cuenta si detecta un uso inusual. Úsalo bajo tu responsabilidad.
- **WhatsApp y Signal** exigen que el teléfono principal se conecte de vez en cuando.
- El estado de las cuentas en Unichat es aproximado: se deduce de las salas del bridge y de los
  mensajes del bot.
- Los avisos de lectura públicos solo se envían si están activados en *Ajustes → Privacidad*;
  si no, Unichat envía un recibo privado (`m.read.private`) para limpiar el contador de no leídos.
- LinkedIn es el bridge más nuevo y su imagen podría cambiar de nombre; por eso está desactivado
  por defecto (perfil `linkedin` en `docker-compose.yml`).

## Mantenimiento

```bash
cd server
docker compose pull && docker compose up -d     # actualizar
docker compose logs -f whatsapp                  # ver el log de un bridge
docker compose down                              # parar todo (los datos quedan en server/data)
```

Copia de seguridad: para el stack y guarda la carpeta `server/data/` y el archivo `server/.env`.

Si cambias nombres de bot o puppets en un `config.yaml`, borra el `registration.yaml` de ese bridge
y vuelve a ejecutar el script para regenerarlo.
