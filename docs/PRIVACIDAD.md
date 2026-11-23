# Privacidad

[English](PRIVACY.md) · Español

Unichat no tiene servidores propios ni cuentas propias. No recoge estadísticas de uso ni informes de errores, y
no se envía nada por su cuenta a quien lo hizo.

## Dónde están tus mensajes

- **Con el servidor local** (la opción por defecto en Mac y Windows), el servidor Matrix (tuwunel) y los puentes
  corren en tu computador y solo escuchan en `127.0.0.1`. Tus mensajes van de tu computador a cada red (WhatsApp,
  Telegram…) directamente, como en la app oficial de cada una.
- **Con un servidor propio** (Docker, `server/`), pasan por el servidor que tú montaste.
- Las fotos, videos y archivos se quedan en la red de origen y se descargan cuando los abres. Lo descargado se
  guarda en `cache/media`, con el límite que elijas en Ajustes → Privacidad.
- La sesión (`session.properties`) y las claves del servidor local se guardan en texto plano en tu carpeta de
  usuario. Cualquiera con acceso a tu cuenta del computador puede leerlas, igual que los mensajes.

## Qué sale de tu computador

| Qué | A dónde | Cuándo |
|---|---|---|
| Tus mensajes y lo que envías | La red de cada chat | Siempre: es la app |
| La descarga de los puentes y del servidor | GitHub (mautrix y el repositorio de Unichat) | Al conectar una red por primera vez, o al actualizar |
| Buscar actualizaciones | La API de GitHub (`api.github.com`), sin datos tuyos | Una vez al día, si está activado (Ajustes → Privacidad) |
| ffmpeg y el modelo de Whisper o de RNNoise | GitHub y Hugging Face | La primera vez que se necesitan (ffmpeg se apaga en Ajustes → Redes → Instalar el conversor automáticamente) |
| Lo que necesita proteger una foto contra la IA: uv y Python, PyTorch, el codificador de imágenes de Stable Diffusion y el patrón de Mist | GitHub, PyPI (`pypi.org`, `files.pythonhosted.org`) y Hugging Face | Solo la primera vez que eliges «Foto protegida contra IA…» en el clip, después de avisarte. La foto se protege en tu equipo y solo va al chat |
| Lo que lleva un enlace (un video, fotos), en un chat con «Guardar lo que envíen en este chat» encendido (el engranaje de su perfil), o cuando eliges «Guardar enlace» en el menú de un mensaje: yt-dlp, y gallery-dl si lo tienes | GitHub (yt-dlp, la primera vez) y luego el sitio del enlace (YouTube, Instagram, TikTok…). Si pones las cookies de un sitio en un archivo llamado `*cookie*.txt` en tu carpeta de Descargas, van a ese sitio, como cuando inicias sesión en él | Solo para esos chats y esos enlaces; lo guardado queda en tu equipo (y en tu almacenamiento, si conectaste uno) |
| **Tu propio almacenamiento** (Ajustes → Nube, apagado hasta que conectes uno) | La cuenta de pCloud, Dropbox o WebDAV que conectes (Icedrive, Nextcloud…). El inicio de sesión ocurre en tu navegador, en la página del servicio; Dropbox solo ve la carpeta propia de Unichat | Más o menos una vez por minuto y después de guardar algo: tus ajustes y las opciones de cada chat (nunca lo propio de este computador: la sesión e inicios de sesión, sus carpetas y los comandos que ejecuta; tus claves —las de Telegram, las de los servicios de GIF, las de las apps de la nube— solo con la bóveda encendida, cifradas), lo que la IA aprendió de cómo escribes (`writing-style.md`), los archivos que guarda «Guardar lo que envíen en este chat» y los textos de los chats que los guardan («Textos» entre sus tipos), con quién escribió cada uno, el chat y cuándo: la copia de cada computador, en `Unichat/Texts`, para que los demás puedan leer lo que se borró (donde se guardan tus propios textos, también lo que escribiste antes de que la IA lo corrigiera, aunque nunca salió al chat); suben en cuanto se guardan, y los de los demás bajan cuando miras un mensaje borrado. En tu computador quedan en tu carpeta de datos, legibles solo por tu cuenta. Tu acceso a ella queda en este computador. Unichat no borra allí nada tuyo salvo las copias sin cifrar que pasa a la bóveda, las carpetas con sus nombres viejos (`Multimedia`, `Ajustes`, `Textos`, `Bóveda`), movidas una vez, en el propio servicio, a los ingleses (`Media`, `Settings`, `Texts`, `Vault`), cada una borrada entera cuando cada archivo en ella está confirmado con su nuevo nombre (una carpeta con algo que no lo está se queda, intacta), (cada una cuando su copia cifrada se lee entera, nunca mientras no hayas confirmado que guardaste el código de recuperación) y la bóveda misma cuando la apagas o la borras. Con la bóveda encendida (Ajustes → Nube → Bóveda), todo lo anterior menos los archivos guardados sube cifrado (AES-256-GCM) con una llave que el servicio nunca ve, y otro computador solo lo abre con tu código de recuperación o tu contraseña; al apagarla tus ajustes, lo que aprendió la IA y los textos vuelven a subir sin cifrar y la bóveda se borra de la cuenta (tus llaves, los mensajes fijados y las transcripciones de las notas de voz, que solo lleva la bóveda, quedan entonces en cada computador) |
| Búsqueda de GIFs | El buscador de GIFs elegido (Ajustes → GIFs) | Cuando buscas |
| Los enlaces de los mensajes | La página del enlace, sin cookies; para un post de X, la API pública de FxTwitter (api.fxtwitter.com) | Al mostrar un mensaje con enlace, para mostrar su vista previa (título, texto, imagen, video) y saber si tiene video. Nunca a tu red local. Se apaga en Ajustes → Privacidad → Conexiones → Vistas previas de los enlaces |
| **Escribir con IA** (apagado por defecto) | El servicio de Claude Code o Codex, con tu cuenta | Solo al pulsar el botón: el chat abierto para un borrador; solo tu texto (y `writing-style.md`) para corregirlo, o un mensaje tuyo ya enviado con «Corregir con IA» en su menú. Con «Corregir lo que envío» encendido, cada mensaje que envías desde Unichat en este computador: justo después de que sale («después de enviar») o antes de que salga («antes de enviar»). Un mensaje ya enviado lo reemplaza su corrección como una edición, sin preguntarte (salvo si la corrección añade un enlace, una mención, un comando o mucho más texto: entonces no se toca); el original queda en el historial de ediciones de la red. Antes de enviar, lo que sale es la corrección, también sin preguntarte: como lo escribiste si la corrección falla o añade algo, y los comandos (`!…`, `/…`) siempre como los escribiste. Con «Aprender cómo escribo», también tus mensajes de ese chat y lo que cambiaste de los borradores, con el mensaje al que respondía cada uno, para actualizar `writing-style.md` (tu forma de escribir, guardada en tu carpeta de datos; las versiones que reemplazó quedan en `writing-style-history/` en este computador). Un borrador también lleva algunas de tus correcciones anteriores (lo que propuso, lo que enviaste, el mensaje que respondía), primero las de ese chat; se guardan en `writing-style-learned.json` en este computador.  Si lo que escribiste tiene datos entre [corchetes], puede buscarlos en internet: solo búsquedas, nunca abrir páginas |

Las confirmaciones de lectura se eligen en Ajustes → Privacidad: al abrir el chat, **al responder** (abrir un chat no
avisa a nadie; al escribir o reaccionar en él, la otra persona ve que leíste todo hasta ahí) o nunca. La app de la red en tu teléfono marca como
leído por su cuenta si abres el chat allí.

Las notas de voz se transcriben en tu computador con Whisper: el audio no sale de él.

## La bóveda

Opcional (Ajustes → Nube → Bóveda), solo con tu propio almacenamiento conectado. No se envía nada a nadie más que a
ese almacenamiento: ni a un servidor de Unichat ni a otro tercero.

- **Lo que sube, sellado**: tus ajustes y las opciones de los chats, lo que aprendió la IA, los textos de los chats que
  los guardan, tus claves (las de Telegram, las de los servicios de GIF, las de las apps de la nube), los mensajes fijados
  y las transcripciones de las notas de voz. Cada archivo se cifra en tu computador con AES-256-GCM antes de salir. La
  multimedia guardada no forma parte de ella.
- **Lo que queda en cada computador**: la llave de la bóveda, tu acceso al almacenamiento, la sesión y los inicios de
  sesión de las redes, y lo propio de ese computador (rutas, comandos). La llave de la bóveda se guarda en tu carpeta de
  datos, legible solo por tu cuenta, como el resto de lo que hay allí.
- **Quién puede leer qué**: tu servicio de almacenamiento, y quien entre a tu cuenta allí, ve solo archivos cifrados y
  sus nombres y tamaños, nunca su contenido. Quien tenga tu código de recuperación o tu contraseña y acceso al
  almacenamiento puede abrirla, así que guarda el código en un lugar seguro: sin el código ni una contraseña, un computador
  perdido no se puede reemplazar y nadie, ni quien hizo Unichat, puede recuperar la bóveda. Un computador que tiene la
  llave puede leer todo lo de la bóveda; «Cambiar la llave» en la página deja fuera a un computador que quites. Un
  computador toma una llave nueva solo cuando la llave que tiene la avala, así que quien solo tenga tu almacenamiento no
  puede darle una; pero un computador quitado sigue teniendo la llave anterior, y uno tuyo que estaba apagado mientras lo
  quitabas puede ser engañado por él hasta que abras allí la bóveda con el código de recuperación o una contraseña puesta después de quitarlo (una contraseña
  anterior, devuelta por el computador quitado, puede abrir su llave): puede darle una llave
  suya, o hacerle creer que la bóveda se apagó, para que suba sin cifrar.
- **Si otro cambia el almacenamiento**: si el archivo de la bóveda se borra, o su llave cambia, sin que lo haga Unichat,
  tus computadores dejan de enviar nada personal y lo dicen en Ajustes → Nube → Bóveda; no vuelven a subir sin cifrar
  por su cuenta. Solo una bóveda apagada de verdad (deja una marca que solo su llave pudo escribir) lo hace, en los
  computadores que la tenían abierta; uno que siguió sin ella, nunca la abrió o estaba apagado cuando su llave cambió por
  última vez te pregunta una vez incluso entonces.
- **Apagarla** (Ajustes → Nube → Bóveda): tus ajustes, lo que aprendió la IA y los textos vuelven al almacenamiento sin
  cifrar, queda en `Unichat/Settings` una marca de que se apagó y la bóveda se borra de la cuenta. Tus claves, los mensajes fijados y las transcripciones, que solo lleva la
  bóveda, quedan en cada computador y no se suben sin cifrar.
- También puedes seguir sin ella en un computador («Seguir sin la bóveda en este equipo»): entonces nada personal sube
  ni baja desde él, y pones tus claves a mano.

## Registros

Los errores se guardan en `logs/unichat.log` dentro de la carpeta de datos. Antes de escribir cada línea se
quitan tokens, contraseñas, números de teléfono, correos, identificadores de usuarios y salas, y el texto de los
mensajes (`support/Redactor`). «Copiar informe de
errores» (Ajustes → Acerca de) copia la versión, el sistema y las últimas líneas del registro al portapapeles. Nada se
envía solo: tú decides si lo pegas en un reporte.

## Tus cuentas

Las redes no autorizan los puentes. Ver «Riesgos» en el [README](../README.es.md#riesgos-de-usar-puentes).
