// UnichatBar: the menu-bar item, drawn natively so it is as narrow as Apple's own (Java's TrayIcon adds a fixed
// 12pt margin on each side). Unichat starts it and drives it through stdin, one tab-separated command per line:
//   icon <base64 png>     44×44 px, shown at 22×22 pt (2x)
//   tip <text>            tooltip
//   count <text>          beside the icon, the unread count; empty shows the icon alone
//   menu <n>              followed by n lines, each "item <tag> <enabled 0|1> <checked -1|0|1> <label>" or "sep"
//   open                  opens the menu as a click would, or closes it if it is open (for tests)
//   notify <chat id> <sound 0|1> <title> <body>   a system notification (Notification Center, Do Not Disturb…)
//   clear <chat id>       removes that chat's notifications (it was read)
//   hotkey <0|1>          turns the global shortcut (⌃⌥U) off or on
// It writes to stdout: "click <tag>" when a menu item is chosen, "open <chat id>" when a notification is clicked,
// "hotkey" when the global shortcut is pressed,
// "notify-auth <1|0>" when notifications become allowed or not (also later), and exits when stdin closes (the app quit or died).
// Notifications need it to run from its bundle (UnichatBar.app, built by native/build-bar.sh): that's what macOS
// shows ("Unichat") and asks permission for. A plain java process can't post them.
import AppKit
import Carbon.HIToolbox
import UserNotifications

/** One writer for stdout: menu clicks and notification callbacks come from different threads. */
let output = DispatchQueue(label: "stdout")
func emit(_ line: String) {
    output.async {
        print(line)
        fflush(stdout)
    }
}

final class Notes: NSObject, UNUserNotificationCenterDelegate {
    private let center = UNUserNotificationCenter.current()
    /** Only touched on the output queue. */
    private var lastReported: Bool?

    func setUp() {
        center.delegate = self
        center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
            self.report(granted)
            // The first answer can be "no" even when the person clicks Allow, and System Settings can change it at
            // any time: keep asking, so Unichat follows without a restart.
            DispatchQueue.main.async {
                Timer.scheduledTimer(withTimeInterval: 15, repeats: true) { _ in self.check() }
            }
        }
    }

    private func check() {
        center.getNotificationSettings { settings in
            self.report(settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional)
        }
    }

    /** Tells Unichat only when the answer changes. */
    private func report(_ allowed: Bool) {
        output.async {
            if self.lastReported == allowed { return }
            self.lastReported = allowed
            print("notify-auth\t\(allowed ? 1 : 0)")
            fflush(stdout)
        }
    }

    func post(chat: String, sound: Bool, title: String, body: String) {
        let c = UNMutableNotificationContent()
        c.title = title
        c.body = body
        c.threadIdentifier = chat // grouped per chat in Notification Center
        c.userInfo = ["chat": chat]
        if sound { c.sound = .default }
        center.add(UNNotificationRequest(identifier: UUID().uuidString, content: c, trigger: nil)) { error in
            if let error { emit("notify-error\t\(error.localizedDescription)") }
        }
    }

    func clear(chat: String) {
        center.getDeliveredNotifications { delivered in
            let ids = delivered.filter { ($0.request.content.userInfo["chat"] as? String) == chat }.map { $0.request.identifier }
            self.center.removeDeliveredNotifications(withIdentifiers: ids)
        }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler done: @escaping () -> Void) {
        if let chat = response.notification.request.content.userInfo["chat"] as? String { emit("open\t\(chat)") }
        done()
    }

    /** Shown even if this helper counts as frontmost (it never is: it has no windows). */
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler done: @escaping (UNNotificationPresentationOptions) -> Void) {
        done([.banner, .list, .sound])
    }
}

/**
 * ⌃⌥U from any app: without a Dock icon Unichat isn't in ⌘Tab, and macOS has no way to be in one and not the other.
 * Carbon's hot keys are the ones that need no Accessibility permission.
 */
final class Hotkey {
    private var ref: EventHotKeyRef?
    private var installed = false

    func set(on: Bool) {
        if !on {
            if let ref { UnregisterEventHotKey(ref) }
            ref = nil
            return
        }
        if ref != nil { return }
        if !installed {
            var type = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
            InstallEventHandler(GetApplicationEventTarget(), { _, _, _ in
                emit("hotkey")
                return noErr
            }, 1, &type, nil, nil)
            installed = true
        }
        let id = EventHotKeyID(signature: OSType(0x554E4348), id: 1) // "UNCH"
        let status = RegisterEventHotKey(UInt32(kVK_ANSI_U), UInt32(controlKey | optionKey), id, GetApplicationEventTarget(), 0, &ref)
        emit(status == noErr ? "hotkey-ok" : "hotkey-error\t\(status)")
    }
}

final class Bar: NSObject, NSMenuDelegate {
    static let size: CGFloat = 22
    /** Variable length: macOS sizes the item to the image and centres it (a fixed length left it off-centre). */
    let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    let menu = NSMenu()
    private var expected = 0
    private var incoming: [[String]] = []
    private var isOpen = false

    override init() {
        super.init()
        menu.autoenablesItems = false
        menu.delegate = self
        item.menu = menu
        item.button?.imagePosition = .imageOnly
    }

    func handle(_ line: String) {
        let parts = line.components(separatedBy: "\t")
        switch parts[0] {
        case "icon":
            guard parts.count > 1, let data = Data(base64Encoded: parts[1]), let image = NSImage(data: data) else { return }
            image.size = NSSize(width: Bar.size, height: Bar.size)
            item.button?.image = image
        case "open": // as if clicked, or closes it if open (lets tests capture the highlight and the open menu)
            if isOpen { menu.cancelTracking() } else { item.button?.performClick(nil) }
        case "count":
            let text = parts.count > 1 ? parts[1] : ""
            item.button?.title = text
            item.button?.font = NSFont.monospacedDigitSystemFont(ofSize: NSFont.smallSystemFontSize + 1, weight: .semibold)
            item.button?.imagePosition = text.isEmpty ? .imageOnly : .imageLeading
        case "tip":
            item.button?.toolTip = parts.count > 1 ? parts[1] : nil
        case "menu":
            expected = parts.count > 1 ? Int(parts[1]) ?? 0 : 0
            incoming = []
            if expected == 0 { apply() }
        case "item", "sep":
            guard expected > 0 else { return }
            incoming.append(parts)
            if incoming.count == expected { apply() }
        case "notify":
            guard parts.count >= 5 else { return }
            notes.post(chat: parts[1], sound: parts[2] == "1", title: parts[3], body: parts[4])
        case "clear":
            if parts.count > 1 { notes.clear(chat: parts[1]) }
        case "hotkey":
            hotkey.set(on: parts.count > 1 && parts[1] == "1")
        default:
            break
        }
    }

    /** Same shape as the open menu: update in place (safe while it is showing); otherwise rebuild. */
    private func apply() {
        let sameShape = menu.items.count == incoming.count
            && zip(menu.items, incoming).allSatisfy { $0.isSeparatorItem == ($1[0] == "sep") }
        if !sameShape {
            menu.removeAllItems()
            for p in incoming {
                menu.addItem(p[0] == "sep" ? .separator() : NSMenuItem(title: "", action: #selector(pick(_:)), keyEquivalent: ""))
            }
        }
        for (mi, p) in zip(menu.items, incoming) where p[0] == "item" && p.count >= 5 {
            mi.target = self
            mi.tag = Int(p[1]) ?? -1
            mi.isEnabled = p[2] == "1"
            mi.state = p[3] == "1" ? .on : .off
            mi.title = p[4]
        }
        expected = 0
    }

    func menuWillOpen(_ menu: NSMenu) { isOpen = true }
    func menuDidClose(_ menu: NSMenu) { isOpen = false }

    @objc func pick(_ sender: NSMenuItem) {
        emit("click\t\(sender.tag)")
    }
}

let app = NSApplication.shared
app.setActivationPolicy(.accessory)
let notes = Notes()
// Only from the bundle: outside one, UNUserNotificationCenter aborts the process.
if Bundle.main.bundleIdentifier != nil { notes.setUp() } else { emit("notify-auth\t0") }
let hotkey = Hotkey()
let bar = Bar()
Thread {
    while let line = readLine() {
        // not DispatchQueue.main: it waits while the menu is open; these modes also run during menu tracking
        RunLoop.main.perform(inModes: [.common, .eventTracking]) { bar.handle(line) }
        CFRunLoopWakeUp(CFRunLoopGetMain())
    }
    DispatchQueue.main.async { exit(0) }
}.start()
app.run()
