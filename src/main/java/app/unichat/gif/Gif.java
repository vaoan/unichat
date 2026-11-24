package app.unichat.gif;

import org.jspecify.annotations.Nullable;

/**
 * One GIF result: a small preview for the grid, and the file to send (mp4 when the provider has it, else the GIF;
 * {@code mp4Url} null when it hasn't).
 */
public record Gif(String provider, String id, String title, String previewUrl, @Nullable String mp4Url, String gifUrl, int width,
                  int height) {}
