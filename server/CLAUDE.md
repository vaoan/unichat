# server/ (Docker) — notes for Claude

The Docker stack (Synapse + Postgres + bridges, `docker-compose.yml`, `setup.sh` / `setup.ps1`), the older alternative to the no-Docker one in `native/`. `docs/BRIDGES.md`. What the app runs is `src/main/java/app/unichat/server/` (its own `CLAUDE.md`). `CREDENCIALES.txt` and `data/` are generated and git-ignored: never read them into a commit or a message. Ports publish on 127.0.0.1 only: the homeserver must not be reachable from the café's Wi-Fi.
