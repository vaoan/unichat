package app.unichat.cloak;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CloakToolTest {
    @TempDir
    Path dir;

    @Test
    void packagesComeOnlyPinnedWithTheirChecksumsAndReadyMade() {
        List<String> c = new CloakTool(dir).packagesCommand();
        assertTrue(c.containsAll(List.of("--require-hashes", "--no-build", "--no-config")), c.toString());
        assertTrue(c.contains(dir.resolve("requirements.txt").toString()));
        List<String> v = new CloakTool(dir).venvCommand();
        assertTrue(v.containsAll(List.of("--no-config", "only-managed", CloakTool.PYTHON)), v.toString());
        assertTrue(v.get(v.size() - 1).startsWith(dir.toString()), "inside its own folder");
    }

    @Test
    void everyRequirementIsAnExactVersionWithHashes() throws IOException {
        String req;
        try (InputStream in = CloakTool.class.getResourceAsStream("/app/unichat/cloak/requirements.txt")) {
            req = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> packages = req.lines().filter(l -> !l.isBlank() && !l.startsWith(" ") && !l.startsWith("#")).toList();
        assertTrue(packages.stream().anyMatch(l -> l.startsWith("torch==2.8.0")), packages.toString());
        for (String p : packages) assertTrue(Pattern.compile("^[a-z0-9_.-]+==[0-9][^ ]* \\\\$").matcher(p).find(), p);
        assertEquals(packages.size(), req.split("\\n(?=\\S)").length, "each one followed by its hashes");
        assertTrue(req.contains("--hash=sha256:"));
    }

    @Test
    void anEmptyFolderIsNotInstalledAndTheCommandCarriesTheLevel() throws IOException {
        CloakTool tool = new CloakTool(dir);
        assertFalse(tool.isInstalled());
        List<String> c = tool.protectCommand(dir.resolve("in.jpg"), dir.resolve("out.png"), CloakLevel.STRONG);
        assertEquals("100", c.get(c.indexOf("--steps") + 1));
        assertTrue(Files.isRegularFile(dir.resolve("cloak.py")), "the script is written out");
        assertTrue(c.contains("-I"), "isolated: nothing from the folder it runs in is imported");
    }

    /** The real thing: downloads ~330 MB into a temp folder, protects a small picture. {@code -Dunichat.networkTests=true}. */
    @Test
    @EnabledIfSystemProperty(named = "unichat.networkTests", matches = "true")
    void theRealInstallationProtectsAPicture() throws Exception {
        assumeTrue(CloakTool.canInstall());
        CloakTool tool = new CloakTool(dir.resolve("cloak"));
        List<String> steps = new ArrayList<>();
        tool.install((key, f) -> { if (steps.isEmpty() || !steps.get(steps.size() - 1).equals(key)) steps.add(key); });
        assertTrue(tool.isInstalled(), steps.toString());
        assertFalse(Files.exists(dir.resolve("cloak").resolve("cache")), "uv's cache is gone");
        BufferedImage img = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 200; y++) for (int x = 0; x < 300; x++) img.setRGB(x, y, (x * 255 / 300) << 16 | (y * 255 / 200) << 8 | 90);
        Path in = dir.resolve("in.png"), out = dir.resolve("out.png");
        ImageIO.write(img, "png", in.toFile());
        List<Double> progress = new ArrayList<>();
        CloakJob.run(tool.protectCommand(in, out, CloakLevel.LIGHT), out, progress::add);
        BufferedImage got = ImageIO.read(out.toFile());
        assertEquals(296, got.getWidth(), "multiples of 8");
        assertEquals(200, got.getHeight());
        assertEquals(1.0, progress.get(progress.size() - 1), 1e-9);
        int changed = 0;
        for (int y = 0; y < 200; y++) for (int x = 0; x < 296; x++) if (got.getRGB(x, y) != img.getRGB(x * 300 / 296, y)) changed++;
        assertTrue(changed > 296 * 200 / 2, "most pixels moved: " + changed);
    }
}
