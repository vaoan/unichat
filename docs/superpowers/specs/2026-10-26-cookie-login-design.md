# Inicio de sesión sin cookies a mano (Instagram, Messenger, X, LinkedIn, Google Messages)

## Por qué
Hoy, conectar una red "de cookies" obliga a abrir las herramientas de desarrollador y pegar un cURL.
El usuario lo calificó de "horrible": quiere algo amigable, idealmente usando el navegador que ya usa,
como el inicio de sesión de Spotify. Instagram no ofrece un login oficial que sirva para los puentes
(OAuth solo para cuentas de empresa y con un token inútil para el bridge), y una página web no puede
entregarle a otra app la sesión de otro sitio. Por eso la sesión se obtiene en la máquina del usuario.

## Objetivos
- Conectar Instagram, Messenger, X, LinkedIn y Google Messages **sin ver nunca una cookie**.
- Si el usuario ya tiene la sesión abierta en su navegador, usarla con un clic (A).
- Si no se puede, una ventana de inicio de sesión dentro de Unichat (B), con usuario, contraseña y
  verificación en dos pasos como en la web.
- Funcionar en Windows (destino principal) y macOS. Varias cuentas por red.

## No objetivos
- Safari (requiere "Acceso total al disco").
- Chrome/Edge en Windows con el cifrado nuevo (cookies "v20", app-bound encryption, Chrome 127+): se
  detecta y se pasa directo a B, sin pedir privilegios de administrador.
- Extensión de navegador (opción C, descartada por ahora).

## Anti-objetivos
- Guardar la sesión del navegador en Unichat: solo se lee en el momento y se envía al bridge del propio
  servidor del usuario. Nada se sube a ningún otro lado.
- Leer cookies de otros sitios: solo los dominios de la red elegida.
- Pedir permisos sin explicar por qué (el aviso del llavero de macOS se anuncia antes).

## Enfoques considerados
- **A. Sesión del navegador** (lectura local de cookies, como hace Beeper con `credential-extractor`).
  Sin escribir nada, pero no sirve con Chrome/Edge en Windows.
- **B. Ventana de login integrada** (WebView de JavaFX con la página real). Siempre funciona; hay que
  escribir la contraseña una vez.
- **C. Extensión de navegador**. La más parecida a Spotify, pero exige instalar una extensión
  (y "modo desarrollador" hasta publicarla). Descartada.
- **Elegido por el usuario: A + B.** A primero; si no encuentra sesión, B.

## Cómo funciona (decidido)
1. En el asistente, al elegir una red de cookies: "Buscando tu sesión de Instagram en tus navegadores…".
2. A — se buscan perfiles de **Firefox** (Windows/macOS/Linux; `cookies.sqlite` sin cifrar, copiado para no
   chocar con el bloqueo) y de **Chrome, Edge y Brave** (macOS: clave del llavero "… Safe Storage",
   AES-CBC "v10"; Windows: DPAPI "v10"; "v20" = no compatible). Se valida que estén las cookies
   imprescindibles de esa red:
   - Instagram `sessionid`, `csrftoken` (+ `ds_user_id`, `mid`, `ig_did` si están)
   - Messenger/Facebook `c_user`, `xs` (+ `datr`, `fr`)
   - X `auth_token`, `ct0`
   - LinkedIn `li_at`, `JSESSIONID`
   - Google Messages: cookies de `google.com` (`SAPISID`, `SID`, `__Secure-1PSID`…) + `messages.google.com`
3. Si hay una o más sesiones: tarjeta "Usar tu sesión de Instagram de Firefox (perfil X)" con botones
   **Usar esta sesión** y **Iniciar sesión con otra cuenta** (→ B).
4. B — ventana con la web real (agente de usuario de un navegador actual, almacén de cookies vacío para
   poder agregar otra cuenta). Cuando aparecen las cookies imprescindibles, la ventana se cierra sola.
5. Las cookies se envían al bridge como JSON `{"nombre": "valor"}` (formato que aceptan los bridges de
   cookies) por el mismo flujo del asistente; el asistente sigue mostrando éxito o error.
6. Si el bridge pide un paso extra (Google Messages: confirmar emoji en el teléfono), el asistente lo
   muestra como hoy.
7. Siempre queda un enlace discreto "Pegar cookies manualmente" para casos raros.

## Pruebas
- Descifrado de Chrome (macOS v10, Windows v10) con datos sintéticos; detección de v20.
- Lectura de Firefox con una base `cookies.sqlite` sintética.
- Selección de cookies por red y armado del JSON.
- Comprobación manual con los navegadores reales del usuario (Chrome con 3 perfiles en este Mac).

## Para quien lo construya
- Dependencias nuevas: `javafx-web` (WebView) y un driver SQLite para Java.
- Detalles de UI dentro del estilo actual del asistente y de accesibilidad (textos accesibles, teclado).
