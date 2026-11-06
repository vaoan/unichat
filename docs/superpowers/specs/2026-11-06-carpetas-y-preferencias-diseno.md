# Carpetas y preferencias portátiles — diseño

Fecha: 2026-11-06 · Estado: propuesto

## Por qué

El usuario quiere organizar sus chats en carpetas propias, como en Discord o Telegram, y llevarse sus preferencias a
cualquier instalación copiando archivos. Los mensajes se pueden volver a descargar y da igual dónde vivan. Lo que no
se puede recuperar es lo que la persona decidió (quién está archivado, qué hay en cada carpeta), y hoy casi todo eso
vive en el servidor Matrix local de cada máquina.

Éxito: en un Unichat recién instalado, sin copiar el servidor, se copian los archivos de preferencias, se vinculan las
cuentas y, a medida que los chats aparecen, cada uno queda archivado, favorito o dentro de sus carpetas como antes.

## Objetivos

1. Un icono de carpetas en el riel, justo encima de Ajustes.
2. Carpetas creadas por la persona; un chat puede estar en varias.
3. Toda preferencia vive en archivos que se pueden copiar; los secretos, aparte.
4. Las preferencias valen en una instalación nueva: se guardan por la identidad del chat en su red, no por el id de sala.

## Fuera de alcance

- Sincronizar preferencias sola entre máquinas: se copian los archivos a mano.
- Botón de exportar o importar.
- Sincronizar con el teléfono más de lo que ya se hace (el archivado sigue siendo de una sola vía).
- Reordenar carpetas o chats dentro de ellas, y carpetas dentro de carpetas.
- Copiar mensajes o medios.

## Lo que no debe pasar

- Perder preferencias en silencio, por ejemplo sobrescribiendo un archivo que no se pudo leer.
- Que el archivo que se copia lleve un token o una contraseña.
- Que eliminar una carpeta toque los chats que contiene.

## Enfoques considerados

1. **El archivo es la única fuente de verdad (elegido por el usuario).** Unichat lee y escribe ahí y deja de escribir
   esas preferencias en Matrix. Lo que llega del teléfono entra como dato de entrada.
2. **El archivo manda y además se copia a Matrix.** Mantiene al día otros clientes Matrix, que el usuario no usa, a
   cambio de dos copias que pueden discrepar.
3. **Matrix manda, con exportar e importar.** Es lo más barato, pero es una foto que hay que acordarse de tomar y no
   cumple "las preferencias viven en archivos".

Para las carpetas, guardarlas en una etiqueta por chat se descartó: no permite carpetas vacías ni orden.

## Archivos

Todos en `Settings.dataDir()`.

| Archivo | Contenido | ¿Se copia? |
|---|---|---|
| `preferences.json` (nuevo) | Preferencias por chat y carpetas | Sí |
| `settings.properties` | Ajustes de la app, ya sin sesión ni secretos | Sí |
| `media-library.json` | Stickers y GIF guardados | Sí |
| `session.properties` (nuevo) | Sesión, secretos y lo propio de esta máquina | **No** |
| `server-seen.json` (nuevo) | Qué ha dicho el servidor de esta máquina (ver Reglas) | **No** |

`session.properties` guarda las claves `matrix.*`, `session.*`, `server.*`, `window.*`, `discord.guilds.entire`,
`discord.guilds.paused` y `gif.<id>.key`. Las de Discord son el registro de lo ya pedido a un puente concreto: copiadas,
una instalación nueva no cargaría los canales ni pausaría servidores archivados. Las claves de GIF son secretos: se vuelven
a escribir en cada máquina. Las demás siguen en `settings.properties`. Al arrancar por primera vez, esas claves se mueven desde `settings.properties`, de forma que un
fallo a mitad no pierda la sesión (primero se escribe el archivo nuevo). En Mac y Linux, `session.properties` queda con
permisos 0600. `Settings` conserva su API: quien lo usa no cambia.

`settings.properties.docker-backup` puede contener un token viejo: la migración lo borra.

### `preferences.json`

```json
{
  "version": 1,
  "chats": {
    "telegram:123456": { "archived": true, "muted": true, "fromServer": ["archived"] },
    "whatsapp:573001234567@s.whatsapp.net": { "favorite": true, "pinned": true },
    "matrix:!abc:ejemplo.org": { "lowPriority": true, "reminderAt": "2026-11-07T14:00:00Z" }
  },
  "folders": [
    { "id": "f3a1", "name": "Trabajo", "chats": ["telegram:123456", "matrix:!abc:ejemplo.org"] }
  ]
}
```

- Se conserva un `false` explícito: significa "la persona lo decidió" (ver Reglas).
- `fromServer` lista los campos copiados del servidor que la persona aún no ha tocado en Unichat.
- Las entradas de chats que aún no aparecen en esta instalación se conservan intactas.
- Escritura atómica (archivo temporal y mover).
- Si el archivo no se puede leer, se renombra a `preferences.json.bad-<fecha>`, se avisa a la persona con un mensaje
  visible y se arranca vacío. Una versión futura desconocida se trata igual.

### Identidad del chat

Clave `"<protocolo>:<channel.id>"`, con los valores de `m.bridge` / `uk.half-shot.bridge` que `SyncProcessor` ya lee
(`room.bridgeProtocol`, `room.bridgeChannelId`). Los chats sin esos datos usan `"matrix:<id de sala>"`.

- Una sala puenteada cuyo estado de puente aún no llegó no debe registrar nada bajo `matrix:`: si la clave de un chat
  cambia de `matrix:` a la del puente, se mueve su entrada, también dentro de las carpetas.
- La clave no incluye la cuenta: dos cuentas de una misma red comparten preferencias en los grupos y contactos que tengan
  en común, también en mensajes directos. Se acepta: hoy el usuario tiene una sola cuenta por red (decidido con él). Quien
  planee añadir una segunda cuenta debe saber que las claves guardadas dependen de esto.

## Reglas de preferencias

Valen para archivado, silenciado, favorito, prioridad baja, fijado y recordatorio.

- **Unichat ya no los escribe en Matrix**, salvo la regla de silencio (abajo). Lo que elige la persona se escribe solo en
  `preferences.json` y saca ese campo de `fromServer`.
- **Lectura del servidor (`m.tag`, `m.push_rules`, recordatorio):** si el campo no está en el archivo, o está en
  `fromServer`, se sigue lo que llega del servidor, en ambos sentidos, y queda en `fromServer`. Si la persona ya lo tocó en
  Unichat, el archivo gana y lo que llega se ignora.
- **Un servidor nuevo no borra nada:** un `false` del servidor solo cuenta si ese mismo servidor antes dijo `true`. Un
  servidor recién instalado no tiene etiquetas, y sin esto todo lo copiado del viejo se perdería. Esa memoria va en
  `server-seen.json` (nuevo, propio de la máquina, no se copia). Si aún no llegó ningún dato del servidor sobre un chat, no
  se cambia nada.
- **Recordatorios:** el valor del servidor solo entra si el campo no está en el archivo. Al dispararse o quitarse, queda
  `"reminderAt": null`, para que el viejo valor del servidor no vuelva a dispararlo.
- Esa única regla cubre tres casos sin código aparte: la migración (lo que ya está en el servidor entra la primera vez),
  lo que se hace en el teléfono, y una instalación nueva.
- Consecuencia aceptada: si la persona ya tocó un campo de un chat en Unichat, lo que haga después con ese campo en el
  teléfono no cambia nada aquí. Si no lo tocó, Unichat sigue al teléfono, también al quitar el archivado.
- **Excepción, la regla de silencio:** los contadores de no leídos solo vienen del servidor (`unread_notifications`), y
  una regla de silencio que se queda allí deja el chat sin contador para siempre. Por eso el archivo sigue siendo la verdad,
  pero Unichat aplica al servidor el valor de `muted` del archivo: al cambiarlo y cuando un chat aparece en una instalación
  nueva. Hay que evitar el bucle: esa regla, escrita por Unichat, no cuenta como un cambio hecho en el teléfono.
- `QuietRule` sigue igual: silenciado es archivado, y un servidor arrastra a sus canales.
- **Sigue en el servidor:** no leído (`m.marked_unread`), que es estado del mensaje y no preferencia, y la pausa de
  servidores de Discord (`setGuildPaused`), que es una orden al puente.
- El constructor debe confirmar que `Notifier` decide por `Chat.muted` y no por lo que devuelve el servidor.

El modo demo (`MockChatService`) usa el mismo almacén, para poder probar y fotografiar la interfaz.

## Interfaz

Texto en español. Sigue las reglas de accesibilidad de `CLAUDE.md`: texto accesible, teclado, 160 % y alto contraste.

**Riel.** Botón de carpeta justo encima de Ajustes (`Icons` aún no tiene el icono), marcado como activo mientras se está
en carpetas. Sin contador: cada carpeta muestra el suyo y sumarlos contaría doble a los chats que están en varias.

**Lista de carpetas.** Al pulsarlo, la columna de chats muestra la lista de nombres, cada uno con su número de chats sin
leer, y un botón "Nueva carpeta" que pide el nombre. Con cero carpetas, un texto explica cómo crear la primera. Clic
derecho sobre una carpeta: Renombrar y Eliminar (Eliminar pide confirmación y dice que los chats no se borran).

**Dentro de una carpeta.** Como la vista de canales de un servidor: se sustituye la lista, con cabecera con botón de
volver y el nombre. No es un acordeón. Los chats usan la misma celda y el mismo orden que la bandeja. Abrir un chat no
saca de la carpeta. Volver con el botón, Esc o flecha izquierda. Escribir en la búsqueda sale de la carpeta, como ya pasa
con un servidor. Las etiquetas de filtro no se muestran dentro.

**Qué se ve dentro.** Todo lo que la persona puso, también archivados: si lo metió en una carpeta es porque quiere verlo
ahí. Los chats que aún no existen en esta instalación no se muestran, pero se conservan.

**Añadir y quitar.** En el menú contextual de un chat (`ChatMenus`), un submenú "Carpetas" con cada carpeta marcada o no,
y "Nueva carpeta…". Marcar y desmarcar añade y quita, así un chat puede estar en varias. Dentro de una carpeta, además
"Quitar de esta carpeta". Un servidor de Discord también se puede añadir; abrirlo lleva a sus canales. No se ofrece en
bots de puente ni en el espacio personal.

## Estructura

- `ChatListPane` ya tiene 723 líneas (límite 800): las carpetas van en clases nuevas de `ui`, reutilizando `ChatCell`.
- Rol nuevo en `service` para las carpetas (listar, crear, renombrar, eliminar, añadir, quitar), parte de `ChatService`.
- El almacén de `preferences.json` y sus reglas, sin JavaFX y probado con pruebas unitarias. Las capas siguen
  `docs/ARQUITECTURA.md`.

## Pruebas que importan

- Instalación nueva: con un `preferences.json` y salas nuevas (otros ids, mismo `channel.id`), los chats recuperan todo.
- Reglas: lo que llega del servidor se sigue en ambos sentidos mientras la persona no toque el campo; después se ignora, y
  un `false` explícito resiste.
- Silencio: quitarlo en Unichat devuelve los contadores; en una instalación nueva, lo silenciado no muestra contador.
- Archivo dañado: se guarda aparte, no se sobrescribe y se avisa.
- Migración de `settings.properties`: la sesión sigue funcionando; `settings.properties` queda sin token; repetirla no daña
  nada.
- Interfaz: crear, entrar, volver, añadir un chat a dos carpetas, eliminar una carpeta y comprobar que el chat sigue ahí.

## Riesgo conocido

Los stickers guardados en `media-library.json` pueden apuntar a medios del puente de la máquina original
(`mxc://media-<puente>.localhost/…`). En una instalación nueva quizá no carguen hasta volver a verlos. No se resuelve aquí.

## Queda a criterio del constructor

Nombres de clases, forma del id de carpeta, cuándo guardar a disco para no escribir de más, y cómo avisar del archivo
dañado dentro del patrón de avisos existente.
