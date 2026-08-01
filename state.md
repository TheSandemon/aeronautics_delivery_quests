# Project State

## 2026-07-27 — v1.1.2 crash hotfix

- The working tree contains the prior v1.1.1 release work plus the v1.1.2 hotfix; repository `HEAD` is still tagged v1.0.3.
- Root cause: RANDOM/Sky Island chunk-future callbacks recursively requested FULL chunks on the server thread while `DistanceManager` was iterating, causing a long tick and `ConcurrentModificationException`.
- Fix: RANDOM preparation and terrain reads run on the server thread; chunk requests are initiated asynchronously; completed results pass through a `ServerTickEvent.Post` queue that processes one action per tick.
- Server start/stop resets the queue and invalidates stale callbacks through an epoch counter.
- Version metadata and release notes are set to 1.1.2.
- `packageRelease` creates identical CurseForge and Modrinth jars under `build/release`.
- Verification command: `.\gradlew.bat clean test packageRelease --no-daemon --no-parallel --console=plain`.

## 2026-07-29 — v1.1.3 generation restoration

- Reproduced the no-quest failure on a controlled dedicated server.
- Two causes were confirmed: the vanilla UNKNOWN chunk ticket expired before asynchronous FULL generation completed, and v1.1.1 rejected valid surfaces within five blocks of the dimension floor.
- RANDOM searches now hold a 600-tick ADQ region ticket, validate the returned `ChunkAccess` directly, and release the ticket after inspection.
- A flat dedicated test world generated and saved a RANDOM quest with both endpoints found on the first candidate.
- Development runtime now uses NeoForge 21.1.235 and excludes obsolete FTB jars from runtime-only dependencies.
- Release version is 1.1.3.

## 2026-07-29 — v1.1.4 village generation restoration

- Reproduced VILLAGE mode finding two village candidates but rejecting the route during surface validation and saving no quest.
- Structure routes now retain both FULL endpoint chunks with 600-tick region tickets and validate the returned `ChunkAccess` objects before releasing those tickets.
- Vanilla structure locator positions may fall inside buildings or on uneven terrain; ADQ now scans the located chunk for the nearest safe dry 3x3 landing when the exact coordinate is invalid.
- A controlled normal-world dedicated server generated and saved a VILLAGE quest from `-240, 65, -1376` to `-1439, 68, -159`.
- Release version is 1.1.4.

## 2026-08-01 — v1.1.4 upstream integration

- Integrated the seven upstream v1.1.0 TNM rebrand, cargo-protection, split-fragment tracking, requester-centered generation, pre-spawn, metadata, and publishing commits with the local v1.1.4 generation fixes.
- Preserved cargo fragment cleanup and no-drop protection while enforcing dry-land cargo footprints and releasing forced chunks during cleanup.
- README, changelog, release notes, version metadata, Modrinth project ID, tests, and dual-platform packaging are synchronized for v1.1.4.
- Verification passed: `.\gradlew.bat clean test packageRelease --no-daemon --no-parallel --console=plain`.
