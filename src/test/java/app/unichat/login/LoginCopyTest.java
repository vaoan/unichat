package app.unichat.login;

import app.unichat.model.Network;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginCopyTest {
    private static BridgeLogin.Flow flow(String id, String name) {
        return new BridgeLogin.Flow(id, name, name + " description");
    }

    @Test
    void telegramHidesDeveloperFlowsAndPutsQrFirst() {
        List<LoginCopy.Choice> c = LoginCopy.choices(Network.TELEGRAM, List.of(
                flow("phone", "Phone Number"), flow("bot", "Bot token"), flow("manual", "Manual"), flow("qr", "QR code")));
        assertEquals(List.of("qr", "phone"), c.stream().map(x -> x.flow().id()).toList());
        assertEquals("Escanear un código QR", c.get(0).title());
        assertFalse(c.get(0).advanced());
    }

    @Test
    void unknownFlowsAreStillOfferedAsAdvancedWithTheBridgesText() {
        List<LoginCopy.Choice> c = LoginCopy.choices(Network.WHATSAPP, List.of(
                flow("passkey", "Passkey"), flow("qr", "QR")));
        assertEquals("qr", c.get(0).flow().id());
        LoginCopy.Choice unknown = c.get(1);
        assertTrue(unknown.advanced(), "goes under Más opciones");
        assertEquals("Passkey", unknown.title());
        assertEquals("Passkey description", unknown.subtitle());
    }

    @Test
    void metaShowsEachNetworkItsOwnFlows() {
        List<BridgeLogin.Flow> meta = List.of(flow("facebook", "facebook.com"), flow("messenger", "messenger.com"),
                flow("instagram", "instagram.com"), flow("messenger-lite", "Messenger iOS"));
        assertEquals(List.of("instagram"), LoginCopy.choices(Network.INSTAGRAM, meta).stream().map(x -> x.flow().id()).toList());
        assertEquals(List.of("facebook", "messenger"), LoginCopy.choices(Network.MESSENGER, meta).stream().map(x -> x.flow().id()).toList());
    }

    @Test
    void knownStepsGetSpanishSteps() {
        BridgeLogin.Step qr = new BridgeLogin.Step("L", "display_and_wait", "fi.mau.whatsapp.login.qr", "Scan the QR code",
                List.of(), new BridgeLogin.Display("qr", "2@x", null, false), null, null);
        LoginCopy.StepText t = LoginCopy.step(Network.WHATSAPP, qr);
        assertEquals("Escanea el código con tu teléfono", t.title());
        assertEquals(3, t.steps().size());
        assertTrue(t.steps().get(1).contains("Dispositivos vinculados"));

        BridgeLogin.Step pwd = new BridgeLogin.Step("L", "user_input", "fi.mau.telegram.login.password.incorrect", "Incorrect password",
                List.of(), null, null, null);
        assertEquals("La contraseña no es correcta", LoginCopy.step(Network.TELEGRAM, pwd).title());
    }

    @Test
    void unknownStepsShowTheBridgesInstructions() {
        BridgeLogin.Step s = new BridgeLogin.Step("L", "user_input", "fi.mau.new.thing", "Enter the magic word", List.of(), null, null, null);
        assertEquals(List.of("Enter the magic word"), LoginCopy.step(Network.SLACK, s).steps());
    }

    @Test
    void fieldLabelsInSpanish() {
        assertEquals("Número de teléfono", LoginCopy.fieldLabel(new BridgeLogin.Field("phone_number", "p", "Phone number", "", null, false, null, List.of())));
        assertEquals("Workspace", LoginCopy.fieldLabel(new BridgeLogin.Field("select", "ws", "Workspace", "", null, false, null, List.of("a"))));
    }

    @Test
    void xChatPinIsExplained() {
        BridgeLogin.Step create = new BridgeLogin.Step("L", "user_input", "fi.mau.twitter.login.juicebox_pin",
                "No PIN code is registered yet. Create your X Chat PIN below.",
                List.of(new BridgeLogin.Field("2fa_code", "pin", "PIN", "", null, false, "^[0-9]{4}$", List.of())), null, null, null);
        LoginCopy.StepText t = LoginCopy.step(Network.X, create);
        assertEquals("Crea tu PIN de X Chat", t.title());
        assertTrue(t.steps().get(1).contains("Inventa uno"));
        assertEquals("PIN de 4 dígitos", LoginCopy.fieldLabel(create.fields().get(0)));
        BridgeLogin.Step enter = new BridgeLogin.Step("L", "user_input", "fi.mau.twitter.login.juicebox_pin", "Enter your X Chat PIN.",
                List.of(), null, null, null);
        assertEquals("Escribe tu PIN de X Chat", LoginCopy.step(Network.X, enter).title());
    }

    private static BridgeLogin.Field pin(String pattern) {
        return new BridgeLogin.Field("password", "pin", "PIN", "", null, true, pattern, List.of());
    }

    @Test
    void aPinSaysTheLengthItsNetworkAsksFor() {
        assertEquals("PIN de 6 dígitos", LoginCopy.fieldLabel(pin("^[0-9]{6}$")), "Messenger");
        assertEquals("PIN de 4 dígitos", LoginCopy.fieldLabel(pin("^[0-9]{4}$")), "X");
        assertEquals("PIN", LoginCopy.fieldLabel(pin(null)), "never a length nobody said");
    }

    @Test
    void messengersHistoryPinExplainsThatItCanBeLeftEmpty() {
        BridgeLogin.Step step = new BridgeLogin.Step("L", "user_input", "fi.mau.meta.history_pin",
                "Enter your Messenger secure storage PIN to restore encrypted chat history. Leave it empty to continue without restoring history.",
                List.of(pin("^[0-9]{6}$")), null, null, null);
        LoginCopy.StepText t = LoginCopy.step(Network.MESSENGER, step);
        assertTrue(t.title().contains("historial"), t.title());
        assertTrue(String.join(" ", t.steps()).contains("6 dígitos"));
        assertTrue(String.join(" ", t.steps()).contains("vacío"));
    }
}
