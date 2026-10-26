// UnichatBar: the menu-bar item, drawn natively so it is as narrow as Apple's own (Java's TrayIcon adds a fixed
// 12pt margin on each side). Unichat starts it and drives it through stdin, one tab-separated command per line:
//   icon <base64 png>     44×44 px, shown at 22×22 pt (2x)
//   tip <text>            tooltip
//   menu <n>              followed by n lines, each "item <tag> <enabled 0|1> <checked -1|0|1> <label>" or "sep"
//   open                  opens the menu as a click would, or closes it if it is open (for tests)
// It writes "click <tag>" to stdout when a menu item is chosen, and exits when stdin closes (the app quit or died).
import AppKit

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
        print("click\t\(sender.tag)")
        fflush(stdout)
    }
}

let app = NSApplication.shared
app.setActivationPolicy(.accessory)
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
