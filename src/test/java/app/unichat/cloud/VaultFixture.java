package app.unichat.cloud;

import app.unichat.settings.Settings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * For screens' tests: this computer connected to an account in memory ({@link FakeDav}), with its {@link Cloud} as the
 * app makes it, and what another computer of the person's does there (makes the vault, adds itself). Everything it set
 * on this computer is put back on {@link #close}.
 */
public final class VaultFixture implements AutoCloseable {
    public static final String HERE = "1b6a3f3e-0d1c-4c0e-9a51-6f2d7c1e2b44", OTHER = "3d8c5b5a-2f3e-4e2a-9c73-8b4f9e3a4d66";
    private static final List<String> ACCOUNT = List.of("cloud.service", "cloud.url", "cloud.user", "cloud.secret", "cloud.settingsBase");

    private final FakeDav dav;
    private final Map<String, String> were = new HashMap<>();
    private final Map<String, String> portableWere;
    private final Cloud cloud;
    private final Path photos;
    private final List<java.util.concurrent.CountDownLatch> held = new java.util.concurrent.CopyOnWriteArrayList<>();
    private byte[] vk;

    public VaultFixture(Path dir) throws Exception {
        dav = new FakeDav("u", "p");
        portableWere = Settings.get().portable();
        were.putAll(VaultComputer.kept());
        for (String k : ACCOUNT) were.put(k, Settings.get().getString(k, null));
        for (String k : VaultComputer.KEYS) Settings.get().set(k, null);
        Settings.get().set("cloud.machine", HERE);
        Settings.get().set("cloud.media", "false");
        new CloudAccount(CloudAccount.Service.OTHER, dav.url(), "u", "p").save();
        Path data = Files.createDirectories(dir.resolve("here"));
        photos = data.resolve("Fotos");
        cloud = Cloud.create(data, () -> null, b -> true, () -> { }, () -> Files.isDirectory(photos) ? List.of(photos) : List.of(),
            new Cloud.Texts(data.resolve("texts"), p -> false, s -> { }),
            new Cloud.Personal(() -> "{}".getBytes(), b -> { }, data.resolve("transcripts")));
    }

    public Cloud cloud() {
        return cloud;
    }

    private Storage storage() {
        return new WebDav(dav.url(), "u", "p");
    }

    /** Another computer made the vault (and gave it {@code password}, unless null); its recovery code as printed. */
    public String otherComputerMadeIt(String password) throws Exception {
        Vault.Created made = Vault.create(storage(), OTHER, "Escritorio");
        vk = made.vk();
        if (password != null) Vault.addPassword(storage(), vk, password.toCharArray());
        return made.code().shown();
    }

    /** One more computer of the vault's, under {@code label} (needs the vault made by {@link #otherComputerMadeIt}). */
    public void anotherComputer(String machine, String label) throws Exception {
        Vault.addComputer(storage(), vk, machine, label);
    }

    /** This computer's way in deleted by another computer: removed from the vault. */
    public void removeHere() throws Exception {
        Vault.removeComputer(storage(), Vault.info(storage()), HERE);
    }

    /** {@code vault.json}'s generation raised by someone with only the account: nothing vouches for it. */
    public void raiseGenerationByHand() throws Exception {
        Vault.Info info = Vault.info(storage());
        dav.put(Vault.INFO, ("{\"id\":\"" + info.id() + "\",\"created\":\"" + info.created() + "\",\"generation\":"
            + (info.generation() + 1) + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** {@code vault.json} deleted by someone with only the account, the vault not turned off. */
    public void deleteVaultFileByHand() {
        dav.files.remove(Vault.INFO);
    }

    /** Whether {@code machine}'s slot holds an offer of the vault's current key (it takes it on its next round). */
    public boolean offeredTheKey(String machine) throws Exception {
        Vault.Info info = Vault.info(storage());
        VaultSlot slot = Vault.slot(storage(), info, machine);
        return slot != null && slot.params().has(VaultNext.NEXT);
    }

    /** The vault's computers in the account (machine → label). */
    public Map<String, String> computers() throws Exception {
        return Vault.computers(storage());
    }

    /** The ways in the account's vault has, by method name. */
    public Set<String> ways() throws Exception {
        Set<String> out = new java.util.TreeSet<>();
        for (VaultSlot s : Vault.slots(storage())) if (s.method() != VaultSlot.Method.COMPUTER) out.add(s.method().name());
        return out;
    }

    /** The account's next PUT of {@code path} fails (the network dropped), once. */
    public void failOnce(String path) {
        dav.failOnce.add(path);
    }

    /** The account's next DELETE of the vault's folder fails, once (its file can still go). */
    public void failVaultDeleteOnce() {
        dav.failDeleteOnce.add(Vault.ROOT);
    }

    /** The account's next PUT of {@code path} waits until the returned step runs (also on {@link #close}). */
    public Runnable holdOnce(String path) {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        dav.holdOnce.put(path, latch);
        return latch::countDown;
    }

    /** The account's next read of the vault's {@code vault.json} (each look at the vault's state) waits until the returned step runs. */
    public Runnable holdVaultReadOnce() {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        dav.holdReadOnce.put(Vault.INFO, latch);
        return latch::countDown;
    }

    /** Media turned on here, with {@code n} photos to go up, in order ({@code p0.jpg}, {@code p1.jpg}…). */
    public void photos(int n) throws java.io.IOException {
        Files.createDirectories(photos);
        for (int i = 0; i < n; i++) Files.writeString(photos.resolve("p" + i + ".jpg"), "foto " + i);
        Settings.get().set("cloud.media", "true");
    }

    /** A request held: {@link #arrived} waits until it's there, {@link #release} lets it go (also on {@link #close}). */
    public record Held(java.util.concurrent.CountDownLatch at, java.util.concurrent.CountDownLatch go) {
        public void arrived() throws InterruptedException {
            at.await();
        }

        public void release() {
            go.countDown();
        }
    }

    /** The account's next upload of photo {@code i} (of {@link #photos}) held. */
    public Held holdPhoto(int i) {
        Held h = new Held(new java.util.concurrent.CountDownLatch(1), new java.util.concurrent.CountDownLatch(1));
        held.add(h.go());
        String path = CloudAccount.MEDIA + "/Fotos/p" + i + ".jpg";
        dav.arrived.put(path, h.at());
        dav.holdOnce.put(path, h.go());
        return h;
    }

    /** How many times the account was asked for the vault's {@code vault.json} (each look at the vault's state). */
    public long vaultReads() {
        return dav.log.stream().filter(("GET " + Vault.INFO)::equals).count();
    }

    /** Once every vault job asked so far is done (the page's reloads included). */
    public void vaultJobsDone() throws Exception {
        cloud.vaultJobsOver().get();
    }

    /** Whether the account still holds a vault (anything under its folder). */
    public boolean vaultThere() {
        return dav.files.keySet().stream().anyMatch(p -> p.startsWith(Vault.ROOT + "/"));
    }

    /** Every file in the account, as it is now. */
    public Map<String, byte[]> files() {
        Map<String, byte[]> out = new HashMap<>();
        dav.files.forEach((p, b) -> out.put(p, b.clone()));
        return out;
    }

    /** Whether the account holds the same files as {@code before}, byte for byte. */
    public boolean same(Map<String, byte[]> before) {
        return before.keySet().equals(dav.files.keySet()) && before.entrySet().stream().allMatch(e -> Arrays.equals(e.getValue(), dav.files.get(e.getKey())));
    }

    @Override
    public void close() throws Exception {
        dav.holdOnce.values().forEach(java.util.concurrent.CountDownLatch::countDown); // nothing waits past the test
        dav.holdReadOnce.values().forEach(java.util.concurrent.CountDownLatch::countDown);
        held.forEach(java.util.concurrent.CountDownLatch::countDown); // a held upload let go, also once it's under way
        try {
            cloud.stop();
            cloud.vaultJobsOver().get(); // what a step asked for runs before the account goes
            cloud.syncNow().get();
        } finally {
            // Put back even when the wait above is cut short (a test's time ran out): the settings outlive the run.
            dav.close();
            if (vk != null) Arrays.fill(vk, (byte) 0);
            Settings.get().replacePortable(portableWere, k -> false);
            were.forEach(Settings.get()::set);
        }
    }
}
