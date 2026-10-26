# Panel lateral, opciones por red, stickers, emojis y GIFs

## Por qué
El usuario no tiene selector de stickers, emojis personalizados ni GIFs; no puede descargar lo que le mandan ni
guardar GIFs en favoritos; y cada chat muestra las mismas opciones aunque su red no las soporte (o le falten las que
sí soporta). Quiere, como en Telegram, **un panel a la derecha en todos los chats** que sirva para ver el perfil y,
sin cerrarse, cambiar a la paleta de emojis, stickers o GIFs.

## Objetivos
- Panel lateral único por chat con pestañas **Perfil · Emojis · Stickers · GIFs**.
- Cada chat ofrece **las opciones nativas de su red**, leídas del propio puente (siempre al día).
- **Descargar** ("Guardar como…") fotos, videos, archivos, audios y stickers recibidos.
- **Favoritos y recientes** de stickers y GIFs, **compartidos entre todos los chats y redes**.
- Packs de stickers/emojis de cada red donde el puente los ofrezca.
- **Buscar GIFs** con proveedores externos configurables (Giphy primero).
- Stickers y fotos abren en grande con un solo clic (hecho: `db039b4`).

## No objetivos (por ahora)
- Crear o editar packs de stickers.
- Enviar stickers nativos de Discord (su puente los manda como imagen).
- Encuestas, ubicación y mensajes temporales completos: en esta etapa solo se **muestran o se ofrecen** donde la red
  los soporta; su interfaz de creación va en una etapa posterior.

## Anti-objetivos
- Ofrecer una acción que la red rechaza (por ejemplo "Editar" en Google Messages, o pasado el límite de 15 min en
  WhatsApp).
- Mandar a un proveedor de GIFs algo distinto del texto que el usuario busca (ni chats, ni contactos, ni su id).
- Romper el uso con servidores externos (Docker u otros): sin datos del puente, se usa lo de hoy.

## De dónde salen los datos (verificado leyendo el código de los puentes)
- **Opciones por chat:** evento de estado **`com.beeper.room_features`** que cada puente bridgev2 publica en cada
  sala (estructura `event.RoomFeatures` de mautrix-go). Campos usados: `file` por tipo (`m.image`, `m.video`,
  `m.audio`, `m.file`, `org.matrix.msc3245.voice`, `fi.mau.gif`, `m.sticker`: tipos MIME y tamaño máximo), `edit` +
  `edit_max_age` + `edit_max_count`, `delete` + `delete_max_age`, `delete_for_me`, `reply`, `thread`, `reaction` +
  `reaction_count` + `allowed_reactions` + `custom_emoji_reactions`, `poll`, `location_message`,
  `disappearing_timer`, `read_receipts`, `typing_notifications`, `max_text_length`. Niveles: −2 rechazado,
  −1 se pierde, 0 no, 1 parcial, 2 completo.
- **Discord** (puente viejo) no publica nada: tabla fija en Unichat (stickers y emojis propios se reciben; GIFs,
  respuestas, hilos, editar y borrar sin límite; reacciones con emojis propios ya vistos).
- **Packs:** API del puente `GET /v3/image_pack/list` (Telegram: todos los packs instalados; WhatsApp/Signal: vacía)
  e `import?pack_url=` (Telegram `t.me/addstickers|addemoji`, WhatsApp `wa.me/stickerpack/…`, Signal
  `signal.art/addstickers#…`). El puente escribe el pack como `m.room.image_pack` / `im.ponies.room_emotes`
  (MSC2545) en el espacio del usuario. Slack publica los emojis propios del espacio de trabajo como pack.
  Un sticker de pack lleva `info["fi.mau.bridged_sticker"]`; al reenviarlo, el puente manda el sticker nativo.
- **GIFs:** ninguna red ofrece búsqueda; todas aceptan GIF como `fi.mau.gif` (video mp4 o image/gif).

## Diseño (decidido)

### 1. Panel lateral
- Reemplaza al panel "Información" actual y conserva su contenido en la pestaña **Perfil** (nombre, datos,
  multimedia, participantes, acciones).
- Se abre desde el botón ⓘ del encabezado (Perfil), el botón de emoji del cuadro de mensaje (Emojis) y un botón
  nuevo de sticker/GIF (Stickers / GIFs). Cambiar de pestaña no lo cierra; Esc o el mismo botón lo cierran.
- Recuerda la última pestaña y el ancho; accesible por teclado (F6 lo incluye como zona) y con lector de pantalla.

### 2. Opciones según la red (`RoomFeatures` en Unichat)
- `SyncProcessor` lee `com.beeper.room_features` de cada sala; el chat guarda sus capacidades.
- Menú del mensaje: **Editar** y **Borrar para todos** solo si la red los permite y dentro del plazo
  (`edit_max_age`, `delete_max_age`); **Borrar para mí** donde exista; **Responder** según `reply`.
- Reacciones: si hay `allowed_reactions`, el selector muestra solo esas; si `reaction_count` es 1, elegir otra
  reemplaza la anterior.
- Cuadro de mensaje: los botones de adjuntar, sticker, GIF y nota de voz aparecen solo si la red los acepta; se avisa
  antes de mandar un archivo más grande que `max_size`.
- Sin `room_features` (servidor externo viejo o red desconocida): comportamiento actual.

### 3. Para todos los chats (no dependen de la red)
- **Guardar como…** en el menú de cada foto, video, audio, archivo y sticker, y botón en el visor.
- **Favoritos** (estrella en el visor y en el menú del mensaje) y **Recientes** de stickers y GIFs, guardados en
  Unichat (no en el servidor), usables en cualquier chat cuya red acepte ese tipo. Si no lo acepta, el ítem sale
  atenuado con la explicación.

### 4. Stickers y emojis
- Pestaña **Emojis:** los emojis de siempre + emojis propios de la red del chat (Slack, Telegram Premium) + en
  Discord, los propios ya vistos en ese servidor.
- Pestaña **Stickers:** Favoritos, Recientes y los packs de la red del chat (Telegram automático; WhatsApp y Signal
  con "Agregar pack por enlace").

### 5. GIFs con proveedores configurables
- Interfaz `GifProvider` (buscar, tendencias) con adaptadores: **Giphy** (primero), Klipy y Tenor.
- **Ajustes → GIFs:** activar proveedores y pegar la clave de cada uno; la búsqueda consulta los activos y mezcla
  los resultados. Agregar otro proveedor = un adaptador nuevo.
- Solo se envía al proveedor el texto buscado. El GIF elegido se descarga y se manda como `fi.mau.gif`.

## Orden de construcción
1. Panel lateral con Perfil (lo de hoy) + pestaña Emojis + "Guardar como…".
2. `room_features` → menús y botones por red.
3. Favoritos y recientes de stickers/GIFs + pestaña Stickers.
4. GIFs: Giphy + Ajustes → GIFs + Klipy/Tenor.
5. Packs por red (Telegram, WhatsApp/Signal por enlace, Slack) y emojis propios.

## Para quien lo construya
- Probar cada pieza de lógica (lectura de `room_features`, plazos de editar/borrar, filtro de reacciones, mezcla de
  proveedores) con pruebas unitarias; las pantallas con pruebas de interfaz en ventana invisible y capturas.
- Accesibilidad del proyecto: textos para lector de pantalla, teclado y alto contraste en todo lo nuevo.
- **Clave de Giphy:** la consigue el usuario (cuenta gratuita de desarrollador) y la pega en Ajustes; Unichat no
  trae una clave propia.
