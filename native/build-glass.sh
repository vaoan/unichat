#!/bin/sh
# Builds JavaFX's macOS libglass.dylib from the pinned OpenJFX tag with Unichat's patches (every file in
# native/patches/javafx/): Glass never released the layer and the view it added to a window (hundreds of MB of
# IOSurface kept per closed window). The sources come from git's objects at the pinned commit, never from a working tree.
# A drop-in for the libglass.dylib in org.openjfx:javafx-graphics:$JFX_VERSION:mac-aarch64 (same sources, flags and
# exported symbols as OpenJFX's buildSrc/mac.gradle); NativeLibLoader loads it first when it sits next to unichat.jar.
# Usage: build-glass.sh <out dylib> [jfx checkout at the tag]   Env: ARCH (default: this Mac's), JAVA_HOME (JDK 25+).
set -e
OUT="$1" SRC="$2"
[ -n "$OUT" ] || { echo "usage: $0 <out dylib> [jfx checkout]" >&2; exit 1; }
JFX_VERSION=27.0.1 # GlassPatchTest fails the build while pom.xml's javafx.version differs: re-check the patch, then bump
JFX_TAG=27.0.1-ga
JFX_COMMIT=11cb6cba8ffaf3e2b16e6adbb3abdda133da66a0
BASE_SHA256=625bb1c31b4f30413064b66b15b2b311d57461d71eb9edab651bd5bf68f91ea0
GRAPHICS_SHA256=8faed7492269c5d0326b0bcb480fda2f1a008885da3deca7dea14ef094e3cf15
MACOS_MIN=11.0 # OpenJFX's own (mac.gradle), below the app's MACOS_MIN
ARCH="${ARCH:-$(uname -m)}"
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 25)}"
HERE="$(cd "$(dirname "$0")" && pwd)"
CACHE="$HOME/Library/Caches/unichat-build/javafx"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$CACHE"

fetch() { # artifact sha256 -> path of the verified jar
  jar="$CACHE/$1-$JFX_VERSION-mac-aarch64.jar"
  m2="$HOME/.m2/repository/org/openjfx/$1/$JFX_VERSION/$1-$JFX_VERSION-mac-aarch64.jar"
  [ -f "$jar" ] || { [ -f "$m2" ] && cp "$m2" "$jar"; } ||
    curl -fsSL -o "$jar" "https://repo1.maven.org/maven2/org/openjfx/$1/$JFX_VERSION/$1-$JFX_VERSION-mac-aarch64.jar"
  echo "$2  $jar" | shasum -a 256 -c - >/dev/null || { rm -f "$jar"; echo "$1: checksum mismatch" >&2; exit 1; }
  echo "$jar"
}

# Only the parts needed: Glass's Objective-C and the Java classes its JNI headers come from.
GLASS=modules/javafx.graphics/src/main
if [ -z "$SRC" ]; then
  SRC="$CACHE/jfx27u-$JFX_TAG" # cloned once; the patches are applied to a copy in $WORK
  if [ "$(git -C "$SRC" rev-parse HEAD 2>/dev/null)" != "$JFX_COMMIT" ]; then
    rm -rf "$SRC" "$SRC.part"
    git clone --quiet --depth 1 --branch "$JFX_TAG" --filter=blob:none --sparse https://github.com/openjdk/jfx27u "$SRC.part"
    git -C "$SRC.part" sparse-checkout set "$GLASS/native-glass/mac" "$GLASS/java/com/sun/glass"
    mv "$SRC.part" "$SRC"
  fi
fi
[ "$(git -C "$SRC" rev-parse HEAD)" = "$JFX_COMMIT" ] || { echo "$SRC is not $JFX_TAG ($JFX_COMMIT)" >&2; exit 1; }
# Without module-info.java: javac would compile the module and want all of javafx.base's sources.
# From the commit's objects (git archive), so an edited or extra file in the checkout is never compiled.
mkdir -p "$WORK/src"
git -C "$SRC" archive "$JFX_COMMIT" -- "$GLASS/native-glass/mac" "$GLASS/java/com/sun/glass" | tar -x -C "$WORK/src"
# patch(1), not git apply: git apply skips paths silently when the work dir sits inside another repository.
for p in "$HERE"/patches/javafx/*.patch; do (cd "$WORK/src" && patch -p1 --quiet --forward --no-backup-if-mismatch < "$p"); done
# Each hunk landed: every line a patch adds is in the file it names.
for p in "$HERE"/patches/javafx/*.patch; do
  while IFS= read -r line; do
    case "$line" in
      "+++ b/"*) file="$WORK/src/${line#+++ b/}" ;;
      "+++"*) ;;
      "+"*) grep -qF -- "${line#+}" "$file" || { echo "$(basename "$p"): not applied: ${line#+}" >&2; exit 1; } ;;
    esac
  done < "$p"
done

# JNI headers, as mac.gradle's javahInclude: com/sun/glass/events/**, com/sun/glass/ui/*, com/sun/glass/ui/mac/*.
J="$WORK/src/$GLASS/java"
BASE_JAR="$(fetch javafx-base "$BASE_SHA256")"
GRAPHICS_JAR="$(fetch javafx-graphics "$GRAPHICS_SHA256")"
CP="$BASE_JAR:$GRAPHICS_JAR"
find "$J/com/sun/glass/events" "$J/com/sun/glass/ui" -maxdepth 1 -name '*.java' > "$WORK/javah.txt"
find "$J/com/sun/glass/events" -mindepth 2 -name '*.java' >> "$WORK/javah.txt"
find "$J/com/sun/glass/ui/mac" -name '*.java' >> "$WORK/javah.txt"
"$JAVA_HOME/bin/javac" -nowarn -XDsuppressNotes -proc:none -implicit:none -cp "$CP" -sourcepath "$J" \
  -d "$WORK/classes" -h "$WORK/headers" @"$WORK/javah.txt" 2>"$WORK/javac.log" || { cat "$WORK/javac.log" >&2; exit 1; }

# Compile and link as mac.gradle does (MAC.glass: ccFlags + -DGL_SILENCE_DEPRECATION ... -Werror; linkFlags).
SDK="$(xcrun --sdk macosx --show-sdk-path)"
N="$WORK/src/$GLASS/native-glass/mac"
# Flags as positional parameters, so a path with spaces (JAVA_HOME, the SDK) stays one word.
set -- -mmacosx-version-min=$MACOS_MIN -isysroot "$SDK" "-iframework$SDK/System/Library/Frameworks" -arch "$ARCH"
set -- "$@" -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin" -std=c99 -c -O3 -DNDEBUG -DGL_SILENCE_DEPRECATION \
  -DMACOS_MIN_VERSION_MAJOR=${MACOS_MIN%.*} -DMACOS_MIN_VERSION_MINOR=${MACOS_MIN#*.} -Werror -ffile-prefix-map="$WORK/src/=" \
  -I"$WORK/headers" -I"$N" -I"$N/a11y"
mkdir -p "$WORK/obj"
pids=""
for f in "$N"/*.m "$N"/a11y/*.m; do
  clang "$@" -o "$WORK/obj/$(basename "$f" .m).o" "$f" &
  pids="$pids $!"
done
for pid in $pids; do wait "$pid" || { echo "compiling Glass failed" >&2; exit 1; }; done
mkdir -p "$(dirname "$OUT")"
# Linked and signed in $WORK, moved to $OUT last: a failed step leaves nothing behind.
OUT_TMP="$WORK/libglass.dylib"
set -- -mmacosx-version-min=$MACOS_MIN -isysroot "$SDK" "-iframework$SDK/System/Library/Frameworks" -arch "$ARCH"
clang++ "$@" -framework AppKit -framework ApplicationServices -framework Carbon -framework OpenGL \
  -framework QuartzCore -framework Security -framework Network -framework Metal -dynamiclib -lobjc \
  -install_name @rpath/libglass.dylib -o "$OUT_TMP" "$WORK"/obj/*.o
strip -x "$OUT_TMP" # as OpenJFX's bundles do (MAC.stripArgs)
codesign --force -s - "$OUT_TMP"
mv "$OUT_TMP" "$OUT"
echo "$OUT"
