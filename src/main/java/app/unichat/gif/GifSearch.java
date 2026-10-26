package app.unichat.gif;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Searches every active provider at once and interleaves their results (one of each in turn). A provider that fails
 * or takes more than 5 s is left out; the others still show.
 */
public final class GifSearch {
    private static final long TIMEOUT_MS = 5000;
    private final List<GifProvider> providers;

    public GifSearch(List<GifProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    public boolean isEmpty() {
        return providers.isEmpty();
    }

    public List<Gif> search(String query, int limit) {
        return gather(p -> p.search(query, limit), limit);
    }

    public List<Gif> trending(int limit) {
        return gather(p -> p.trending(limit), limit);
    }

    @FunctionalInterface
    private interface Call {
        List<Gif> run(GifProvider p) throws Exception;
    }

    private List<Gif> gather(Call call, int limit) {
        List<CompletableFuture<List<Gif>>> futures = new ArrayList<>();
        for (GifProvider p : providers) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return call.run(p);
                } catch (Exception e) {
                    return List.<Gif>of();
                }
            }).completeOnTimeout(List.of(), TIMEOUT_MS, TimeUnit.MILLISECONDS));
        }
        List<List<Gif>> lists = futures.stream().map(CompletableFuture::join).toList();
        List<Gif> out = new ArrayList<>();
        for (int i = 0; out.size() < limit; i++) {
            boolean any = false;
            for (List<Gif> l : lists) {
                if (i < l.size() && out.size() < limit) {
                    out.add(l.get(i));
                    any = true;
                }
            }
            if (!any) break;
        }
        return out;
    }
}
