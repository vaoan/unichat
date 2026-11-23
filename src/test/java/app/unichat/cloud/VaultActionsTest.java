package app.unichat.cloud;

import app.unichat.settings.Settings;
import app.unichat.support.I18n;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What Ajustes → Nube → Bóveda does through {@link Cloud}: make the vault, open it on another computer with the recovery
 * code or a password (a wrong one changes nothing), go on without it, add a password, a new code, take a computer out
 * with the key changed, and what the page is shown of it.
 */
class VaultActionsTest {
    private static final String DESK = "1b6a3f3e-0d1c-4c0e-9a51-6f2d7c1e2b44", LAPTOP = "2c7b4a4f-1e2d-4d1f-8b62-7a3e8d2f3c55",
        OTHER = "3d8c5b5a-2f3e-4e2a-9c73-8b4f9e3a4d66";
    private static final String PASSWORD = "una contraseña larga";

    @TempDir
    Path dir;
    private FakeDav cloud;
    private Map<String, String> portableWere, keysWere;
    private VaultComputer desk, laptop;

    @BeforeEach
    void start() throws Exception {
        cloud = new FakeDav("u", "p");
        portableWere = Settings.get().portable();
        keysWere = VaultComputer.kept();
        desk = new VaultComputer(cloud, dir, DESK);
        laptop = new VaultComputer(cloud, dir, LAPTOP);
    }

    @AfterEach
    void stop() {
        cloud.close();
        Settings.get().replacePortable(portableWere, k -> false);
        keysWere.forEach(Settings.get()::set);
    }

    private Storage dav() {
        return new WebDav(cloud.url(), "u", "p");
    }

    private VaultView view(VaultComputer c) throws InterruptedException, ExecutionException {
        return c.as(c.cloud.vault()::vaultView);
    }

    private String create(VaultComputer c) throws InterruptedException, ExecutionException {
        return c.as(c.cloud.vault()::createVault);
    }

    private <T> T as(VaultComputer c, java.util.function.Supplier<CompletableFuture<T>> call) throws InterruptedException, ExecutionException {
        return c.as(call::get);
    }

    private static String message(ExecutionException e) {
        return e.getCause().getMessage();
    }

    @Test
    void creatingMakesItOpenHereAndGivesTheCodeOnce() throws Exception {
        assertEquals(VaultView.NONE, view(desk));
        String code = create(desk);
        RecoveryCode typed = RecoveryCode.parse(code);
        assertNotNull(typed, "a code as printed: " + code.length() + " characters");
        assertEquals(Cloud.VaultState.OPEN, desk.round().vault());
        VaultView v = view(desk);
        assertEquals(Set.of(VaultView.Way.RECOVERY), v.ways());
        assertEquals(1, v.computers().size());
        assertTrue(v.computers().get(0).here());
        assertEquals(VaultActions.computerName(), v.computers().get(0).label());
        assertTrue(v.codeUnconfirmed(), "until the person types the groups back");
        as(desk, desk.cloud.vault()::codeKept);
        assertFalse(view(desk).codeUnconfirmed());
        assertFalse(cloud.files.values().stream().anyMatch(b -> new String(b, java.nio.charset.StandardCharsets.UTF_8).contains(code)),
            "the code is never in the account");
    }

    @Test
    void creatingWhenThereIsOneSaysSo() throws Exception {
        create(laptop);
        ExecutionException e = assertThrows(ExecutionException.class, () -> create(desk));
        assertEquals(I18n.t("cloud.error.vaultExists"), message(e));
    }

    @Test
    void anotherComputerOpensItWithAPassword() throws Exception {
        create(desk);
        as(desk, () -> desk.cloud.vault().addPassword(PASSWORD.toCharArray()));
        assertEquals(Cloud.VaultState.LOCKED, laptop.round().vault());
        VaultView locked = view(laptop);
        assertEquals(Set.of(VaultView.Way.RECOVERY, VaultView.Way.PASSWORD), locked.ways(), "the ways it can be opened with");
        assertEquals(List.of(), locked.computers(), "nothing more without the key");
        char[] typed = PASSWORD.toCharArray();
        as(laptop, () -> laptop.cloud.vault().openVault(VaultView.Way.PASSWORD, typed));
        assertArrayEquals(new char[typed.length], typed, "the password is cleared once used");
        assertEquals(Cloud.VaultState.OPEN, laptop.round().vault());
        assertEquals(2, view(desk).computers().size(), "a computer of the vault now");
    }

    @Test
    void aWrongSecretSaysSoAndWritesNothing() throws Exception {
        String code = create(desk);
        as(desk, () -> desk.cloud.vault().addPassword(PASSWORD.toCharArray()));
        Map<String, byte[]> before = new HashMap<>(cloud.files);
        for (VaultView.Way way : VaultView.Way.values()) {
            ExecutionException e = assertThrows(ExecutionException.class,
                () -> as(laptop, () -> laptop.cloud.vault().openVault(way, "otra contraseña larga".toCharArray())), way.name());
            assertEquals(I18n.t("cloud.error.vaultWrongSecret"), message(e), way.name());
        }
        String other = RecoveryCode.random().shown();
        assertThrows(ExecutionException.class, () -> as(laptop, () -> laptop.cloud.vault().openVault(VaultView.Way.RECOVERY, other.toCharArray())));
        assertEquals(before.keySet(), cloud.files.keySet(), "nothing written");
        before.forEach((p, b) -> assertArrayEquals(b, cloud.files.get(p), p));
        assertNull(laptop.get(VaultParts.ID), "nor kept here");
        as(laptop, () -> laptop.cloud.vault().openVault(VaultView.Way.RECOVERY, code.toCharArray()));
        assertEquals(Cloud.VaultState.OPEN, laptop.round().vault(), "the right one at once after");
    }

    @Test
    void theCodeOpensItAsTypedByHand() throws Exception {
        String code = create(desk);
        String typed = code.toLowerCase(java.util.Locale.ROOT).replace("-", " ").replace('0', 'o');
        as(laptop, () -> laptop.cloud.vault().openVault(VaultView.Way.RECOVERY, typed.toCharArray()));
        assertEquals(Cloud.VaultState.OPEN, laptop.round().vault());
    }

    @Test
    void aWayInLeftFromBeforeTheKeyChangedOpensNothing() throws Exception {
        String old = create(desk);
        assertEquals(Cloud.VaultState.OPEN, desk.round().vault());
        String oldSlot = cloud.files.keySet().stream().filter(p -> p.startsWith(Vault.SLOTS + "/") && !p.contains(DESK)).findFirst().orElseThrow();
        byte[] stale = cloud.files.get(oldSlot);
        Cloud.KeyChanged changed = as(desk, desk.cloud.vault()::rotateKey);
        cloud.put(oldSlot, stale); // put back by someone who kept it
        ExecutionException e = assertThrows(ExecutionException.class,
            () -> as(laptop, () -> laptop.cloud.vault().openVault(VaultView.Way.RECOVERY, old.toCharArray())));
        assertEquals(I18n.t("cloud.error.vaultWrongSecret"), message(e), "it would open the old key");
        as(laptop, () -> laptop.cloud.vault().openVault(VaultView.Way.RECOVERY, changed.code().toCharArray()));
        assertEquals(Cloud.VaultState.OPEN, laptop.round().vault());
        assertTrue(view(desk).codeUnconfirmed(), "the new code is to be confirmed too");
    }

    /**
     * Eve kept the password's slot from before a change of key (she held the old key: a removed computer), and with the
     * account she puts it back in place of the new password's, its generation as {@code claims} (null: as it was), and
     * strips {@code vault.json}'s vouch.
     */
    private void oldPasswordSlotPutBack(Long claims) throws JsonProcessingException, IOException, InterruptedException, ExecutionException {
        create(desk);
        as(desk, () -> desk.cloud.vault().addPassword(PASSWORD.toCharArray()));
        String slotPath = passwordSlot();
        byte[] stale = cloud.files.get(slotPath);
        assertEquals(Cloud.VaultState.OPEN, desk.round().vault());
        as(desk, desk.cloud.vault()::rotateKey);
        as(desk, () -> desk.cloud.vault().addPassword(PASSWORD.toCharArray()));
        cloud.files.remove(passwordSlot());
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode slot = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(stale);
        if (claims != null) slot.put("generation", claims);
        cloud.put(slotPath, json.writeValueAsBytes(slot));
        com.fasterxml.jackson.databind.node.ObjectNode info = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(cloud.files.get(Vault.INFO));
        info.remove("vouch");
        cloud.put(Vault.INFO, json.writeValueAsBytes(info));
    }

    private String passwordSlot() throws IOException {
        return Vault.slots(dav()).stream().filter(s -> s.method() == VaultSlot.Method.PASSWORD).map(s -> Vault.SLOTS + "/" + s.id() + ".json")
            .findFirst().orElseThrow();
    }

    @Test
    void aNewComputerNeverJoinsTheOldKeyThroughAWayInPutBackFromBeforeTheChange() throws Exception {
        oldPasswordSlotPutBack(null);
        ExecutionException e = assertThrows(ExecutionException.class,
            () -> as(laptop, () -> laptop.cloud.vault().openVault(VaultView.Way.PASSWORD, PASSWORD.toCharArray())));
        assertEquals(I18n.t("cloud.error.vaultWrongSecret"), message(e), "it would open the key Eve still holds");
        assertNull(laptop.get(VaultParts.ID));
    }

    @Test
    void aWayInPutBackClaimingTheCurrentGenerationOpensNothing() throws Exception {
        oldPasswordSlotPutBack(1L);
        ExecutionException e = assertThrows(ExecutionException.class,
            () -> as(laptop, () -> laptop.cloud.vault().openVault(VaultView.Way.PASSWORD, PASSWORD.toCharArray())));
        assertEquals(I18n.t("cloud.error.vaultWrongSecret"), message(e), "its generation is sealed with it");
        assertNull(laptop.get(VaultParts.ID));
    }

    @Test
    void goingOnWithoutItIsRememberedAndOpeningLaterEndsIt() throws Exception {
        String code = create(desk);
        as(laptop, () -> laptop.cloud.vault().declineVault(true));
        assertEquals(Cloud.VaultState.DECLINED, laptop.round().vault());
        assertEquals(Cloud.VaultState.DECLINED, view(laptop).state());
        assertEquals(Cloud.VaultState.DECLINED, laptop.round().vault(), "never asked again");
        as(laptop, () -> laptop.cloud.vault().declineVault(false));
        assertEquals(Cloud.VaultState.LOCKED, laptop.round().vault(), "taken back");
        as(laptop, () -> laptop.cloud.vault().declineVault(true));
        as(laptop, () -> laptop.cloud.vault().openVault(VaultView.Way.RECOVERY, code.toCharArray()));
        assertNull(laptop.get(VaultParts.DECLINED));
        assertEquals(Cloud.VaultState.OPEN, laptop.round().vault());
    }

    @Test
    void aShortPasswordIsRefusedAndOnlyOpenItCanAddOne() throws Exception {
        create(desk);
        ExecutionException e = assertThrows(ExecutionException.class, () -> as(desk, () -> desk.cloud.vault().addPassword("corta".toCharArray())));
        assertEquals(I18n.t("cloud.error.vaultShortPassword", Vault.MIN_PASSWORD), message(e));
        e = assertThrows(ExecutionException.class, () -> as(laptop, () -> laptop.cloud.vault().addPassword(PASSWORD.toCharArray())));
        assertEquals(I18n.t("cloud.error.vaultNotOpen"), message(e));
        assertEquals(Set.of(VaultView.Way.RECOVERY), view(desk).ways());
    }

    @Test
    void aNewCodeReplacesTheOld() throws Exception {
        String old = create(desk);
        as(desk, desk.cloud.vault()::codeKept);
        String code = as(desk, desk.cloud.vault()::newRecoveryCode);
        assertNotEquals(old, code);
        assertTrue(view(desk).codeUnconfirmed());
        assertThrows(ExecutionException.class, () -> as(laptop, () -> laptop.cloud.vault().openVault(VaultView.Way.RECOVERY, old.toCharArray())));
        as(laptop, () -> laptop.cloud.vault().openVault(VaultView.Way.RECOVERY, code.toCharArray()));
        assertEquals(Cloud.VaultState.OPEN, laptop.round().vault());
    }

    @Test
    void takingAComputerOutChangesTheKeyInTheSameStep() throws Exception {
        String code = create(desk);
        as(desk, () -> desk.cloud.vault().addPassword(PASSWORD.toCharArray()));
        as(laptop, () -> laptop.cloud.vault().openVault(VaultView.Way.RECOVERY, code.toCharArray()));
        assertEquals(Cloud.VaultState.OPEN, laptop.round().vault());
        assertEquals(Cloud.VaultState.OPEN, desk.round().vault());
        Cloud.KeyChanged changed = as(desk, () -> desk.cloud.vault().removeAndChangeKey(LAPTOP, Set.of(DESK, LAPTOP)));
        assertNotNull(RecoveryCode.parse(changed.code()));
        assertEquals(Set.of("PASSWORD"), changed.dropped(), "said, to add again");
        assertEquals(1, Vault.info(dav()).generation(), "the key changed");
        assertEquals(Cloud.VaultState.REMOVED, laptop.round().vault());
        assertEquals(Cloud.VaultState.OPEN, desk.round().vault());
        assertEquals(List.of(DESK), view(desk).computers().stream().map(VaultView.Computer::machine).toList());
    }

    @Test
    void theListFlagsAComputerNewSinceTheLastRemovalAndOneWhoseKeyChanged() throws Exception {
        String code = create(desk);
        as(laptop, () -> laptop.cloud.vault().openVault(VaultView.Way.RECOVERY, code.toCharArray()));
        assertNull(desk.round().error()); // the laptop seen, and pinned, here
        VaultView.Computer seen = view(desk).computers().stream().filter(c -> c.machine().equals(LAPTOP)).findFirst().orElseThrow();
        assertFalse(seen.keyChanged() || seen.newSinceRemoval() || seen.here());
        assertNotNull(seen.firstSeen());

        VaultComputer other = new VaultComputer(cloud, dir, OTHER);
        as(other, () -> other.cloud.vault().openVault(VaultView.Way.RECOVERY, code.toCharArray()));
        as(desk, () -> desk.cloud.vault().removeComputer(OTHER));
        String again = as(desk, desk.cloud.vault()::newRecoveryCode);
        VaultComputer third = new VaultComputer(cloud, dir, "4e9d6c6b-3a4f-4f3b-8d84-9c5a0f4b5e77");
        as(third, () -> third.cloud.vault().openVault(VaultView.Way.RECOVERY, again.toCharArray()));
        // The laptop set up again: the same machine, another key.
        laptop.set(VaultParts.ID, null).set(VaultParts.KEY, null).set(VaultParts.PRIVATE, null);
        as(laptop, () -> laptop.cloud.vault().openVault(VaultView.Way.RECOVERY, again.toCharArray()));

        Map<String, VaultView.Computer> listed = new HashMap<>();
        view(desk).computers().forEach(c -> listed.put(c.machine(), c));
        assertTrue(listed.get(third.get("cloud.machine")).newSinceRemoval(), "came after the last removal");
        assertFalse(listed.get(LAPTOP).newSinceRemoval(), "seen before it");
        assertTrue(listed.get(LAPTOP).keyChanged(), "its key isn't the one first seen");
        as(desk, () -> desk.cloud.vault().acceptKey(LAPTOP));
        assertFalse(view(desk).computers().stream().filter(c -> c.machine().equals(LAPTOP)).findFirst().orElseThrow().keyChanged(),
            "accepted by the person");
    }

    @Test
    void aComputersNameIsOnePlainLine() throws Exception {
        String code = create(desk);
        byte[] vk;
        try (var r = desk.as(() -> CompletableFuture.completedFuture(VaultParts.open(dav(), (p, b) -> false, () -> { })))) {
            vk = r.key();
        }
        Vault.addComputer(dav(), vk, OTHER, "Mal\nvado\u202etxt.exe" + "x".repeat(100));
        Arrays.fill(vk, (byte) 0);
        String label = view(desk).computers().stream().filter(c -> c.machine().equals(OTHER)).findFirst().orElseThrow().label();
        assertTrue(label.startsWith("Mal vado txt.exe"), label);
        assertTrue(label.length() <= 65, "not too long to show: " + label.length());
        assertNotNull(code);
    }

    @Test
    void aChangeOffersTheNewKeyOnlyToTheComputersThePersonConfirmed() throws Exception {
        String code = create(desk);
        as(laptop, () -> laptop.cloud.vault().openVault(VaultView.Way.RECOVERY, code.toCharArray()));
        VaultComputer other = new VaultComputer(cloud, dir, OTHER);
        as(other, () -> other.cloud.vault().openVault(VaultView.Way.RECOVERY, code.toCharArray()));
        assertNull(desk.round().error());
        // The list showed the laptop; OTHER came after it was shown (or wasn't confirmed).
        Cloud.KeyChanged changed = as(desk, () -> desk.cloud.vault().rotateKey(Set.of(LAPTOP)));
        assertEquals(Set.of(OTHER), changed.refused().keySet(), "named");
        assertEquals(Cloud.VaultState.OPEN, laptop.round().vault());
        assertEquals(Cloud.VaultState.REMOVED, other.round().vault(), "it got nothing");
        assertEquals(Cloud.VaultState.OPEN, desk.round().vault(), "this computer always goes on");
    }

    @Test
    void aRemovalWhoseChangeOfKeyFailsSaysTheKeyWasntChanged() throws Exception {
        String code = create(desk);
        as(laptop, () -> laptop.cloud.vault().openVault(VaultView.Way.RECOVERY, code.toCharArray()));
        assertNull(desk.round().error());
        cloud.failOnce.add(Vault.INFO);
        ExecutionException e = assertThrows(ExecutionException.class,
            () -> as(desk, () -> desk.cloud.vault().removeAndChangeKey(LAPTOP, Set.of(DESK))));
        String said = I18n.t("cloud.error.vaultRemovedKeyKept", "");
        assertTrue(message(e).startsWith(said.substring(0, said.indexOf('('))), message(e));
        assertFalse(Vault.computers(dav()).containsKey(LAPTOP), "removed all the same");
        assertEquals(0, Vault.info(dav()).generation(), "the key didn't change");
    }

    @Test
    void everyNameFromTheAccountIsOnePlainLineWhereverItIsShown() throws Exception {
        String code = create(desk);
        String nasty = "Mal\nvado\u202Eexe.txt" + "y".repeat(200);
        byte[] vk;
        try (var r = desk.as(() -> CompletableFuture.completedFuture(VaultParts.open(dav(), (p, b) -> false, () -> { })))) {
            vk = r.key();
        }
        Vault.addComputer(dav(), vk, OTHER, nasty);
        Arrays.fill(vk, (byte) 0);
        String clean = VaultView.label(nasty);
        assertTrue(clean.startsWith("Mal vado exe.txt") && clean.length() <= 65, clean);
        assertEquals(clean, Vault.computers(dav()).get(OTHER), "what strays and damaged texts are named by");
        for (String hidden : new String[] {"\u200E", "\u200F", "\u061C", "\u200B", "\u200C", "\u200D", "\u2060", "\uFEFF"}) {
            assertEquals("ab", VaultView.label("a" + hidden + "b"), "U+" + Integer.toHexString(hidden.charAt(0)) + " taken out");
        }
        String flags = "\uD83C\uDDEA\uD83C\uDDF8".repeat(70); // 70 flags: two code points each
        String cut = VaultView.label(flags);
        assertTrue(cut.endsWith("\uD83C\uDDF8…"), "cut between whole flags, never half of one");
        assertEquals(64 * 4 + 1, cut.length(), "64 flags and the ellipsis");
        assertEquals(clean, as(desk, () -> desk.cloud.vault().rotateKey(Set.of())).refused().get(OTHER), "and refused computers");
        assertNotNull(code);
    }
}
