#!/usr/bin/env python3
"""Writes THIRD-PARTY.md: every third-party piece Unichat's packages carry, with its license texts and notices.

Run after changing a dependency or a pinned version, with the tuwunel build present (its Rust crates are read from
~/Library/Caches/unichat-build/tuwunel and ~/.cargo). Stops if any license text can't be found: never drop one silently.
Usage: python3 -I native/third-party.py   (GH_TOKEN optional)
"""
import glob, json, os, re, sys, tomllib, urllib.request, zipfile

HOME = os.path.expanduser("~")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TUWUNEL = HOME + "/Library/Caches/unichat-build/tuwunel"
M2 = HOME + "/.m2/repository/"
GH = "https://raw.githubusercontent.com/"


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "unichat-third-party"})
    if url.startswith("https://api.github.com") and os.environ.get("GH_TOKEN"):
        req.add_header("Authorization", "Bearer " + os.environ["GH_TOKEN"])
    try:
        return urllib.request.urlopen(req, timeout=60).read().decode("utf-8", "replace")
    except Exception as e:
        sys.exit(f"No license text from {url}: {e}")


def jar(path, member):
    try:
        with zipfile.ZipFile(M2 + path) as z:
            return z.read(member).decode("utf-8", "replace")
    except Exception as e:
        sys.exit(f"No {member} in {path}: {e}")


def pinned(pattern, file):
    m = re.search(pattern, open(os.path.join(ROOT, file)).read(), re.M)
    if not m: sys.exit(f"Version not found in {file}")
    return m.group(1)


JACKSON = "com/fasterxml/jackson/core/jackson-core/2.17.2/jackson-core-2.17.2.jar"
WHISPER = pinned(r"^VERSION=(\S+)", "native/build-whisper.sh")
WEBP = pinned(r"^VERSION=(\S+)", "native/fetch-gif2webp.sh")
OLM = pinned(r"^VERSION=(\S+)", "native/build-libolm.sh")
TUWUNEL_V = pinned(r"^TUWUNEL_VERSION=(\S+)", "src/main/resources/app/unichat/server/versions.env")

# name, version, license, what it's for, [(heading, text)]
JFX_LEGAL = [p["path"] for p in json.loads(fetch("https://api.github.com/repos/openjdk/jfx/git/trees/master?recursive=1"))["tree"]
             if re.match(r"modules/javafx\.(base|graphics|controls|media|web|swing)/src/main/legal/.+\.md$", p["path"])]
COMPONENTS = [
    ("whisper.cpp (whisper-cli)", WHISPER, "MIT", "transcribir notas de voz",
     [("LICENSE", fetch(f"{GH}ggml-org/whisper.cpp/v{WHISPER}/LICENSE"))]),
    ("libwebp (gif2webp)", WEBP, "BSD-3-Clause + concesión de patentes", "stickers animados",
     [("COPYING", fetch(f"{GH}webmproject/libwebp/v{WEBP}/COPYING")), ("PATENTS", fetch(f"{GH}webmproject/libwebp/v{WEBP}/PATENTS"))]),
    ("GIFLib (dentro de gif2webp)", "la que enlaza libwebp", "MIT", "leer GIF",
     [("COPYING", fetch("https://sourceforge.net/p/giflib/code/ci/master/tree/COPYING?format=raw"))]),
    ("libolm", OLM, "Apache-2.0", "lo necesitan los puentes en macOS",
     [("LICENSE", fetch(f"https://gitlab.matrix.org/matrix-org/olm/-/raw/{OLM}/LICENSE"))]),
    ("tuwunel", TUWUNEL_V, "Apache-2.0", "servidor Matrix local",
     [("LICENSE", open(TUWUNEL + "/LICENSE").read())]),
    ("JavaFX / OpenJFX", "23.0.1", "GPL-2.0 con excepción de classpath; WebKit, GStreamer y GLib con partes LGPL", "interfaz",
     [("LICENSE", fetch(GH + "openjdk/jfx/master/LICENSE")), ("ADDITIONAL_LICENSE_INFO", fetch(GH + "openjdk/jfx/master/ADDITIONAL_LICENSE_INFO")),
      ("ASSEMBLY_EXCEPTION", fetch(GH + "openjdk/jfx/master/ASSEMBLY_EXCEPTION"))] + [(p.split("/")[-1], fetch(GH + "openjdk/jfx/master/" + p)) for p in JFX_LEGAL]),
    ("Jackson (core, databind, annotations, YAML)", "2.17.2", "Apache-2.0", "JSON y YAML",
     [("NOTICE", jar(JACKSON, "META-INF/NOTICE")), ("FastDoubleParser LICENSE", jar(JACKSON, "META-INF/FastDoubleParser-LICENSE")),
      ("FastDoubleParser NOTICE", jar(JACKSON, "META-INF/FastDoubleParser-NOTICE"))]),
    ("SnakeYAML", "2.2", "Apache-2.0", "YAML", [("LICENSE", fetch(GH + "snakeyaml/snakeyaml/master/LICENSE.txt"))]),
    ("SQLite JDBC (incluye SQLite)", "3.46.1.3", "Apache-2.0; SQLite es de dominio público", "leer bases de los puentes",
     [("LICENSE", jar("org/xerial/sqlite-jdbc/3.46.1.3/sqlite-jdbc-3.46.1.3.jar", "META-INF/maven/org.xerial/sqlite-jdbc/LICENSE")),
      ("LICENSE.zentus", jar("org/xerial/sqlite-jdbc/3.46.1.3/sqlite-jdbc-3.46.1.3.jar", "META-INF/maven/org.xerial/sqlite-jdbc/LICENSE.zentus"))]),
    ("JNA", "5.14.0", "Apache-2.0 (elegida entre Apache-2.0 y LGPL-2.1)", "llamadas nativas",
     [("LICENSE", jar("net/java/dev/jna/jna/5.14.0/jna-5.14.0.jar", "META-INF/LICENSE"))]),
    ("ZXing core", "3.5.3", "Apache-2.0", "códigos QR", [("NOTICE", fetch(GH + "zxing/zxing/zxing-3.5.3/NOTICE"))]),
    ("TwelveMonkeys ImageIO", "3.12.0", "BSD-3-Clause", "leer WebP", [("LICENSE", fetch(GH + "haraldk/TwelveMonkeys/master/LICENSE.txt"))]),
    ("JCodec (incluye un decodificador derivado de JAAD)", "0.2.5", "BSD-2-Clause", "videos desde GIF",
     [("LICENSE", fetch(GH + "jcodec/jcodec/master/LICENSE"))]),
    ("Concentus", "1.0.2", "BSD-3-Clause", "decodificar notas de voz Opus", [("LICENSE", fetch(GH + "lostromb/concentus/master/LICENSE"))]),
]
APACHE = fetch("https://www.apache.org/licenses/LICENSE-2.0.txt")


def crates():
    """External crates of tuwunel's lock file that were really compiled (target/release/.fingerprint)."""
    built = {re.sub(r"-[0-9a-f]{16}$", "", f) for f in os.listdir(TUWUNEL + "/target/release/.fingerprint")}
    dirs = {os.path.basename(d): d for d in glob.glob(HOME + "/.cargo/registry/src/*/*")}
    manifests = {}
    for m in glob.glob(HOME + "/.cargo/git/checkouts/*/*/**/Cargo.toml", recursive=True):
        try:
            p = tomllib.load(open(m, "rb")).get("package")
            if p and isinstance(p.get("name"), str): manifests.setdefault(p["name"], os.path.dirname(m))
        except Exception:
            pass
    out = []
    locked = tomllib.load(open(TUWUNEL + "/Cargo.lock", "rb"))["package"]
    source = lambda p: dirs.get(f"{p['name']}-{p['version']}") or manifests.get(p["name"])
    for p in locked:
        if not p.get("source") or p["name"] not in built: continue
        d = source(p)
        if not d:
            # The fingerprint has no version: with several locked, the one never downloaded wasn't the one compiled.
            if any(q["name"] == p["name"] and q["version"] != p["version"] and source(q) for q in locked): continue
            sys.exit(f"No source for crate {p['name']} {p['version']}")
        lic = tomllib.load(open(d + "/Cargo.toml", "rb")).get("package", {}).get("license")
        out.append((p["name"], p["version"], lic if isinstance(lic, str) else None, d))
    return sorted(out, key=lambda c: c[0].lower())


NAMES = re.compile(r"^(licen[cs]e|copying|unlicense|notice|copyright)", re.I)


def texts(d):
    """License files of a crate, looking up to the repository root for workspace members. MIT preferred over Apache."""
    cur = d
    for _ in range(4):
        files = [f for f in os.listdir(cur) if NAMES.match(f) and os.path.isfile(os.path.join(cur, f))]
        if files:
            pick = [f for f in files if not re.search(r"apache", f, re.I)] or files
            return [open(os.path.join(cur, f), errors="replace").read() for f in sorted(pick)]
        cur = os.path.dirname(cur)
    return []


def block(text):
    return "```text\n" + text.strip().replace("```", "'''") + "\n```\n"


rust = crates()
groups, bare = {}, []
for name, version, lic, d in rust:
    found = texts(d)
    if not found: bare.append(f"{name} {version}")
    for t in found: groups.setdefault(t.strip(), []).append(f"{name} {version}")

o = ["# Componentes de terceros\n",
     "El código propio de Unichat es [0BSD](LICENSE). Los paquetes de Unichat también llevan los componentes de abajo, "
     "cada uno con su propia licencia; 0BSD no los cubre. Archivo generado con `native/third-party.py`.\n",
     "## Incluidos en la app\n", "| Componente | Versión | Licencia | Para qué |\n|---|---|---|---|"]
for n, v, l, why, _ in COMPONENTS: o.append(f"| {n} | {v} | {l} | {why} |")
o.append(f"| Crates de Rust dentro de tuwunel | ver lista | varias, ver abajo | servidor Matrix local |")
o.append("| OpenJDK (runtime de Java) | el del JDK que empaqueta | GPL-2.0 con excepción de classpath | ejecutar la app |\n")
o.append("Los avisos del runtime de Java van en la carpeta `legal` del propio runtime. Un paquete generado sin `TUWUNEL_BIN` "
         "no lleva tuwunel ni libolm.\n")
o.append("## No incluidos: se descargan al usarlos\n\n| Componente | Licencia |\n|---|---|\n"
         "| Puentes de mautrix (WhatsApp, Telegram, Signal, Meta, Discord, Slack, Google Messages, X, LinkedIn) | AGPL-3.0 |\n"
         "| Modelos de voz de Whisper (ggml) | MIT |\n")
o.append("## Notas\n\n"
         "- **LGPL en JavaFX**: WebKit y GStreamer/GLib van como bibliotecas nativas aparte dentro de JavaFX. Su código "
         "fuente está en <https://github.com/openjdk/jfx>.\n"
         "- **MPL-2.0**: el crate `as_variant` de tuwunel. Su código fuente está en <https://github.com/matrix-construct/tuwunel> "
         "y sus dependencias.\n"
         "- **JAAD**: JCodec trae un decodificador AAC con paquete `net.sourceforge.jaad`, sin aviso propio en el código de "
         "JCodec; se lista bajo la licencia de JCodec.\n")
o.append("## Crates de Rust compilados en tuwunel\n\n| Crate | Versión | Licencia |\n|---|---|---|")
for name, version, lic, _ in rust: o.append(f"| {name} | {version} | {lic or 'ver texto abajo'} |")
o.append("\n## Textos de licencia\n")
for n, v, l, why, parts in COMPONENTS:
    o.append(f"### {n}\n")
    for head, text in parts: o.append(f"**{head}**\n\n{block(text)}")
o.append("### Apache License 2.0\n\nTexto para todo lo marcado Apache-2.0 aquí arriba y en los crates.\n\n" + block(APACHE))
o.append("### Avisos de los crates de Rust\n\nCuando un crate ofrece elegir, se usa MIT. Textos idénticos van una sola vez.\n")
for text, names in sorted(groups.items(), key=lambda kv: kv[1][0].lower()):
    o.append("**" + ", ".join(names) + "**\n\n" + block(text))
if bare:
    o.append("**Sin archivo de licencia propio; aplica la licencia declarada en la tabla:** " + ", ".join(bare) + "\n")
open(os.path.join(ROOT, "THIRD-PARTY.md"), "w").write("\n".join(o))
print(f"{len(COMPONENTS)} componentes, {len(rust)} crates, {len(groups)} textos distintos, sin archivo: {bare}")
