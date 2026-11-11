package app.unichat.cloak;

/**
 * How hard a picture is protected: steps of the attack on each tile. Measured on an M1 with a 512×768 photo (Kodak's
 * kodim04) at 10 steps: ~1.5 min, the encoder's view at 512 px moved 15 % of the way to the target pattern with PSNR
 * 35.7 dB (Mist's own settings: the same 15 % at 28.7 dB, its pattern plainly visible on skin). About 3, 8 and 25 minutes
 * for a 768 px working size. Peak memory 1.5 GB at every level.
 */
public enum CloakLevel {
    LIGHT(10), MEDIUM(30), STRONG(100);

    private final int steps;

    CloakLevel(int steps) {
        this.steps = steps;
    }

    public int steps() { return steps; }
}
