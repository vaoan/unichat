package app.unichat.ui;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Popup emoji grid by category; also provides the quick-reaction set. */
public final class EmojiPicker {
    private EmojiPicker() {}

    public static final String[] QUICK = {"❤️", "👍", "😂", "😮", "😢", "🙏"};

    static final Map<String, String> CATEGORIES = new LinkedHashMap<>();
    static {
        CATEGORIES.put("Caras", "😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🥵 🥶 🥴 😵 🤯 🤠 🥳 😎 🤓 🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 💀 💩 🤡 👻 👽 🤖");
        CATEGORIES.put("Gestos", "👋 🤚 ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏 💪 🫶 👀 🧠");
        CATEGORIES.put("Corazones", "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💯 💢 💥 💫 💦 💨 🔥 ✨ ⭐ 🌟");
        CATEGORIES.put("Animales", "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🦆 🦅 🦉 🐺 🐴 🦄 🐝 🦋 🐌 🐞 🐢 🐍 🐙 🐬 🐳 🦈");
        CATEGORIES.put("Comida", "🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🍒 🍑 🥭 🍍 🥥 🥑 🍅 🌽 🥕 🍞 🧀 🍳 🥓 🍔 🍟 🍕 🌭 🌮 🌯 🍝 🍜 🍣 🍰 🎂 🍫 🍿 🍩 🍪 ☕ 🍺 🍻 🥂 🍷");
        CATEGORIES.put("Actividades", "⚽ 🏀 🏈 ⚾ 🎾 🏐 🎱 🏓 🎮 🎲 🎯 🎳 🎸 🎹 🎤 🎧 🎬 🎨 🎉 🎊 🎁 🏆 🥇");
        CATEGORIES.put("Objetos", "📱 💻 ⌨️ 🖥️ 📷 📞 ⏰ 💡 💸 💰 💳 📎 📌 ✏️ 📝 📅 📦 ✉️ 🔒 🔑 🔔 🚗 ✈️ 🏠 ⏳ ✅ ❌ ❓ ❗ ⚠️ 🚀");
    }

    /** Shows the picker anchored above {@code anchor}; {@code onPick} receives the emoji. */
    public static void show(Node anchor, Consumer<String> onPick) {
        Popup popup = new Popup();
        popup.setAutoHide(true);
        VBox content = new VBox(6);
        content.setPadding(new Insets(10));
        for (Map.Entry<String, String> cat : CATEGORIES.entrySet()) {
            Label title = new Label(cat.getKey());
            title.getStyleClass().add("emoji-category");
            FlowPane grid = new FlowPane(2, 2);
            grid.setPrefWrapLength(300);
            for (String e : cat.getValue().split(" ")) {
                Button b = new Button(e);
                b.getStyleClass().add("emoji-button");
                b.setOnAction(ev -> {
                    onPick.accept(e);
                    popup.hide();
                });
                grid.getChildren().add(b);
            }
            content.getChildren().addAll(title, grid);
        }
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefSize(340, 320);
        scroll.getStyleClass().add("emoji-picker");
        scroll.getStylesheets().addAll(anchor.getScene().getStylesheets());
        VBox wrap = new VBox(scroll);
        wrap.getStyleClass().add("popup-surface");
        wrap.getStyleClass().addAll(anchor.getScene().getRoot().getStyleClass());
        wrap.setStyle(anchor.getScene().getRoot().getStyle());
        popup.getContent().add(wrap);
        var b = anchor.localToScreen(anchor.getBoundsInLocal());
        popup.show(anchor, b.getMinX(), b.getMinY() - 330);
    }
}
