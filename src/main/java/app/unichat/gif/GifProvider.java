package app.unichat.gif;

import java.io.IOException;
import java.util.List;

/**
 * A GIF search service (Giphy, Klipy…). Each one has its own API, so each gets an adapter; Ajustes → GIFs turns them
 * on with the user's key. Only the search text (and the key) is ever sent.
 */
public interface GifProvider {
    String id();

    String name();

    List<Gif> search(String query, int limit) throws IOException, InterruptedException;

    List<Gif> trending(int limit) throws IOException, InterruptedException;
}
