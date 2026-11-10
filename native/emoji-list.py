#!/usr/bin/env python3
"""Writes src/main/resources/app/unichat/emoji/emoji.txt, the emoji picker's list, from Unicode's emoji-test.txt, and
names_<language>.txt, what searching finds each one by, from CLDR's annotations (Unicode License v3).

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
CLDR = "https://raw.githubusercontent.com/unicode-org/cldr-json/48.2.3/cldr-json/"
# Each language Unichat speaks: its annotations and the derived ones (flags, joined emoji), pinned.
NAMES = {
    "es": [("cldr-annotations-full/annotations/es/annotations.json", "339cdf9ae5fe6d3250d3c53b95ff6d8a4dae96c7c0d279756ee7a73c43c84b56"),
           ("cldr-annotations-derived-full/annotationsDerived/es/annotations.json", "f9f43c327c5b76dbd337496738c0f5c526ee1c803dd683d60100b5fce85ab79e")],
    "en": [("cldr-annotations-full/annotations/en/annotations.json", "f22083cb86dffb63a643d5bacb5d9899f82d2fa5d388ad4f3aed72184acef505"),
           ("cldr-annotations-derived-full/annotationsDerived/en/annotations.json", "15c9457afa703a32dcc78d54626418a91e820fe0ddd67b7d491c4c4d9f5846ab")],
}


def fetch(url, sha256):
    data = urllib.request.urlopen(url).read()
    assert hashlib.sha256(data).hexdigest() == sha256, url + " changed"
    return data

data = fetch(URL, SHA256)
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

# names_<lang>.txt: one line per emoji of the list, "emoji<TAB>name|keyword|keyword", the name first (what a screen
# reader may say too). CLDR's keys leave out the variation selector (U+FE0F).
import json
for lang, files in NAMES.items():
    known = {}
    for path, sha in files:
        doc = json.loads(fetch(CLDR + path, sha))
        for key, v in next(iter(doc.values()))["annotations"].items():
            known.setdefault(key, v)
    lines, missing = [], 0
    for group in out.values():
        for e in group:
            a = known.get(e.replace("\ufe0f", ""))
            if a is None:
                missing += 1
                continue
            words = [w for w in a.get("tts", []) + a.get("default", []) if "|" not in w and "\t" not in w]
            lines.append(e + "\t" + "|".join(dict.fromkeys(words)))
    (target.parent / f"names_{lang}.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(lang, len(lines), "named,", missing, "without a name")
