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
| Búsqueda de GIFs | El buscador de GIFs elegido (Ajustes → GIFs) | Cuando buscas |
| Los enlaces de los mensajes | La página del enlace, sin cookies | Al mostrar un mensaje con enlace, para saber si tiene video. Nunca a tu red local. Se apaga en Ajustes → Privacidad → Videos de los enlaces |
| **Escribir con IA** (apagado por defecto) | El servicio de Claude Code o Codex, con tu cuenta | Solo al pulsar el botón: el chat abierto para un borrador; solo tu texto para corregirlo. Con «Aprender cómo escribo», también tus mensajes de ese chat y lo que cambiaste de los borradores, para actualizar `writing-style.md` (tu forma de escribir, guardada en tu carpeta de datos)  Si lo que escribiste tiene datos entre [corchetes], puede buscarlos en internet: solo búsquedas, nunca abrir páginas |

Las confirmaciones de lectura se eligen en Ajustes → Privacidad: al abrir el chat, **al responder** (abrir un chat no
avisa a nadie; al escribir en él, la otra persona ve que leíste) o nunca. La app de la red en tu teléfono marca como
leído por su cuenta si abres el chat allí.

Las notas de voz se transcriben en tu computador con Whisper: el audio no sale de él.

## Registros

Los errores se guardan en `logs/unichat.log` dentro de la carpeta de datos. Antes de escribir cada línea se
quitan tokens, contraseñas, números de teléfono, correos, identificadores de usuarios y salas, y el texto de los
mensajes (`support/Redactor`). «Copiar informe de
errores» (Ajustes → Acerca de) copia la versión, el sistema y las últimas líneas del registro al portapapeles. Nada se
envía solo: tú decides si lo pegas en un reporte.

## Tus cuentas

Las redes no autorizan los puentes. Ver «Riesgos» en el [README](../README.es.md#riesgos-de-usar-puentes).
