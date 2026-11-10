package app.unichat.ui;

import app.unichat.autosave.AutoSave;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** "Lo anterior" says what it's doing and where it is, not just "Buscando…". */
class ChatOptionsTest {
    private static final java.time.Instant NOV_3 = LocalDate.of(2026, 11, 3).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant();

    private static AutoSave.Exploring running(AutoSave.Doing doing, String what) {
        return new AutoSave.Exploring(true, NOV_3, false, 8, 2, 120, new AutoSave.Now(doing, NOV_3, what));
    }

    @Test
    void whileRunningItSaysWhatAndWhereAndHowMany() {
        assertEquals("Revisando los mensajes del 3 nov 2026\n120 mensajes revisados · 8 guardados · 2 ya no estaban",
                ChatOptions.pastState(running(AutoSave.Doing.LOOKING, null)));
        assertEquals("Descargando IMG_3944.MOV (del 3 nov 2026)\n120 mensajes revisados · 8 guardados · 2 ya no estaban",
                ChatOptions.pastState(running(AutoSave.Doing.DOWNLOADING, "IMG_3944.MOV")));
        assertEquals("Pidiendo al servidor los mensajes de antes del 3 nov 2026…\n120 mensajes revisados · 8 guardados · 2 ya no estaban",
                ChatOptions.pastState(running(AutoSave.Doing.ASKING_OLDER, null)));
    }

    @Test
    void whenOverItSaysHowFarItGot() {
        assertEquals("Revisado hasta el principio.\n120 mensajes revisados · 8 guardados · 2 ya no estaban",
                ChatOptions.pastState(new AutoSave.Exploring(false, NOV_3, true, 8, 2, 120, null)));
        assertEquals("", ChatOptions.pastState(new AutoSave.Exploring(false, null, false, 0, 0, 0, null)));
    }

    @Test
    void aDownloadSaysHowMuchHasArrived() {
        AutoSave.Exploring e = new AutoSave.Exploring(true, NOV_3, false, 8, 2, 120,
                new AutoSave.Now(AutoSave.Doing.DOWNLOADING, NOV_3, "IMG_3944.MOV", 0.45));
        assertEquals("Descargando IMG_3944.MOV (del 3 nov 2026) · 45 %\n120 mensajes revisados · 8 guardados · 2 ya no estaban",
                ChatOptions.pastState(e));
    }
}
