# Carpetas y preferencias portátiles: plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Carpetas de chats en el riel y preferencias que viven en archivos copiables y valen en una instalación nueva.

**Architecture:** `service/PreferencesFile` es la fuente de verdad, sin JavaFX, indexada por la identidad del chat en su red. `SyncProcessor` le pasa lo que dice el servidor y aplica al `Chat` lo que ella responde. La interfaz solo conoce el rol nuevo `ChatFolders`.

**Tech Stack:** Java 21, JavaFX 23, Jackson, JUnit 5, TestFX.

**Spec:** `docs/superpowers/specs/2026-11-06-carpetas-y-preferencias-diseno.md`

## Global Constraints

- Texto de la interfaz y documentos en español; código, comentarios y commits en inglés.
- Archivos Java < 800 líneas y métodos < 100 (Checkstyle). `ChatListPane` ya tiene 723: lo nuevo va en clases propias.
- Capas de `docs/ARQUITECTURA.md`: `service` puede usar `model`, `settings`, `login`; JavaFX solo en `ui`.
- Debe seguir funcionando en Windows: nada de permisos POSIX sin comprobar que el sistema de archivos los admite.
- Ningún secreto en `settings.properties` ni `preferences.json`; nunca imprimir tokens en registros ni pruebas.
- Las pruebas nunca tocan el directorio real de datos (surefire fija `unichat.dataDir`).
- Controles nuevos: texto accesible, alcanzables con teclado, y deben aguantar 160 % y alto contraste.
- **Sin commits durante la ejecución salvo que el usuario lo pida.** Hay 8 archivos de otro trabajo sin confirmar (login de LinkedIn, incluido `CLAUDE.md`): no mezclarlos.

## Review Focus

1. Un nombre de carpeta vacío, solo espacios, repetido o muy largo: vacío se rechaza, repetido se permite, largo se recorta con elipsis sin romper la columna a 160 %.
2. La persona está dentro de una carpeta y la elimina, o sale de un chat que estaba en ella: vuelve a la lista de carpetas sin errores; el chat abandonado deja de verse, y su entrada se conserva.
3. Un recordatorio ya disparado cuyo valor sigue en el servidor: no vuelve a dispararse tras reiniciar.
4. Un `preferences.json` copiado con chats que aún no existen, y luego esos chats llegan en un `/sync` posterior sin `m.tag`: reciben sus preferencias al aparecer.
5. Disco lleno o sin permiso al guardar: la app sigue funcionando con lo que hay en memoria y el archivo anterior queda intacto.

---

### Task 1: Separar sesión y secretos de `settings.properties`

**Files:**
- Modify: `src/main/java/app/unichat/settings/Settings.java`
- Test: `src/test/java/app/unichat/settings/SettingsSplitTest.java`

**Interfaces:**
- Produces: `Settings` conserva toda su API. `static boolean Settings.staysOnThisMachine(String key)` (paquete).

- [ ] **Step 1: Escribir las pruebas que fallan** (el constructor `Settings(Path)` es de paquete)

```java
@Test void machineKeysLiveApart() {
    var s = new Settings(dir);
    s.setMatrixSession("https://h", "@a:h", "TOKEN", "DEV");
    s.set("ui.theme", "DARK");
    s.set("gif.tenor.key", "K");
    s.set("discord.guilds.paused", "1");
    assertFalse(read("settings.properties").contains("TOKEN"));
    assertTrue(read("settings.properties").contains("ui.theme"));
    assertTrue(read("session.properties").contains("TOKEN"));
    assertTrue(read("session.properties").contains("gif.tenor.key"));
    assertEquals("TOKEN", new Settings(dir).accessToken());
}
@Test void anOldFileIsMigratedOnceAndTwiceIsHarmless() { /* settings.properties con matrix.accessToken y ui.theme; abrir dos veces:
    token solo en session.properties, tema solo en settings.properties, accessToken() correcto; settings.properties.docker-backup borrado */ }
@Test void aCrashBetweenWritesKeepsTheSession() { /* existen ambos archivos con el token en los dos: gana session.properties y se limpia el otro */ }
@Test void aFailedSaveKeepsThePreviousFile() { /* dir de solo lectura (assumeTrue si no aplica): set() no lanza, el archivo sigue igual */ }
@Test void clearSessionEmptiesTheSessionFile() { }
```

- [ ] **Step 2: Ejecutar y ver que fallan.** `./mvnw -q test -Dtest=SettingsSplitTest` → FAIL.

- [ ] **Step 3: Implementar.** Dos `Properties`; `getString` y `set` eligen por clave. `staysOnThisMachine`: prefijos `matrix.`, `session.`, `server.`, `window.`, `discord.guilds.`; o empieza por `gif.` y termina en `.key`. Confirmar con `grep` los nombres reales de esas claves (`DiscordServers`, `GifProviders`). Migración en el constructor: escribir primero `session.properties` y luego reescribir `settings.properties`. `session.properties` con 0600 si `PosixFileAttributeView` está disponible. Borrar `settings.properties.docker-backup`.

- [ ] **Step 4: Ejecutar.** `./mvnw -q test -Dtest=SettingsSplitTest` → PASS.

### Task 2: `PreferencesFile`: reglas, carpetas y disco

**Files:**
- Create: `src/main/java/app/unichat/service/PreferencesFile.java`, `src/main/java/app/unichat/service/ChatKeys.java`
- Test: `src/test/java/app/unichat/service/PreferencesFileTest.java`

**Interfaces:**
- Produces:

```java
public final class ChatKeys {
    /** "<protocol>:<channel>" when both are known, else "matrix:<roomId>". */
    public static String of(String protocol, String channelId, String roomId);
}
public final class PreferencesFile {
    public enum Flag { ARCHIVED, MUTED, FAVORITE, LOW_PRIORITY, PINNED }
    /** What the server says; null = nothing heard about it yet. */
    public record ServerValues(Boolean archived, Boolean muted, Boolean favorite, Boolean lowPriority, Boolean pinned,
                               Instant reminder, boolean reminderKnown) {}
    public record Effective(boolean archived, boolean muted, boolean favorite, boolean lowPriority, boolean pinned,
                            Instant reminder, boolean muteChosenHere) {}
    public record Folder(String id, String name, List<String> chatKeys) {}

    public PreferencesFile(Path preferences, Path serverSeen);
    public static PreferencesFile inMemory();
    /** The name the unreadable file was set aside under, or null. */
    public String setAside();
    public synchronized Effective reconcile(String key, ServerValues server);
    public synchronized Effective current(String key);
    public synchronized void choose(String key, Flag flag, boolean value);
    /** Set by a rule, not the person (a mute from the phone archives): the person hasn't touched it. */
    public synchronized void follow(String key, Flag flag, boolean value);
    public synchronized void chooseReminder(String key, Instant at);
    public synchronized void rekey(String oldKey, String newKey);
    /** Writes what reconcile changed, if anything. */
    public synchronized void flush();
    public synchronized List<Folder> folders();
    public synchronized String createFolder(String name);
    public synchronized void renameFolder(String id, String name);
    public synchronized void deleteFolder(String id);
    public synchronized void setInFolder(String folderId, String key, boolean in);
}
```

- [ ] **Step 1: Escribir las pruebas que fallan**, con estos nombres y afirmaciones:

```java
// k = "telegram:1"; on(...) = ServerValues con solo ese flag en true y el resto false; unknown = todo null
serverValueEntersWhenTheFileHasNothing:        reconcile(k, on(ARCHIVED)).archived() == true; el archivo guardado tiene fromServer ["archived"]
followsThePhoneBothWaysUntilTouched:           on → true; luego off (mismo servidor) → false
aNewServerDoesNotEraseWhatWasCopied:           A: reconcile on, flush. B: copiar solo preferences.json, seen nuevo. reconcile(off).archived() == true
aPersonsChoiceBeatsTheServer:                  choose(k, ARCHIVED, false); reconcile(on).archived() == false; y lo mismo con true y off
choosingRemovesFromServerMarker:               on, choose(k, ARCHIVED, true): el archivo no lista fromServer; reconcile(off) sigue true
explicitFalseSurvivesARestart:                 choose false; recargar; reconcile(on).archived() == false
nothingHeardChangesNothing:                    choose true; reconcile(unknown).archived() == true; y sin entrada, sin entrada creada
followedValueIsNotAChoice:                     follow(k, ARCHIVED, true); reconcile(on) y luego (off) → false
muteChosenHereIsReported:                      choose(k, MUTED, true): muteChosenHere true; on(MUTED): false
reminderImportedOnlyWhenAbsent:                reconcile con ts → ese ts; chooseReminder(k, null); reconcile con ts → null, también tras recargar
unknownChatEntriesAreKept:                     cargar archivo con "whatsapp:9"; choose en otro; guardar: "whatsapp:9" sigue
rekeyMovesEntryAndFolders:                     choose + setInFolder con "matrix:!a"; rekey a "telegram:1": current y carpeta lo reflejan; rekey sobre clave existente no pisa campos ya elegidos
aChatCanBeInSeveralFolders:                    dos carpetas lo contienen; quitarlo de una deja la otra
folderNamesMustNotBeBlank:                     createFolder("  ") lanza IllegalArgumentException; "A" dos veces da dos carpetas; el nombre se guarda sin espacios a los lados
deletingAFolderKeepsTheChatsPrefs:             choose + setInFolder + deleteFolder: current no cambia
damagedFileIsSetAsideNotOverwritten:           "{roto" → setAside() no nulo, ese archivo conserva el contenido; arranca vacío; un cambio escribe un preferences.json válido
unknownVersionIsSetAside:                      {"version":99} → igual
aFailedWriteKeepsTheOldFileAndMemory:          carpeta de solo lectura (assumeTrue): choose no lanza; current refleja el cambio; el archivo sigue igual
flushWritesOnlyWhenSomethingChanged:           dos flush sin cambios no alteran la fecha del archivo
chatKeys:                                      of("telegram","1","!r") == "telegram:1"; of(null,"1","!r") == "matrix:!r"; of("telegram",null,"!r") == "matrix:!r"
```

- [ ] **Step 2: Ejecutar y ver que fallan.** `./mvnw -q test -Dtest=PreferencesFileTest` → FAIL.

- [ ] **Step 3: Implementar.** Formato exacto de la spec, sección `preferences.json`; campos JSON `archived, muted, favorite, lowPriority, pinned, reminderAt, fromServer`. `"reminderAt": null` significa "tocado". Regla de `reconcile`, para cada flag con valor del servidor no nulo:

```text
if server == true: seen.add(key, flag)
tocado = el campo está en el archivo y no está en fromServer
if tocado: ignorar
if server == true: valor = true, en fromServer
if server == false y seen.contains(key, flag): valor = false, en fromServer; seen.remove(key, flag)
```

Escritura atómica (temporal y `ATOMIC_MOVE`); errores de disco se registran con `System.err` sin lanzar. `choose`, `chooseReminder` y las carpetas guardan al momento; `reconcile` solo marca pendiente hasta `flush`. `rekey` conserva lo ya elegido en el destino. `inMemory()` no toca disco.

- [ ] **Step 4: Ejecutar.** `./mvnw -q test -Dtest=PreferencesFileTest` → PASS.

### Task 3: Conectar Matrix al archivo

**Files:**
- Modify: `matrix/MatrixRoom.java`, `matrix/SyncProcessor.java`, `matrix/ChatActions.java`, `matrix/MatrixChatService.java`, `model/Chat.java`
- Test: `src/test/java/app/unichat/matrix/PreferencesSyncTest.java`

**Interfaces:**
- Consumes: `PreferencesFile`, `ChatKeys` (Task 2).
- Produces: `Chat.prefKey()` / `setPrefKey(String)`. `SyncProcessor.setPreferences(PreferencesFile)` (por defecto `inMemory()`, así las pruebas existentes no cambian). `SyncProcessor.mutesToApply()` devuelve `Map<String, Boolean>` de sala a silencio.

- [ ] **Step 1: Escribir las pruebas que fallan**, con `/sync` en JSON como en `SyncProcessorTest`:

```java
tagsOnlyEnterThroughTheFile:           m.tag con m.favourite → chat.favorite(); tras choose(key, FAVORITE, false) y otro m.tag igual → false
prefKeyUsesTheBridgeIdentity:          sala con uk.half-shot.bridge protocol telegram, channel 7 → prefKey "telegram:7"
sameChatOnAnotherServerGetsItsPrefs:   archivo con "telegram:7" archived; proc nuevo con sala de otro id y channel 7 → chat.archived()
aChatThatArrivesLaterGetsItsPrefs:     primer /sync sin esa sala; el siguiente la trae sin m.tag → archived
bridgeStateArrivingLaterRekeys:        sala sin m.bridge + choose; siguiente /sync con m.bridge → la entrada ya está en la clave nueva
noAccountDataChangesNothing:           archivo archived, /sync sin account_data de la sala → sigue archived
aMuteChosenHereIsAppliedToTheServer:   archivo muted elegido, sin regla en servidor → mutesToApply tiene sala → true; con la regla ya presente → vacío
aMuteFromThePhoneIsNotPushedBack:      regla en m.push_rules sin entrada → chat.muted() y mutesToApply vacío
```

- [ ] **Step 2: Ejecutar y ver que fallan.** `./mvnw -q test -Dtest=PreferencesSyncTest` → FAIL.

- [ ] **Step 3: `SyncProcessor`.** `applyRoomAccountData` y `m.push_rules` dejan de escribir en `Chat`: guardan los valores crudos en `MatrixRoom` (nulos hasta oírlos, y `lastPrefKey`). Al final de `process`, para cada sala: calcular la clave con `ChatKeys.of(room.bridgeProtocol, room.bridgeChannelId, room.id)`, `rekey` si cambió, `reconcile`, aplicar `Effective` a `Chat` y `setPrefKey`, y luego `flush` una vez. `setMutedLocal` deja de existir como vía de cambio de preferencia. `mutesToApply`: salas con `muteChosenHere` cuyo valor difiere de la regla oída; una sala queda fuera mientras su escritura esté en curso.

- [ ] **Step 4: `ChatActions`.** `setFavorite`, `setLowPriority`, `setPinned`, `archiveOnly`, `muteOnly`, `setReminder` ya no llaman a `setTag`/`putRoomAccountData`: `prefs.choose…`, aplicar a `Chat`, `core.fire(chatsChanged)`, futuro completado. `muteOnly` además escribe la regla en el servidor; si falla, no revierte (se reintenta por `mutesToApply`). Quitar `optimistic` si queda sin uso; `setMarkedUnread` y `setGuildPaused` no cambian.

- [ ] **Step 5: `MatrixChatService`.** Crear `PreferencesFile(dataDir/preferences.json, dataDir/server-seen.json)` y dársela a `proc` y `chatActions`. Tras cada `proc.process`, aplicar `mutesToApply`. El `archiveOnly(id, true)` de `mutedElsewhere` pasa a `prefs.follow(key, ARCHIVED, true)`. Confirmar con `grep` que `Notifier` y `NotificationPolicy` deciden por `Chat.muted`/`archived`.

- [ ] **Step 6: Ejecutar.** `./mvnw -q test -Dtest='PreferencesSyncTest,SyncProcessorTest,SpaceSyncTest,RoomFeaturesSyncTest'` → PASS. Si una prueba existente afirmaba escrituras en Matrix, actualizarla y decirlo en el informe de la tarea.

### Task 4: El rol `ChatFolders` en los dos servicios

**Files:**
- Create: `service/ChatFolders.java`, `service/FolderActions.java`
- Modify: `service/ChatService.java`, `service/MockChatService.java`, `matrix/MatrixChatService.java`
- Test: `src/test/java/app/unichat/service/FoldersServiceTest.java`

**Interfaces:**
- Consumes: `PreferencesFile`, `Chat.prefKey()`.
- Produces:

```java
public interface ChatFolders {
    List<PreferencesFile.Folder> folders();
    String createFolder(String name);
    void renameFolder(String id, String name);
    void deleteFolder(String id);
    void setInFolder(String folderId, Chat chat, boolean in);
    /** Message for the person if the preferences file was unreadable and set aside; else null. */
    String preferencesWarning();
}
```

`FolderActions`: `new FolderActions(PreferencesFile, Runnable fireChatsChanged)`, implementa `ChatFolders`; cada servicio delega en ella. Cada cambio avisa con `chatsChanged`.

- [ ] **Step 1: Escribir las pruebas que fallan** sobre `MockChatService` con `unichat.dataDir` temporal: `createFolder` avisa a un `Listener`; `setInFolder` deja la clave del chat; una carpeta sobrevive a recrear el servicio; `setFavorite` en demo queda en el archivo y sobrevive a recrear el servicio; `preferencesWarning()` no nulo con un archivo dañado y contiene su nombre.

- [ ] **Step 2: Ejecutar y ver que fallan.** `./mvnw -q test -Dtest=FoldersServiceTest` → FAIL.

- [ ] **Step 3: Implementar.** `MockChatService` usa el mismo `PreferencesFile` y fija `prefKey` = `"matrix:" + id`; sus setters pasan por `choose` y aplican `current` al cargar los chats demo. `ChatService` añade el rol `ChatFolders`.

- [ ] **Step 4: Ejecutar.** `./mvnw -q test -Dtest=FoldersServiceTest` → PASS, luego `./mvnw -q test -Dtest='ArchitectureTest'` → PASS.

### Task 5: La interfaz de carpetas

**Files:**
- Create: `ui/FolderView.java`, `ui/FolderRows.java`, `ui/FolderDialogs.java`
- Modify: `ui/Rail.java`, `ui/ChatListPane.java`, `ui/ChatMenus.java`, `ui/Icons.java`, `ui/MainView.java`, `css/app.css`
- Test: `src/test/java/app/unichat/ui/FolderRowsTest.java`, `src/test/java/app/unichat/ui/FoldersUiTest.java`

**Interfaces:**
- Consumes: `ChatFolders` (Task 4).
- Produces: `ChatListPane.showFolders()`, `ChatListPane.foldersOpen()`. `FolderRows.chatsIn(Folder, List<Chat>) -> List<Chat>`, `FolderRows.unread(Folder, List<Chat>) -> long`. `FolderView(ChatFolders, Supplier<ListCell<Chat>> cells, Consumer<Chat> open)`, porque `ChatCell` es interna de `ChatListPane`.

- [ ] **Step 1: Escribir las pruebas que fallan.** `FolderRowsTest`: conserva archivados; no incluye chats ausentes ni bots de puente; orden fijados primero y luego actividad, como `InboxRows:53`; `unread` usa `ChatFilter.inboxUnread`. `FoldersUiTest` (`-Dunichat.uiTests=true`, corre con `-Pheadless-ui`):

```java
theFoldersButtonSitsRightAboveSettings:   en Rail, el índice del botón "Carpetas" == índice de "Ajustes" - 1
clickingShowsNamesAndClickingANameGoesIn: crear carpetas, pulsar: se ven los nombres; pulsar uno: se ven sus chats y el título; la lista de carpetas ya no se ve
goingBackReturnsToTheNames:               botón volver y también Esc
openingAChatStaysInTheFolder:             abrir chat: el hilo lo muestra y seguimos dentro
aChatCanBeInTwoFolders:                   setInFolder en dos; aparece en ambas
deletingTheOpenFolderGoesBackToTheNames:  sin excepción
leavingAChatInsideAFolderJustHidesIt:     leaveChat: deja de verse; la clave sigue en la carpeta
searchingLeavesTheFolder:                 escribir en la búsqueda muestra resultados normales
theInboxButtonLeavesFolders:              pulsar Bandeja cierra carpetas; Carpetas marcado activo solo dentro
menuTogglesMembership:                    ChatMenus tiene "Carpetas" con cada una marcada; bots y espacio personal no lo ofrecen
```

- [ ] **Step 2: Ejecutar y ver que fallan.** `./mvnw -q verify -Pheadless-ui -Dtest='FolderRowsTest,FoldersUiTest' -Dsurefire.failIfNoSpecifiedTests=false` → FAIL.

- [ ] **Step 3: Implementar.** Según la sección Interfaz de la spec, con estos textos: tooltip `Carpetas`; botón `Nueva carpeta`; diálogo `Nombre de la carpeta`; vacío `Aún no tienes carpetas. Crea una y añade chats con clic derecho.`; acciones `Renombrar`, `Eliminar`; confirmación `¿Eliminar la carpeta «X»? Los chats no se borran.`; submenú `Carpetas` con `Nueva carpeta…`; dentro de una carpeta `Quitar de esta carpeta`. `CheckMenuItem` para cada carpeta. El botón va tras el `grow` de `Rail` y antes de `settings`, sin contador. `FolderView` se añade a `ChatListPane` como la `spaceView`, con el mismo cambio de visibilidad. Al abrir un espacio desde una carpeta, usar la `spaceView` existente. Nombres largos con elipsis. `MainView`: comando `Ver carpetas` en `CommandBar`, y al arrancar, si `preferencesWarning()` no es nulo, mostrarlo con `toast`. Añadir `-Dunichat.panel`-style `-Dunichat.openFolders=true` para las capturas.

- [ ] **Step 4: Ejecutar.** El comando del Step 2 → PASS.

- [ ] **Step 5: Comprobar visualmente.** En un directorio temporal con `session.backend=demo` y un `preferences.json` con dos carpetas: `JAVA_OPTS="-Dunichat.dataDir=<dir> -Dunichat.openFolders=true -Dunichat.snapshot=out.png" ./run-dev.sh`; leer la imagen. Repetir en tema claro, oscuro, 160 % y alto contraste, y dentro de una carpeta.

### Task 6: Documentar, verificar e instalar

**Files:**
- Modify: `CLAUDE.md`, `docs/ARQUITECTURA.md`, `README.md` si describe dónde viven las preferencias

- [ ] **Step 1: Documentar.** En `CLAUDE.md`, qué archivo guarda qué, qué se copia y qué no, la regla de `server-seen.json` y el rol `ChatFolders`.

- [ ] **Step 2: Verificación completa.** `./mvnw -q verify -Pheadless-ui` → exit 0. Citar el resultado real; `AutoScrollUiTest` y otros inestables conocidos se reportan como tales, no se ocultan.

- [ ] **Step 3: Copia de seguridad antes de migrar los datos reales.** La migración toca el token de la persona: copiar `settings.properties` y `media-library.json` a `~/Library/Caches/unichat-prefs-backup-<fecha>/` con permisos 0700, sin imprimir su contenido.

- [ ] **Step 4: Instalar.** `TUWUNEL_BIN=$HOME/Library/Caches/unichat-build/tuwunel/target/release/tuwunel ./install-mac.sh`, luego `open /Applications/Unichat.app` (el instalador no la arranca) y reactivar el volcado con `jinfo`. Instalar sin `TUWUNEL_BIN` deja la app sin servidor.

- [ ] **Step 5: Comprobar con los datos reales, sin imprimir valores.** Puentes corriendo; ventana visible; la sesión sigue activa; `grep -c accessToken settings.properties` da 0; `preferences.json` se creó; el número de chats con `"archived": true` coincide con los que había archivados antes; ningún chat archivado reapareció en la bandeja. Pedir a la persona que cree una carpeta y confirme que sobrevive a reiniciar.
