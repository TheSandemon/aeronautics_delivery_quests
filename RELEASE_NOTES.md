## v1.1.4 — Village and structure quest generation restoration

- Fixed VILLAGE and ANY_STRUCTURE routes finding structures but failing before a quest could be created.
- Structure endpoint chunks now remain loaded until both landing surfaces have been validated.
- Landing validation reads the completed chunks returned by NeoForge instead of an expired level-cache entry.
- When a structure's locator coordinate is inside a building or on uneven terrain, ADQ now selects the nearest safe dry landing within that structure chunk.
- Retains the v1.1.2 server crash fix and all v1.1.3 RANDOM/Sky Island generation fixes.
