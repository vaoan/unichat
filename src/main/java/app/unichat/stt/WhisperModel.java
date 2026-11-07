package app.unichat.stt;

import app.unichat.support.Downloads;
import app.unichat.support.I18n;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.DoubleConsumer;

/**
 * Multilingual whisper.cpp models, too big to ship: downloaded on first use into {@code <data>/models/} and kept
 * only if they match the pinned checksum. Pinned to one commit of the model repository.
 */
public enum WhisperModel {
    BASE("ggml-base-q5_1.bin", 59_707_625L, "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"),
    SMALL("ggml-small-q5_1.bin", 190_085_487L, "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb");

    private static final String REPO = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/";

    private final String file;
    private final long size;
    private final String sha256;

    WhisperModel(String file, long size, String sha256) {
        this.file = file;
        this.size = size;
        this.sha256 = sha256;
    }

    public long size() { return size; }

    public Path path(Path modelsDir) { return modelsDir.resolve(file); }

    /** Present with the right size (the checksum was verified when it was downloaded). */
    public boolean isInstalled(Path modelsDir) {
        try {
            return Files.size(path(modelsDir)) == size;
        } catch (IOException e) {
            return false;
        }
    }

    /** Downloads the model unless it's there. Blocking; {@code progress} gets 0..1. */
    public Path ensure(Path modelsDir, DoubleConsumer progress) throws IOException, InterruptedException {
        Path target = path(modelsDir);
        if (isInstalled(modelsDir)) return target;
        Downloads.fetch(REPO + file, size, sha256, target, I18n.t("download.what.voiceModel"), progress);
        return target;
    }
}
