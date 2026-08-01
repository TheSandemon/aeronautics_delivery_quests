### TNM Aeronautics Quests - v1.1.4 Changelog

#### Village and Structure Generation Restoration
- Fixed VILLAGE and ANY_STRUCTURE searches finding route candidates but rejecting them during delayed surface validation.
- Retain both endpoint chunks with short-lived ADQ region tickets until validation completes.
- Validate surfaces against the completed `ChunkAccess` results returned by NeoForge.
- If a structure locator points inside a building or onto uneven ground, select the nearest safe dry landing within that structure chunk.
- Reproduced and verified automatic VILLAGE quest generation on a dedicated NeoForge server.

---

### TNM Aeronautics Quests - v1.1.3 Changelog

#### Quest Generation Restoration
- Fixed RANDOM candidate futures completing after their temporary vanilla chunk ticket expired.
- Added a short-lived ADQ region ticket around each candidate and release it immediately after terrain validation.
- Validate terrain from NeoForge's returned `ChunkAccess` rather than relying on the level's visible chunk cache.
- Removed the invalid five-block minimum-height cutoff that rejected legitimate low-altitude terrain.
- Reproduced and verified automatic quest generation on a dedicated NeoForge server.

---

### TNM Aeronautics Quests - v1.1.2 Changelog

#### Server Stability
- Fixed a `ConcurrentModificationException` caused by recursively requesting RANDOM candidate chunks while Minecraft updated its chunk-distance map.
- Moved RANDOM chunk-generation requests off the server thread.
- Added a server tick-post work queue that processes one search action per tick.
- Added server lifecycle cleanup so stale search work cannot survive a stop or restart.
- Added regression tests covering queue pacing and cleanup.

---

### TNM Aeronautics Quests - v1.1.1 Changelog

#### Sky-Island Route Generation
- Added `skyIslandMode` for RANDOM quests in sparse floating-island worlds.
- Candidate chunks follow a golden-angle spiral instead of clustering randomly.
- Searches stop at the first valid endpoint, keep one chunk request in flight, and use the configurable `randomSearchAttempts` ceiling.

#### Dry-Land Safety
- RANDOM, VILLAGE, ANY_STRUCTURE, and custom-coordinate endpoints reject water and waterlogged surfaces.
- RANDOM validates each loaded candidate during the search instead of aborting after selecting an invalid route.
- Cargo placement rejects missing ground, seabeds, void gaps, leaves, and excessively uneven footprints.

---

### TNM Aeronautics Quests - v1.1.0 Changelog

#### ✈️ TNM Rebranding
- **New Name**: *Aeronautics Delivery Quests (ADQ)* is now **TNM Aeronautics Quests**! The mod name, description, and in-game chat prefix (`[ADQ]` → `[TNM Quests]`) have all been updated.
- **Your Worlds Are Safe**: This is purely a rename — the internal mod ID stays `aeronautics_delivery_quests`, so existing worlds, placed Delivery Quests Tables, configs (`aeronautics_delivery_quests.toml`, `custom_quests.json`), saved quests, and cooldowns all carry over untouched. The `/adq` command works exactly as before. Just drop in the new jar.

#### 🔒 Cargo Protection Overhaul
- **Cargo Invulnerability Fixed**: The `enableCargoInvulnerability` option previously had no effect — cargo could always be broken. It now genuinely protects cargo everywhere it exists: on the physics contraption *and* at the pickup site, against player mining, block placement, explosions, and mob griefing. Protection coverage also now matches the full size of the cargo instead of a small fixed area.
- **No More Free Loot**: Cargo blocks **never drop items** when destroyed — with invulnerability on *or* off. You can no longer mine iron blocks (or anything else) out of a delivery crate. In breakable mode, destroyed blocks still reduce the delivery payout as damage; they just yield nothing.
- **Split Cargo Fully Recalled**: If a cargo contraption gets fractured into multiple physics pieces, all detached pieces are now tracked as part of the quest. They get the same block protection, and every piece is recalled together when the contract is completed, cancelled, or failed — no more orphaned debris floating around your world. The compass and delivery check keep targeting the main body, and blocks lost to broken-off pieces count as missing mass toward the damage penalty.

#### 📦 Smarter Cargo Spawning
- **No More Pop-In**: Cargo now spawns while the approaching pilot is still far outside render distance (new `cargoSpawnDistance` config, default 250 blocks), so it's already sitting on the ground by the time you can see the pickup site. Securing the cargo, the compass switch, and server announcements still happen when you reach the pickup point, same as before.
- **Nearby Contracts for the Requester**: Using `/adq generate` or the board's Generate/Fill buttons now searches for pickup locations around *you*, instead of around a random online player — no more contracts spawning tens of thousands of blocks away on servers with spread-out players. Automatic background generation still spreads quests across the whole community.
- **No More Underwater Cargo**: Cargo destined for ocean areas previously could spawn on the seabed, dozens of blocks underwater. It now lands on top of the water surface instead.
- **No More Void Drops**: On floating-island and void world types, quest generation now detects when there's no ground at all and cleanly retries elsewhere, instead of dropping cargo into the abyss.

#### 🛠️ Under the Hood
- Updated the build to the latest NeoForge for 1.21.1 (21.1.236); servers on NeoForge 21.1.65 or newer remain fully supported.
- Modernized mod metadata to NeoForge's current dependency format and cleaned up deprecated API usage for a warning-free build.
- Updated the release/publishing toolchain (mod-publish-plugin 2.1.1) and made the project build cleanly on any machine.

---

### Aeronautics Delivery Quests (ADQ) - v1.0.3 Changelog

#### GUI Polish & Interface Polish
- **Ledger Cooldown Rename**: Changed the clipboard label "Cooldown" to "Next Quest In" to make cooldown status clearer.
- **Route & Mass Line Splitting**: Formatted the quest card text so that Mass and Route are on separate lines per quest card, removing the vertical pipe (`|`) character.
- **Flight Manual Simplification**: Simplified the Flight Manual screen to show a unified description help section instead of the multi-step gameplay rules.

#### Predefined Coordinate Support (custom_quests.json)
- **pickupPos and dropoffPos Parameter**: Added optional `pickupPos` and `dropoffPos` string parameters (blank by default on all default quests).
- **Coordinate Formats**: Supports `x,y,z` or `x,z` formats (queries the surface heightmap when Y is omitted or 0). If valid coordinates are specified, quest generation skips the async structure search and generates the quest at the exact location.

#### Aeronautics Recipe Integration & Bug Fixes
- **Table Crafting Recipe**: Updated the crafting recipe for the delivery quests table to require a Contraption Diagram (`simulated:contraption_diagram`) at the top, a Compass (`minecraft:compass`) in the middle, and any wood slab (`#minecraft:wooden_slabs` tag) at the bottom.
- **Occlusion Fix**: Added `.noOcclusion()` properties to the delivery quests table block, fixing the see-through ground bug beneath it.

---

### Aeronautics Delivery Quests (ADQ) - v1.0.2 Changelog

#### Quest & Location Generation Configuration Toggles
- **Generation Mode Toggle**: Replaced the sliding scale `customQuestsChance` with an enum-based `questGenerationMode` config toggle (`CUSTOM` or `PROCEDURAL`).
  - *CUSTOM*: Spawns structured quests exactly as defined in `custom_quests.json`.
  - *PROCEDURAL*: Dynamically mixes names, descriptions, mass classes, schematics, and rewards from across the pool of valid templates in `custom_quests.json`.
- **Location Mode Selector**: Replaced `randomQuestGen` with an enum-based `questLocationMode` config toggle (`VILLAGE`, `ANY_STRUCTURE`, or `RANDOM`).
  - *ANY_STRUCTURE*: Performs an Overworld-wide search using the registries to locate any valid registered structure (top level only) for cargo pickup and delivery.

#### Expanded Default Assets & Balanced Economy
- **6 Default Cargo Schematics**: Expanded the programmatically generated cargo NBT schematics list to 6, adding `light_food_crate`, `medium_ore_crate`, and `heavy_industrial_boiler`.
- **6 Default Packaged Quests**: Expanded example templates to 6 default quests in `custom_quests.json`.
- **Balanced Emerald Economy**: Reduced default emerald payouts for all 6 example quests to a balanced range of 10–50 emeralds. Scaled default config payouts for procedural generation to 15 (Light), 30 (Medium), and 50 (Heavy).

#### Command Cooldowns & Concurrency Safety
- **5-Second Command Cooldown**: Enforces a 5-second cooldown on all player `/adq` command executions.
- **Quest Generation Lock**: Implemented an execution lock (`AtomicBoolean`) to prevent starting quest generation twice concurrently, with dynamic play button greying out on the client UI.

#### Config Auto-Healing
- **Auto-Healing Configuration**: If an outdated version of `custom_quests.json` is detected (with old legacy emerald payouts of `300` or `640`), it is automatically upgraded and rewritten with the balanced 1.0.2 rewards on server startup.

#### Quest Board Clipboard UI Polish
- **Multi-Reward Display**: Enhanced the Quest Board clipboard to display all reward items. Details are parsed into a clean comma-separated list and wrapped dynamically to fit within ledger slots and the active quest details view.
- **Ledger Spacing Refactor**: Reduced quest items displayed per page from 3 to 2, scaling card heights to 68px (originally 48px) to cleanly prevent reward text from spilling over.
- **Dynamic State Updates**: Accept buttons refresh active states dynamically when a player's quest cooldown ticks down to zero.

#### C2ME Concurrency & Thread Safety
- **Asynchronous Random Safety**: Replaced thread-bound `level.getRandom()` with isolated `RandomSource.create()` inside the background quest generation thread. This completely resolves threading crashes and `ConcurrentModificationException` conflicts when running alongside the **C2ME (Concurrent Chunk Management Engine)** mod.

---

### Aeronautics Delivery Quests (ADQ) - v1.0.1 Changelog

#### Core Mechanics & Standalone Architecture
- **Standalone Mod**: Stripped all FTB Chunks, Teams, and Library requirements from compilation, config, and runtime scripts, making ADQ 100% independent.
- **Safe Air Spawning**: Re-engineered physical spawning to check solid surface footprints and ensure 100% empty air columns. Cargo spawns 3 blocks in the air and drops cleanly under gravity.
- **Vertical Spawning Safety Fallback**: Added a sky fallback (spawns cargo 4 blocks above solid ground) if flat ground is unavailable, preventing clipping into village structures/ground. Removed legacy floating grass block placements.

#### Custom Quest JSON Configurations
- **Admins JSON Pool (`custom_quests.json`)**: Added a dedicated GSON database at `config/aeronautics_delivery_quests/custom_quests.json` (auto-generates example templates on startup) allowing custom names, descriptions, mass classes, NBT templates, and rewards.
- **Mix Ratio Tuning**: Added `customQuestsChance` (default 0.5) double config parameter under `ADQConfig` to set the percentage ratio of custom JSON quests versus procedural generation.
- **Instant Hot-Reloads**: Integrated JSON reloader directly into the `/adq reload` command and chest-GUI reload button for real-time refreshes without a server restart.

#### Exploit Protection & Chest-GUI Command Panel
- **Anti-Loot Balance**: Swapped `minecraft:netherite_block` in the Heavy schematic with `minecraft:polished_deepslate` and programmed the manager to overwrite legacy/OP config files on startup. Guaranteed all default schematics use exactly `simulated:rope_connector`.
- **Chest-GUI Redesign**: Restructured the board to limit quest maps to slots 0–17 (respecting `maxActiveQuestsPerBoard`). Converted the bottom row (slots 18–25) into a command dashboard for Reissuing Compass, Cancelling Contract, and OP-level 2 Admin Commands (Generate, Delete All, Reload).
