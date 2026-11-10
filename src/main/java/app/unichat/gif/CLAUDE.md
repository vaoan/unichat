# gif — notes for Claude

GIF search (`GifSearch` over `GifProviders`: `GiphyProvider`, `KlipyProvider`, each with the person's own key) and frame reading (`GifFrames`). Keys are `gif.<provider>.key`, kept on this machine (`Settings.staysOnThisMachine`). What a provider returns is a stranger's address: downloads (`GifFiles`) must be https only and size-limited (not yet: the request timeout covers only the headers).
