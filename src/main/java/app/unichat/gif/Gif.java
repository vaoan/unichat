package app.unichat.gif;

/** One GIF result: a small preview for the grid, and the file to send (mp4 when the provider has it, else the GIF). */
public record Gif(String provider, String id, String title, String previewUrl, String mp4Url, String gifUrl, int width, int height) {}
