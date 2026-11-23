package app.unichat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What Unichat stores, sends, logs or tells a program is English; Spanish reaches the screen only through
 * {@code support/I18n} ({@code messages_es.properties}). Every string literal and text block under {@code src/main/java}
 * is read, comments are not, and one with Spanish in it fails here unless {@link #ALLOWED} names it with its reason.
 */
class EnglishFirstTest {
    private static final Path MAIN = Path.of("src/main/java");
    /** The texts of the person's language live here, as keys: the one file that may hold any. */
    private static final String I18N = "app/unichat/support/I18n.java";

    /** Letters and marks only Spanish uses among the app's two languages. */
    private static final Pattern MARKS = Pattern.compile("[áéíóúñÁÉÍÓÚÑ¿¡]");
    /**
     * Common Spanish words that are no English word nor a code token (so not "no", "me", "de", "en", "es", "con",
     * "ver", "el", "la", "un", "se", "por"), the usual UI verbs among them, which carry no accent.
     */
    private static final Set<String> WORDS = Set.of("que", "para", "pero", "los", "las", "una", "unos", "unas",
            "porque", "cuando", "donde", "desde", "hasta", "tambien", "muy", "nuevo", "nueva", "archivo", "carpeta",
            "ajustes", "mensaje", "mensajes", "cuenta", "cuentas", "idioma", "nota", "notas", "voz", "nube", "redes",
            "perfil", "teclado", "acerca", "escribir", "hola", "gracias", "yo", "sin", "buscar", "cancelar", "aceptar",
            "guardar", "abrir", "eliminar", "responder", "copiar", "cerrar", "todos", "enviar");
    private static final Pattern WORD = Pattern.compile("\\p{L}+");

    /**
     * A literal allowed to hold Spanish: the file, the literal as written in the source and why. It must be that
     * literal exactly, unless {@code part} says a part is enough (one name inside several paths, a long prompt).
     */
    record Allowed(String file, String literal, boolean part, String why) {
        static Allowed exact(String file, String literal, String why) { return new Allowed(file, literal, false, why); }
        static Allowed part(String file, String literal, String why) { return new Allowed(file, literal, true, why); }

        boolean allows(String rel, String text) {
            return file.equals(rel) && (part ? text.contains(literal) : text.equals(literal));
        }
    }

    private static final List<Allowed> ALLOWED = List.of(
            Allowed.part("app/unichat/cloud/CloudNames.java", "Ajustes",
                    "the old settings folder (\"Ajustes\", \"/Ajustes\", \"Ajustes/boveda-apagada.json\"), which the "
                            + "migration must find to move it"),
            Allowed.exact("app/unichat/cloud/CloudNames.java", "/Bóveda",
                    "the old vault folder, which the migration must find to move it"),
            Allowed.exact("app/unichat/stt/SttSettings.java", "{archivo}",
                    "a command saved with the old Spanish placeholder is rewritten to {file}"),
            Allowed.exact("app/unichat/stt/SttSettings.java", "{idioma}",
                    "a command saved with the old Spanish placeholder is rewritten to {language}"),
            Allowed.exact("app/unichat/server/LocalServer.java", "yo",
                    "the account on every existing local server is @yo:localhost; another name would lose it"),
            Allowed.part("app/unichat/assist/FixPrompt.java", "in Spanish á é í ó ú ü ñ ¿ ¡",
                    "the English prompt names the marks the AI must add back to a Spanish text"));

    @Test
    void noSpanishOutsideTheUiTexts() throws IOException {
        var found = new ArrayList<String>();
        var used = new HashSet<Allowed>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String rel = MAIN.relativize(f).toString().replace('\\', '/');
                if (rel.equals(I18N)) continue;
                for (Literal l : literals(Files.readString(f, StandardCharsets.UTF_8))) {
                    if (!spanish(l.text())) continue;
                    Allowed a = allowedFor(rel, l.text());
                    if (a != null) used.add(a);
                    else found.add(rel + ":" + l.line() + ": \"" + l.text() + "\"");
                }
            }
        }
        assertTrue(found.isEmpty(), "Spanish outside I18n (a UI text goes through I18n.t in both languages; anything "
                + "else is English; a real exception goes in ALLOWED with its reason):\n" + String.join("\n", found));
        var stale = ALLOWED.stream().filter(a -> !used.contains(a)).toList();
        assertTrue(stale.isEmpty(), "Allowed literals no longer in the code (remove them): " + stale);
    }

    /** The entry that lets {@code text} in file {@code rel} be Spanish, or null. */
    static Allowed allowedFor(String rel, String text) {
        return ALLOWED.stream().filter(a -> a.allows(rel, text)).findFirst().orElse(null);
    }

    @Test
    void anExactEntryAllowsOnlyItsOwnLiteral() {
        assertNotNull(allowedFor("app/unichat/server/LocalServer.java", "yo"));
        assertNull(allowedFor("app/unichat/server/LocalServer.java", "yo no sé"), "more Spanish beside it");
        assertNull(allowedFor("app/unichat/stt/SttSettings.java", "Falta {archivo}"));
        assertNull(allowedFor("app/unichat/cloud/CloudNames.java", "/Bóveda/llaves nuevas"));
        assertNotNull(allowedFor("app/unichat/cloud/CloudNames.java", "Ajustes/boveda-apagada.json"), "a part entry");
        assertNull(allowedFor("app/unichat/ui/MainView.java", "yo"), "only in its own file");
    }

    @Test
    void catchesMarksAndWholeWordsOnly() {
        assertTrue(spanish("Sofía"));
        assertTrue(spanish("¿listo?"));
        assertTrue(spanish("Atajos de teclado"));
        assertTrue(spanish("Algo para ti"));
        for (String verb : List.of("sin", "buscar", "cancelar", "aceptar", "guardar", "abrir", "eliminar", "responder",
                "copiar", "cerrar", "todos", "enviar")) {
            assertTrue(spanish("Boton " + verb), verb);
        }
        assertFalse(spanish("ver el la un se por"), "Spanish words that are English or code too are left out");
        assertFalse(spanish("no file"), "English words that are Spanish too");
        assertFalse(spanish("--no-config"));
        assertFalse(spanish("paragraph queue notary"), "a word inside another is no word");
    }

    @Test
    void readsLiteralsAndTextBlocksButNoComments() {
        String src = """
                // "Cuentas" in a comment
                /* "también" here, and a "quote */ String a = "uno"; char q = '"';
                String b = "dos \\" tres";
                /** "Javadoc" */
                String c = \"""
                    cuatro
                    \""";
                """;
        var lits = literals(src);
        assertEquals(List.of("uno", "dos \\\" tres", "\n    cuatro\n    "), lits.stream().map(Literal::text).toList());
        assertEquals(List.of(2, 3, 5), lits.stream().map(Literal::line).toList());
    }

    static boolean spanish(String text) {
        if (MARKS.matcher(text).find()) return true;
        var m = WORD.matcher(text);
        while (m.find()) {
            if (WORDS.contains(m.group().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /** A string literal or text block as written (escapes left as they are), and the line it starts on. */
    record Literal(int line, String text) { }

    /** The literals of a Java source, skipping comments and char literals. */
    static List<Literal> literals(String src) {
        var out = new ArrayList<Literal>();
        int line = 1;
        int i = 0;
        while (i < src.length()) {
            char c = src.charAt(i);
            int end;
            if (src.startsWith("//", i)) {
                end = src.indexOf('\n', i);
                end = end < 0 ? src.length() : end;
            } else if (src.startsWith("/*", i)) {
                end = src.indexOf("*/", i + 2) + 2;
            } else if (src.startsWith("\"\"\"", i)) {
                int close = closing(src, i + 3, "\"\"\"");
                out.add(new Literal(line, src.substring(i + 3, close)));
                end = close + 3;
            } else if (c == '"' || c == '\'') {
                int close = closing(src, i + 1, String.valueOf(c));
                if (c == '"') out.add(new Literal(line, src.substring(i + 1, close)));
                end = close + 1;
            } else {
                end = i + 1;
            }
            for (int k = i; k < end; k++) if (src.charAt(k) == '\n') line++;
            i = end;
        }
        return out;
    }

    /** Where {@code quote} closes what starts at {@code from}, past escaped characters. */
    private static int closing(String src, int from, String quote) {
        int i = from;
        while (!src.startsWith(quote, i)) i += src.charAt(i) == '\\' ? 2 : 1;
        return i;
    }
}
