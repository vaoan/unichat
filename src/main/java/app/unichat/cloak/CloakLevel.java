package app.unichat.cloak;

/**
 * How hard a picture is protected: steps of the attack on each tile. Measured on an M1 with a 1024×768 photo (worked at
 * 768×576, 9 tiles of up to 256 px), ~1.7 s per tile and step alone: about 3, 8 and 25 minutes. The encoder's view of
 * the picture moved 17 %, 25 % and 31 % of the way to the target pattern, with the visible change growing a little
 * (PSNR 28.5, 27.7 and 26.6 dB). Peak memory 1.5 GB at every level.
 */
public enum CloakLevel {
    LIGHT(10), MEDIUM(30), STRONG(100);

    private final int steps;

    CloakLevel(int steps) {
        this.steps = steps;
    }

    public int steps() { return steps; }
}
