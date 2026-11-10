package app.unichat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/** The package rules of docs/ARCHITECTURE.md. A failure here means a new dependency broke a layer, not a flaky test. */
class ArchitectureTest {
    private static final JavaClasses APP = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("app.unichat");

    private static final String ROOT = "app.unichat";

    @Test
    void packagesHaveNoCycles() {
        slices().matching("app.unichat.(*)..").should().beFreeOfCycles().check(APP);
    }

    @Test
    void onlyTheEntryPointIsAtTheRoot() {
        noClasses().that().resideOutsideOfPackage(ROOT)
                .should().dependOnClassesThat().resideInAPackage(ROOT).check(APP);
    }

    @Test
    void javaFxStaysInTheUi() {
        noClasses().that().resideOutsideOfPackages("..ui..", ROOT)
                .should().dependOnClassesThat().resideInAnyPackage("javafx..").check(APP);
    }

    @Test
    void nothingBelowTheUiKnowsTheUi() {
        noClasses().that().resideOutsideOfPackages("..ui..", ROOT)
                .should().dependOnClassesThat().resideInAPackage("..ui..").check(APP);
    }

    @Test
    void theUiTalksToChatServiceNotToMatrix() {
        // LoginView signs in, which creates the service: the one place that has to name it.
        noClasses().that().resideInAPackage("..ui..").and().doNotHaveSimpleName("LoginView")
                .should().dependOnClassesThat().resideInAPackage("..matrix..").check(APP);
    }

    @Test
    void theServiceApiDoesNotKnowItsImplementation() {
        noClasses().that().resideInAPackage("..service..")
                .should().dependOnClassesThat().resideInAnyPackage("..matrix..", "..server..").check(APP);
    }

    @Test
    void theLocalServerDoesNotKnowChats() {
        noClasses().that().resideInAPackage("..server..")
                .should().dependOnClassesThat().resideInAnyPackage("..matrix..", "..service..", "..model..").check(APP);
    }

    @Test
    void matrixAndTheLocalServerShareOnlyTheBridgePackage() {
        noClasses().that().resideInAPackage("..matrix..")
                .should().dependOnClassesThat().resideInAPackage("..server..").check(APP);
    }

    @Test
    void theUpdateCheckKnowsOnlySettings() {
        noClasses().that().resideInAPackage("..update..")
                .should().dependOnClassesThat().resideInAnyPackage("..model..", "..service..", "..matrix..", "..server..", "..login..", "..bridge..")
                .check(APP);
    }

    @Test
    void savingEverythingAChatReceivesDoesntKnowMatrixNorTheUi() {
        noClasses().that().resideInAPackage("..autosave..")
                .should().dependOnClassesThat().resideInAnyPackage("..matrix..", "..server..", "..login..", "..bridge..",
                        "..stt..", "..assist..", "..gif..", "..update..", "..voice..", "..links..", "..cloak..")
                .check(APP);
    }

    @Test
    void theCloudKnowsOnlySettings() {
        noClasses().that().resideInAPackage("..cloud..")
                .should().dependOnClassesThat().resideInAnyPackage("..model..", "..service..", "..matrix..", "..server..",
                        "..login..", "..bridge..", "..stt..", "..assist..", "..gif..", "..update..", "..video..", "..voice..",
                        "..links..", "..cloak..", "..autosave..", "..ui..")
                .check(APP);
    }

    @Test
    void imageProtectionKnowsOnlySettings() {
        noClasses().that().resideInAPackage("..cloak..")
                .should().dependOnClassesThat().resideInAnyPackage("..model..", "..service..", "..matrix..", "..server..",
                        "..login..", "..bridge..", "..stt..", "..assist..", "..gif..", "..update..", "..video..", "..voice..",
                        "..links..")
                .check(APP);
    }

    @Test
    void theBaseLayersStayAtTheBottom() {
        noClasses().that().resideInAnyPackage("..model..", "..support..", "..bridge..")
                .should().dependOnClassesThat().resideInAnyPackage("..settings..", "..service..", "..matrix..", "..server..", "..login..", "..stt..", "..assist..", "..gif..", "..update..", "..cloak..", "..autosave..")
                .check(APP);
        noClasses().that().resideInAPackage("..settings..")
                .should().dependOnClassesThat().resideInAnyPackage("..model..", "..service..", "..matrix..", "..server..", "..login..", "..stt..", "..gif..", "..update..", "..cloak..", "..autosave..")
                .check(APP);
    }
}
