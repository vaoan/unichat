package app.unichat.stt;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandLineTest {
    @Test
    void quotesKeepSpacesTogether() {
        assertEquals(List.of("whisper", "-m", "/a b/model.bin", "it's"), CommandLine.split("whisper  -m \"/a b/model.bin\" \"it's\""));
        assertEquals(List.of("say", "two words"), CommandLine.split("say 'two words'"));
        assertEquals(List.of(""), CommandLine.split("\"\""));
    }

    @Test
    void windowsPathsKeepTheirBackslashes() {
        assertEquals(List.of("C:\\Program Files\\whisper\\main.exe", "-f", "C:\\x.wav"),
                CommandLine.split("\"C:\\Program Files\\whisper\\main.exe\" -f C:\\x.wav"));
        assertEquals(List.of("say \"hi\""), CommandLine.split("\"say \\\"hi\\\"\""));
    }

    @Test
    void aPathWithSpacesStaysOneArgument() {
        List<String> cmd = CommandLine.expand("mlx_whisper {archivo} --language {idioma} --output-dir={archivo}.d",
                Map.of("archivo", "/Users/Ana María/nota.wav", "idioma", "es"));
        assertEquals(List.of("mlx_whisper", "/Users/Ana María/nota.wav", "--language", "es",
                "--output-dir=/Users/Ana María/nota.wav.d"), cmd);
    }
}
