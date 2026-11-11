package app.unichat.cloak;

import java.util.List;

/**
 * How hard a picture is protected: the attack's steps and how much it may change the picture. Measured on flat
 * artwork (720×960, cel shading), the encoder's view at 512 px moved toward the target pattern, and how visible:
 * LIGHT 8 % at PSNR 33.9 dB (a mottle in dark areas only), MEDIUM 12 % at 32.3 dB (a faint cell pattern on smooth
 * areas up close), STRONG (at 30 steps) 15.5 % at 29.3 dB (coloured grain everywhere). On a photo, with more texture to
 * hide in, they move more. About 3, 8 and 25 minutes for a 768 px working size on an M1; peak memory 1.5 GB.
 */
public enum CloakLevel {
    LIGHT(10, 12, 0.15, 0.3), MEDIUM(30, 12, 0.7, 0.3), STRONG(100, 16, 1.0, 1.0);

    private final int steps;
    private final double eps, smoothShare, colour;

    /**
     * @param eps the largest change per channel, 0..255, where texture hides it
     * @param smoothShare what the smoothest areas may take of {@code eps} (flat artwork is mostly those)
     * @param colour the change in colour, against the one in brightness
     */
    CloakLevel(int steps, double eps, double smoothShare, double colour) {
        this.steps = steps;
        this.eps = eps;
        this.smoothShare = smoothShare;
        this.colour = colour;
    }

    public int steps() { return steps; }

    /** The script's options for this level ({@code cloak.py}). */
    List<String> options() {
        return List.of("--steps", String.valueOf(steps), "--eps", String.valueOf(eps),
                "--smooth-share", String.valueOf(smoothShare), "--colour", String.valueOf(colour));
    }
}
