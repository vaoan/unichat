#!/usr/bin/env python3
"""Writes src/main/resources/app/unichat/emoji/emoji.txt, the emoji picker's list, from Unicode's emoji-test.txt.

Emoji 15.0: what macOS 13.3 (Unichat's minimum) and Windows 11 draw; a newer emoji shows as a box there. Only
fully-qualified emoji, without the skin-tone variants (they would double the list) and the "Component" group
(skin tones and hair styles alone). One line per group: its key, a tab, the emoji separated by spaces.

python3 native/emoji-list.py   (downloads the pinned file and checks it)
"""
import hashlib
import pathlib
import urllib.request

URL = "https://unicode.org/Public/emoji/15.0/emoji-test.txt"
SHA256 = "8445f23ac8388e096be19d0262e14fceff856ff52093f2356dc89485f1a853db"
GROUPS = {"Smileys & Emotion": "smileys", "People & Body": "people", "Animals & Nature": "animals",
          "Food & Drink": "food", "Travel & Places": "travel", "Activities": "activities", "Objects": "objects",
          "Symbols": "symbols", "Flags": "flags"}
SKIN_TONES = {chr(c) for c in range(0x1F3FB, 0x1F400)}

data = urllib.request.urlopen(URL).read()
assert hashlib.sha256(data).hexdigest() == SHA256, "emoji-test.txt changed"
out, group = {}, None
for line in data.decode("utf-8").splitlines():
    if line.startswith("# group: "):
        group = GROUPS.get(line[len("# group: "):])
        continue
    if group is None or line.startswith("#") or "; fully-qualified" not in line:
        continue
    emoji = line.split("#", 1)[1].split()[0]
    if not SKIN_TONES & set(emoji):
        out.setdefault(group, []).append(emoji)
target = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/app/unichat/emoji/emoji.txt"
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text("".join(f"{g}\t{' '.join(e)}\n" for g, e in out.items()), encoding="utf-8")
print(sum(len(e) for e in out.values()), "emoji in", len(out), "groups")
