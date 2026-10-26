# Grupos, servidores y contactos — diseño

Fecha: 2026-10-26 · Estado: propuesto

## Por qué

En Unichat los grupos de Telegram con temas y los servidores de Discord no se pueden usar. Además, faltan muchos
contactos: no aparecen en la bandeja, así que no se les puede escribir. El usuario quiere dos formas de ver cada
red:

- **Bandeja** (lo de hoy): quién te escribe, ordenado por actividad y con avisos.
- **Grupos**: tus servidores y grupos con sus canales o temas.

Además quiere poder hablarle a cualquier contacto de la red.

## Lo que encontramos (datos reales, 26-oct)

- Los puentes representan contenedores como **espacios de Matrix** (`m.room.create` con `type: m.space`, hijos en
  `m.space.child`). Hay tres casos:
  - la carpeta personal de cada red (Telegram: 15 hijos);
  - los grupos de Telegram con temas (foros), cuyos hijos son los temas;
  - en Discord (puente legado), un espacio por servidor con los canales como hijos, una vez que el servidor se
    activa con el botón "Servidores".
- Unichat no distingue espacios: los muestra como chats ("Chat vacío", sin poder escribir). Es el fallo que se ve
  en "los grupos de Telegram no funcionan".
- Límites de creación al iniciar sesión, en las configuraciones que genera `BridgeConfig`:
  - Telegram: `sync.create_limit: 15`, `login_sync_limit: 15`;
  - Discord: `startup_private_channel_create_limit: 5`;
  - WhatsApp: `history_sync.max_initial_conversations: 50`.
- API de provisioning de bridgev2 (`/_matrix/provision/v3/…`):
  - `GET contacts` (lista `{id, name, identifiers[], mxid, dm_room_mxid?}`);
  - `POST search_users {query}`;
  - `GET resolve_identifier/{id}`;
  - `POST create_dm/{id}`.
  - Probado en vivo: WhatsApp (158 contactos, búsqueda), Telegram (129 contactos de los que solo 4 tienen chat,
    búsqueda), Facebook e Instagram (solo búsqueda). X y Discord no tienen ninguna de las dos (404).

## Objetivos

1. **Espacios fuera de la bandeja.** Un espacio nunca aparece como chat. Sus hijos sí, como chats normales.
2. **Selector Bandeja | Grupos** en la cabecera de la lista de chats, recordado por red (`list.view.<red>`). Solo
   se muestra cuando la red tiene espacios con hijos.
3. **Vista Grupos.** Árbol en la lista:
   - Cada espacio es una fila desplegable (avatar, nombre, contador de no leídos que suma sus hijos).
   - Debajo van sus hijos: canales en Discord, temas en Telegram.
   - Un hijo que también es espacio se anida un nivel más.
   - Los chats sueltos que no cuelgan de ningún espacio que no sea la carpeta personal se agrupan al final en
     "Chats directos y otros". La carpeta personal no es un grupo: es el conjunto de todos.
   - Abrir un hijo funciona igual que desde la Bandeja.
   - Teclado: flechas para moverse, derecha/izquierda o Enter para desplegar, Enter en un chat para abrirlo.
   - Lector de pantalla: "Servidor X, 3 canales, 5 sin leer, contraído".
4. **Más sincronización.** Subir los límites en las configuraciones de los puentes:
   - Telegram `create_limit` y `login_sync_limit`: 100;
   - Discord `startup_private_channel_create_limit`: 50;
   - WhatsApp `max_initial_conversations`: 200.

   Los puentes ya respetan las esperas que pide cada red. Se aplica al regenerar la configuración, sin volver a
   iniciar sesión.
5. **Nuevo chat.** Un botón y un atajo (⌘N / Ctrl+N) abren un buscador de contactos:
   - Eliges la red; si hay varias cuentas, también la cuenta.
   - Aparece la lista de contactos (`contacts`) y, al escribir, los resultados de `search_users` (con 300 ms de
     espera entre teclas).
   - Al escribir un número de teléfono o un @usuario se ofrece "Escribir a <identificador>", que usa
     `resolve_identifier`.
   - Al elegir: si el contacto ya tiene chat (`dm_room_mxid`), se abre; si no, `create_dm` lo crea y se abre al
     llegar por sync.
   - Las redes que no tienen ni lista ni búsqueda (X, Discord) dicen por qué y cómo llegar a la persona (en
     Discord, por sus servidores).

## No objetivos

- Crear grupos nuevos (`create_group`) y gestionar miembros.
- Unirse a servidores de Discord o grupos de Telegram nuevos desde Unichat.
- Cambiar la sincronización de historial (backfill) más allá de los límites de creación.
- Discord sin servidores activados: sigue el selector de servidores que ya existe.

## Anti-objetivos

- Que la Bandeja cambie de comportamiento: filtros, archivados, avisos y orden se quedan como están.
- Mandar a la red algo más que la búsqueda que el usuario escribe.
- Que un puente sin estas funciones rompa la pantalla: se degrada con un mensaje claro.

## Enfoques considerados

- **A. Árbol en la lista (elegido por el usuario, siguiendo la recomendación).** Sirve igual para Discord y para
  Telegram, y la barra izquierda sigue siendo de redes. Coste: una lista con dos modos.
- B. Servidores en la barra izquierda. Es el estilo Discord, pero con muchos servidores la barra se vuelve
  inmanejable y no encaja con los temas de Telegram.
- Sincronización: **opción 2 (elegida)**, límites altos más "Nuevo chat" bajo demanda. Se descartó crear todo al
  iniciar sesión porque llena la bandeja lentamente y Telegram frena a quien pide mucho de golpe.

## Restricciones

- Java 21 + JavaFX; también Windows. El texto visible va en español.
- Accesibilidad (CLAUDE.md): texto accesible, alcanzable con teclado, funciona al 160 % y con alto contraste.
- Sin datos personales en los logs (Redactor).
- Pruebas: la lógica pura (árbol a partir de espacios, filtrado de espacios, análisis de contactos) con pruebas
  unitarias, y la interfaz con `-Dunichat.uiTests=true`.

## Respuestas a la revisión del diseño (decisiones mías, marcadas como tales)

1. **Cuenta en multicuenta:** el id de `Account` ya lleva el login del puente (`<protocolo>:<loginId>`, ver
   `MatrixChatService.accountId`). Las llamadas de contactos van con `?user_id=<yo>&login_id=<loginId>` y con el
   mismo token de Matrix que ya usa el inicio de sesión dinámico (`BridgeLogin`).
2. **Árbol:** un hijo cuenta si aparece en el `m.space.child` del espacio con contenido no vacío, o si declara
   `m.space.parent` hacia ese espacio. El filtro de sync de Unichat no limita los tipos de estado, así que esos
   eventos ya llegan. El plan empieza con una prueba que lo confirma con un sync grabado. Discord (puente legado)
   sigue el mismo modelo una vez activado un servidor; su "Nuevo chat" solo explica cómo llegar a la gente.
3. **Rutas YAML (verificadas en las configuraciones generadas hoy):**
   - Telegram: `network.sync.create_limit` y `network.sync.login_sync_limit`;
   - Discord: `bridge.startup_private_channel_create_limit`;
   - WhatsApp: `network.history_sync.max_initial_conversations`.

   La prueba de `BridgeConfig` comprueba que cada clave ya existía en la plantilla del puente, para que una ruta
   mal escrita falle en vez de crear una clave nueva que el puente ignora.
4. **Hijos a los que aún no te has unido:** se muestran atenuados con "Unirse". Al pulsarlo, Unichat entra al
   chat y lo abre. Los hijos que el sync no conoce (solo están en `m.space.child`) toman el nombre de
   `/hierarchy` del espacio.
5. **Carpeta personal:** es el espacio cuyo `m.bridge` tiene `channel.id` que empieza por `__` (bridgev2 la llama
   `__personal…`; en tus datos aparece como `__pe…`). No se muestra como grupo.
6. **No leídos de un espacio:** suma los hijos unidos, no archivados y no silenciados. El texto para el lector de
   pantalla dice esa misma cifra.
7. **⌘N / Ctrl+N:** no está usado en `MainView`.

## Decisiones dejadas al constructor

- Cómo se representa internamente el árbol (modelo `SpaceTree` o similar), siempre que salga de los eventos
  `m.space.child` / `m.space.parent` del sync.
- El diseño exacto de la fila de espacio y del diálogo Nuevo chat, siguiendo el estilo de `AddAccountPane` y
  `BridgeLoginPane`.
- El orden dentro de un espacio: el `order` de `m.space.child` si existe; si no, el nombre.
