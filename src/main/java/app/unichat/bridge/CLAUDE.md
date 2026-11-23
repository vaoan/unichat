# bridge — notes for Claude

What `matrix` and `server` share about the bridges, so neither depends on the other: `BridgeStates` (the account states bridges POST to `server/StatusEndpoint`) `BridgePorts` (each bridge's port, for direct media) and `LoginsCache` (the logins `matrix` keeps in `matrix.logins.<protocolId>`, which `server` reads to know which bridges have an account).
