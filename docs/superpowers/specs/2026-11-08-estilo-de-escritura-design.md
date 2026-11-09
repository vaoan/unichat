# Aprender cómo escribe la persona (modo de escritura con IA)

## Por qué

Los borradores de Claude/Codex no suenan a la persona: escriben «jajaja» cuando ella escribe «hahaha», o usan
palabras que nunca diría («qué chido»). Escribir esas reglas a mano en las instrucciones es tedioso y nunca
termina. Unichat debe aprenderlas solo, de a pocos, y pasárselas a la IA con cada borrador.

## Objetivos

- Un archivo `writing-style.md` con cómo escribe la persona, que Unichat mejora cada vez que se usa el botón de
  borrador y que va en el prompt de cada borrador.
- Aprende de dos fuentes: **sus mensajes enviados** (cómo escribe) y **lo que corrige de un borrador antes de
  enviarlo** (qué no diría). Las correcciones pesan más.
- Visible y editable en Ajustes → Escribir con IA, por si aprende algo mal; con un botón para borrar lo aprendido.

## Fuera de alcance

- Estilos distintos por contacto o red (un solo archivo; la IA puede anotar diferencias dentro de él, p. ej. «más
  formal con clientes», si las ve).
- Aprender sin usar el botón: nada se analiza si la persona no usa el modo de escritura.

## Lo que no puede pasar

- Que el archivo guarde datos de otras personas o del contenido de los chats (nombres, teléfonos, citas de
  mensajes ajenos): solo rasgos de estilo de la persona.
- Que aprender haga fallar el borrador (si falla, el borrador sale con el archivo que había).
- Que crezca sin límite y se coma el prompt, o que se llene de reglas y la IA se confunda: **corto** (máx. 40
  viñetas, 3 000 caracteres), y cada actualización junta, corrige o quita reglas en vez de solo agregar.

## Enfoques considerados

- **A. La IA actualiza el archivo cada vez que se usa el botón, y luego escribe el borrador con él (elegido).**
  Entiende matices (mexicanismos, tono con clientes) y lo corregido ya cuenta en el borrador de ese momento. Cuesta
  una llamada extra cuando hay algo nuevo que aprender, y ese borrador tarda un poco más.
- B. Unichat lo calcula sin IA (conteos de palabras y emojis): sin costo, pero se queda en listas y no capta el tono.
- C. Mixto por tandas (la IA cada ~5 correcciones): menos llamadas, aprende más lento. Paso fácil desde A si el
  costo molesta.

## Cómo funciona

1. **Recordar el borrador.** Cuando un borrador cae en el cuadro de un chat, el `Composer` recuerda su texto para
   ese chat. Al enviar un mensaje de texto en ese chat:
   - si el texto enviado difiere del borrador, guarda la corrección (borrador → enviado);
   - si es igual, nada (no enseña nada nuevo);
   - en ambos casos olvida el borrador. Cambiar de chat no lo olvida (el borrador queda en ese chat).
   Las correcciones pendientes se guardan en `writing-style-corrections.json` (las últimas 20; este computador),
   para no perderlas si la app se cierra.
   Al pulsar el botón de borrador, el proceso tiene dos pasos, en orden:
2. **Paso 1: aprender.** Si hay algo nuevo (correcciones pendientes, o mensajes míos en el chat posteriores a la
   última vez que se aprendió de ese chat), una llamada al mismo programa (Claude Code o Codex), sin carpeta que
   leer, con:
   - el `writing-style.md` actual;
   - mis últimos ~30 mensajes de texto de ese chat (solo los míos);
   - las correcciones pendientes;
   - la orden de devolver el archivo completo, corregido: solo rasgos de estilo (palabras y muletillas, risas,
     emojis, puntuación, mayúsculas, largo, saludos y despedidas, idioma y variante, qué evita), en viñetas cortas,
     sin nombres, números ni citas de otras personas; **máximo 40 viñetas**: agregar solo lo que de verdad es nuevo,
     corregir lo que estaba mal, juntar lo repetido y quitar lo menos útil si no cabe; lo aprendido de correcciones
     pesa más que lo deducido, y una regla nueva que contradice una vieja la reemplaza.
   Si falla o tarda más de 2 minutos, una línea en el registro (nunca el prompt ni la respuesta), las correcciones
   siguen pendientes y el borrador sigue con el archivo que había: aprender nunca impide el borrador. Si sale bien,
   se guarda el archivo (si viene de más de 3 000 caracteres se descarta) y se vacían las correcciones usadas. Sin
   nada nuevo, este paso no se hace y el borrador no espera.
3. **Paso 2: el borrador.** Lleva el archivo después de las instrucciones de la persona, como «Cómo escribe esta
   persona (aprendido de sus mensajes; sus instrucciones de arriba mandan si chocan)». Corregir (⌘O) también lo
   lleva, para no cambiar «hahaha» por «jajaja».

## Ajustes → Escribir con IA

- «Aprender cómo escribo» (activado por defecto): explica que, cuando hay algo nuevo, usa una llamada más antes del
  borrador.
- «Lo aprendido»: el archivo en un cuadro de texto editable (se guarda al escribir, como las instrucciones) y
  «Borrar lo aprendido» (vacía el archivo y las correcciones pendientes).
- El aviso de privacidad dice que, para aprender, se envían a la IA tus mensajes de ese chat y tus correcciones.

## Archivos

- `writing-style.md`: viaja con la persona (como `settings.properties`).
- `writing-style-corrections.json`: se queda en este computador; se vacía al aprender.

## Decisiones mías (para revisar)

- Corregir (⌘O) también usa el estilo aprendido.
- Al aprender solo entran mis mensajes del chat abierto, no de todos: es lo que ya va a la IA con el borrador y no
  amplía lo que sale del computador.
- Límites: 30 mensajes, 20 correcciones, 40 viñetas, 3 000 caracteres, 2 minutos para aprender.

## Para quien lo construya

Nombres de clases, dónde vive la lógica pura (prompt de aprendizaje, recortes, comparación) y las pruebas: lógica
pura en `assist/` con pruebas; la interfaz en `ui/` (`Composer`, `AiReplyButton`, `AiReplyPage`). Sin cambios en
la arquitectura de paquetes.
