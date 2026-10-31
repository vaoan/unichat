package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.model.Member;
import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.service.ChatService;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Consumer;

/** Collapsible right panel: chat identity, quick actions, members and shared media. */
public final class InfoPane extends ScrollPane {
    private final ChatService svc;
    private final VBox box = new VBox(16);
    private final Runnable close;
    private final Consumer<String> toast;
    private Chat chat;

    public InfoPane(ChatService svc, Runnable close, Consumer<String> toast) {
        this.svc = svc;
        this.close = close;
        this.toast = toast;
        getStyleClass().add("info-pane");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        box.getStyleClass().add("info-content");
        setContent(box);
        setPrefWidth(300);
        setMinWidth(260);
    }

    public void show(Chat c) {
        chat = c;
        refresh();
    }

    public void refresh() {
        box.getChildren().clear();
        if (chat == null) return;
        Chat c = chat;

        Button x = Composer.iconButton(Icons.CLOSE, "Cerrar", close);
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        Label head = new Label("Información");
        head.getStyleClass().add("info-heading");
        HBox top = new HBox(head, grow, x);
        top.setAlignment(Pos.CENTER_LEFT);

        Avatar a = new Avatar(84);
        a.set(c.title(), c.id(), c.avatarUrl(), svc);
        a.setNetwork(c.network(), true);
        Label title = new Label(c.title());
        title.getStyleClass().add("info-title");
        title.setWrapText(true);
        Label net = new Label(c.network().displayName() + (c.group() ? " · Grupo" : " · Chat directo"));
        net.getStyleClass().add("info-subtitle");
        VBox ident = new VBox(8, a, title, net);
        ident.setAlignment(Pos.CENTER);
        if (c.topic() != null && !c.topic().isBlank()) {
            Label topic = new Label(c.topic());
            topic.setWrapText(true);
            topic.getStyleClass().add("info-topic");
            ident.getChildren().add(topic);
        }

        HBox actions = new HBox(8,
                action(Icons.BELL_OFF, c.muted() ? "Activar" : "Silenciar", c.muted(), () -> svc.setMuted(c.id(), !c.muted())),
                action(Icons.STAR, c.favorite() ? "Favorito" : "Favorito", c.favorite(), () -> svc.setFavorite(c.id(), !c.favorite())),
                action(Icons.PIN, "Fijar", c.pinned(), () -> svc.setPinned(c.id(), !c.pinned())),
                action(Icons.ARCHIVE, c.archived() ? "Archivado" : "Archivar", c.archived(), () -> svc.setArchived(c.id(), !c.archived())));
        actions.setAlignment(Pos.CENTER);

        box.getChildren().addAll(top, ident, actions);

        if (c.reminderAt() != null) {
            Label rem = new Label("⏰ Recordatorio: " + Formats.listStamp(c.reminderAt(), Instant.now(), ZoneId.systemDefault())
                    + " " + Formats.time(c.reminderAt(), ZoneId.systemDefault()));
            rem.getStyleClass().add("info-reminder");
            box.getChildren().add(rem);
        }

        List<Message> media = svc.messages(c.id()).stream()
                .filter(m -> !m.deleted() && m.type() == MessageType.IMAGE && m.mediaUrl() != null).toList();
        if (!media.isEmpty()) {
            box.getChildren().add(section("Multimedia (" + media.size() + ")"));
            FlowPane grid = new FlowPane(4, 4);
            for (Message m : media.subList(Math.max(0, media.size() - 9), media.size())) grid.getChildren().add(thumb(m));
            box.getChildren().add(grid);
        }

        if (c.group() || c.members().size() > 2) {
            box.getChildren().add(section("Participantes (" + c.members().size() + ")"));
            VBox people = new VBox(2);
            for (Member m : c.members().stream().limit(100).toList()) {
                Avatar pa = new Avatar(30);
                pa.set(m.displayName(), m.id(), m.avatarUrl(), svc);
                Label n = new Label(m.id().equals(svc.myUserId()) ? m.displayName() + " (tú)" : m.displayName());
                n.getStyleClass().add("member-name");
                HBox row = new HBox(10, pa, n);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().add("member-row");
                people.getChildren().add(row);
            }
            box.getChildren().add(people);
        }

        Button leave = new Button(c.group() ? "Salir del grupo" : "Eliminar chat");
        leave.getStyleClass().add("danger-button");
        leave.setMaxWidth(Double.MAX_VALUE);
        leave.setOnAction(e -> svc.leaveChat(c.id()).thenRun(() -> Platform.runLater(() -> toast.accept("Saliste de " + c.title()))));
        Button lowPrio = new Button(c.lowPriority() ? "Mover a la bandeja" : "Mover a prioridad baja");
        lowPrio.getStyleClass().add("secondary-button");
        lowPrio.setMaxWidth(Double.MAX_VALUE);
        lowPrio.setOnAction(e -> svc.setLowPriority(c.id(), !c.lowPriority()));
        box.getChildren().addAll(new VBox(8, lowPrio, leave));
    }

    private Node section(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("info-section");
        return l;
    }

    private Node action(String icon, String text, boolean active, Runnable run) {
        Button b = new Button(text);
        b.setGraphic(Icons.icon(icon, 18));
        b.setContentDisplay(javafx.scene.control.ContentDisplay.TOP);
        b.getStyleClass().add("info-action");
        if (active) b.getStyleClass().add("active");
        b.setOnAction(e -> run.run());
        b.setAccessibleText(text + (active ? ", activado" : ""));
        return b;
    }

    private Node thumb(Message m) {
        ImageView v = new ImageView();
        v.setFitWidth(84);
        v.setFitHeight(84);
        v.getStyleClass().add("media-thumb");
        svc.media(m.mediaUrl(), 168, 168).thenAcceptAsync(p -> {
            try {
                Image img = AnimatedImage.still(p, 168, 168);
                Platform.runLater(() -> v.setImage(img));
            } catch (java.io.IOException e) {
                System.err.println("Could not decode a shared photo: " + e);
            }
        }, AnimatedImage.DECODER);
        v.setOnMouseClicked(e -> {
            if (getScene() != null) MediaViewer.show(getScene().getWindow(), svc.media(m.mediaUrl(), 0, 0), m.fileName());
        });
        v.setCursor(javafx.scene.Cursor.HAND);
        return v;
    }
}
