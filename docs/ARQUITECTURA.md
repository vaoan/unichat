# Arquitectura

Unichat es una app de escritorio (Java 21 + JavaFX). La UI solo conoce `ChatService`; detrás está Matrix con los
puentes mautrix, o el modo demo.

## Capas

Cada paquete solo puede depender de los que están debajo. `ArchitectureTest` lo comprueba en cada `./mvnw test`.

| Paquete | Qué hace | Puede usar |
|---|---|---|
| raíz (`app.unichat`) | Arranque: crea y conecta todo. Nadie depende de ella | todo |
| `ui` | Pantallas JavaFX. Único paquete (además de la raíz) que usa JavaFX | lo de abajo, salvo `matrix` |
| `matrix` | `ChatService` sobre Matrix | `service`, `bridge`, `gif`, `links`, `login`, `model`, `settings`, `support` |
| `service` | La API que usa la UI (`ChatService`, `BridgeAccess`) y el modo demo | `login`, `model`, `settings` |
| `server` | Servidor local: instala, arranca y vigila tuwunel y los puentes | `bridge`, `settings`, `support` |
| `stt` | Voz a texto | `settings`, `support` |
| `assist` | Borradores de respuesta con el CLI de IA de la persona | `model`, `settings`, `support` |
| `video` | ffmpeg y la conversión de stickers WebM | `settings`, `support` |
| `voice` | Grabar notas de voz, limpiarlas y codificarlas | `model`, `settings`, `support`, `video` |
| `links` | Abre los enlaces de los mensajes para saber si tienen video o audio, sin llegar nunca a esta máquina ni a su red | `model` |
| `gif` | Buscadores de GIF y lectura de cuadros | `model`, `settings` |
| `login` | Inicio de sesión en los puentes y cookies del navegador | `model` |
| `settings` | Preferencias | `support` |
| `model`, `support`, `bridge` | Datos del dominio; utilidades (`Os`, `Hashes`, `Redactor`, `BundledTools`); lo que `matrix` y `server` comparten de los puentes (`BridgeStates`, `BridgePorts`) | nada del proyecto |

Excepciones aceptadas:

- `ui/LoginView` usa `MatrixChatService`: iniciar sesión es lo que crea el servicio.
- `ui` usa `server` para lo que no es de chats (claves de Telegram, registros, servidor local).

Lo que solo existe con puentes va en `BridgeAccess`, que el demo no implementa. La UI pregunta
`svc instanceof BridgeAccess` en vez de nombrar a `MatrixChatService`.

## `service`: interfaces pequeñas

`ChatService` junta interfaces por rol: `ChatSession`, `ChatReader`, `MediaSource`, `ChatMessaging`, `ChatPreferences`,
`NetworkAccounts`, `ContactDirectory`, `GroupDirectory` y `ChatFolders` (carpetas). Una clase nueva pide solo el rol que necesita: `ImageCache`
recibe `MediaSource`, no todo el servicio.

## `matrix`

`MatrixChatService` es la fachada: conserva el bucle de sincronización y le pasa el resto a sus partes.

| Clase | Responsabilidad |
|---|---|
| `MatrixChatService` | Ciclo de vida, sincronización y consultas |
| `MatrixCore` | Lo que comparten las partes: conexión, salas, hilos y oyentes |
| `MatrixLogin` | Iniciar sesión y recuperar una sesión guardada |
| `SyncCache` | La última sincronización en disco, sin el contenido de los chats con bots |
| `SyncProcessor`, `RoomFields`, `SystemMessages`, `SyncEvents` | `/sync` → modelo (lógica sin red) |
| `MessageSender` | Lo que sale en un chat |
| `ChatActions` | Lo que cambias de un chat |
| `BridgeAccounts` | Puentes y cuentas |
| `BotChat`, `BridgeProtocol` | El chat con el bot de cada puente; comandos y respuestas |
| `DiscordServers`, `ContactActions`, `MediaDownloader` | Servidores de Discord, contactos, descargas |
| `RoomPreferences` | Lo que dice el servidor frente a `PreferencesFile`, que decide; la regla de silencio que vuelve al servidor |
| `MatrixClient` | HTTP |

## Límites de código

No hay un número oficial: Checkstyle trae por defecto 2.000 líneas por archivo y 150 por método. Unichat es más estricto
y lo comprueba en cada compilación:

| Regla | Límite | Herramienta |
|---|---|---|
| Líneas por archivo Java | 800 | Checkstyle (`checkstyle.xml`) |
| Líneas por método | 100 (meta: 60) | Checkstyle |
| Código copiado | 100 tokens | PMD CPD (`./mvnw verify`) |

El límite no es el objetivo. Un archivo se parte por responsabilidad, no para bajar de un número.

## Principios

- **KISS:** la solución más simple que funcione; nada de capas "por si acaso".
- **DRY:** lo que se repite tres veces se extrae; dos veces, si es la misma regla y no una coincidencia.
- **SOLID:** una responsabilidad por clase; depender de interfaces (`ChatService`, `BridgeAccess`, `BridgeProvisioner`);
  interfaces pequeñas. Cuando SOLID y KISS chocan, gana KISS: no se crea una interfaz con una sola implementación si no
  hay una dependencia que invertir.

## Deuda conocida

No se tocó porque arreglarlo cambia comportamiento o necesita pruebas que no existen. En orden de utilidad:

1. **Métodos de 60 a 100 líneas** (ver `./mvnw checkstyle:check` con un límite menor): sobre todo constructores de UI.
   **`Network`** se consulta con `switch` en varios sitios: una red nueva toca varios archivos.
2. **Registros:** se escribe con `System.err`, sin niveles, y no hay `UncaughtExceptionHandler`, así que un error en un
   hilo propio puede perderse. Migrar a `System.Logger` cambia el formato de los registros, y `AppLog` y `Redactor`
   deben seguir filtrando.
3. **`catch` que ignoran errores:** hay que revisarlos uno por uno; algunos son intencionales.
4. **Estado global:** `Settings.get()`, `LocalServer.get()` y el `provisioner` estático de `BridgeAccounts` obligan a
   las pruebas a compartir estado.
5. **Textos en español fuera de `ui`** (errores de `matrix`, `server`, etc.): es el primer paso si algún día se traduce.
6. **CI:** las acciones de GitHub están fijadas por etiqueta, no por SHA.
