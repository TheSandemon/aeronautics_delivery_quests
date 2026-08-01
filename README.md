# 🗺️ TNM Aeronautics Quests
**A high-stakes trade expansion for Create: Aeronautics & Sable**

**TNM Aeronautics Quests** (formerly *Aeronautics Delivery Quests*) is a lightweight, high-performance NeoForge 1.21.1 mod that adds procedural hauling contracts to your server. Players can accept quests, travel to remote locations to secure physical cargo, and pilot their custom-built airships to transport these heavy rigid bodies to destination settlements for massive rewards.

> [!NOTE]
> The mod ID remains `aeronautics_delivery_quests` for full world-save, config, and registry compatibility with earlier releases. Existing worlds, configs, and quest data carry over untouched.

**TNM Aeronautics Quests must be installed on both the client and server** (or just the client for singleplayer) to handle custom UI rendering, block assets, and packet synchronization.

---

## 🚀 Key Features

*   **📦 Dynamic & Custom Quests:** Accessible to players via the **Delivery Quests Table** block (and `/adq` for administrators), players browse available contracts in **Light**, **Medium**, and **Heavy** weight classes with dynamically calculated routes and tailored rewards. Creators can design custom quest entries in `custom_quests.json` using custom NBT templates!
*   **⚖️ Sable Physics Integration:** Dynamically loads schematic templates (such as crates, pallets, and secure containers) and compiles them into **Sable dynamic physics rigid bodies** when secured.
*   **🧭 Virtual Calibration Compasses:** Issues vanilla Quest Compasses that point dynamically to cargo pickups or destination locations. Lost your compass? Retrieve a replacement instantly using the board's GUI control panel inside the table's menu.
*   **🔒 Damage Penalties & Shielding:** Configurable invulnerability (`enableCargoInvulnerability`). When off (default), cargo blocks can break, and any missing mass proportionally reduces your reward payout! When on, cargo is fully shielded from breakages, explosions, and mob griefing. In both modes, cargo blocks **never drop their items** — no free loot from delivery contracts.
*   **🧩 Split Tracking & Debris Cleanup:** If a cargo contraption is fractured into multiple physics bodies, the quest keeps targeting the main body (blocks lost to split-off pieces count as missing mass), detached fragments inherit full block protection, and all fragments are cleaned up together with the cargo when the quest completes, fails, or is cancelled.
*   **👻 Seamless Cargo Spawning:** Physical cargo is pre-spawned while the approaching pilot is still outside render distance (`cargoSpawnDistance`, default 250 blocks), so it is already sitting there when they arrive — no visible pop-in. Securing still happens at the pickup point.
*   **⏳ Board Expirations & Player Cooldowns:** Unaccepted contracts on the board cycle out after 60 minutes. A 1-hour player cooldown prevents quest-spamming (automatically bypassed for admins/operators).

---

## Sky-Island Worlds

For sparse floating-island worlds, set these values in `config/aeronautics_delivery_quests.toml`:

```toml
questLocationMode = "RANDOM"
skyIslandMode = true
randomSearchAttempts = 96
```

Sky-island searches keep one candidate chunk in flight and stop when both dry endpoints are found. Increase `randomSearchAttempts` only for exceptionally sparse worlds. Every location mode rejects water, waterlogged ground, void columns, leaves, and excessively uneven landing footprints.

---

## 🔍 For the Skeptics (FAQ)

### 💻 Does this require client-side installs?
**Yes.** Because the mod introduces the physical **Delivery Quests Table** block, custom clipboard menus, client-side screen UI rendering, and network synchronization packets, it must be installed on both the server and the client (or just the client for singleplayer).

### 📉 Will this cause tick lag or TPS spikes during generation?
Route discovery runs asynchronously, while chunk requests and terrain validation are paced through the server tick loop. RANDOM searches keep one candidate chunk in flight, and structure routes retain only their two endpoint chunks while validation completes. This avoids blocking chunk-generation loops and keeps generation work bounded.

### 🛡️ Will this block players from modifying their own airships?
**No.** The block protection is highly targeted: it only intercepts destruction inside the exact physics-body bounds of an *active delivery quest's* cargo (including any pieces that break off it) and its Overworld pickup footprint. Invulnerability shielding only applies when `enableCargoInvulnerability` is enabled in the config. Normal airships, blocks, and structures remain 100% destructible. One rule always applies regardless of the setting: **cargo blocks never drop their items**, so contracts can't be mined for free loot.

### 🌀 What happens if cargo spawns outside the world border, underground, underwater, or inside buildings?
*   **Out-of-Sight Pre-Spawning**: Cargo materializes while the approaching pilot is still beyond render distance (`cargoSpawnDistance`, default 250 blocks), so nobody ever sees it pop in.
*   **Safe Air-Column Spawning**: The mod scans a horizontal $33\times33$ area around the start coordinates to find a solid, flat footprint. It checks that the entire spawn space (and the air blocks below it) is 100% empty air, spawning the cargo 3 blocks in the air so it falls cleanly under gravity without clipping or breaking blocks.
*   **Dry-Land Validation**: Water, waterlogged blocks, leaves, void columns, and unsafe footprints are rejected. If the selected coordinate is unsuitable, structure routes search the completed endpoint chunk for the nearest safe landing; RANDOM routes continue to another candidate.
*   **No Unsafe Fallback**: If cargo placement cannot find a dry, clear footprint near the pickup, it fails cleanly instead of placing cargo underwater, over the void, or inside terrain.
*   **Border Buffer**: The engine enforces a strict **150-block safety buffer** from your world border. If a potential spawn is too close to or outside the border, it is aborted instantly.

### 💾 Does it survive server restarts?
**Yes.** All active quests, accepted states, cargo positions, and player cooldowns are persisted in GSON JSON registries (`adq_quests.json` and `adq_cooldowns.json`) inside the server's world save folder. Stale compasses are also automatically purged on player login to handle offline quest expirations.

---

## 🛠️ Command Reference

> [!NOTE]
> All `/adq` commands require operator permissions (Level 2+). Regular players must use the physical **Delivery Quests Table** block to open the board, cancel contracts, or reissue compasses.

*   `/adq` - `[Admin]` Open the virtual Quest Board & command control panel.
*   `/adq cancel` - `[Admin]` Abandon your current contract (recalls physical cargo and clears compasses).
*   `/adq compass` - `[Admin]` Re-issue your Quest Delivery Compass (only if on an active quest and you don't already have one).
*   `/adq generate` - `[Admin]` Force-triggers a background procedural quest generation.
*   `/adq complete` - `[Admin]` Force-completes your active delivery contract.
*   `/adq delete <index>` - `[Admin]` Cleanly force-deletes a specific board quest.
*   `/adq deleteall` - `[Admin]` Purge all quests from the board.
*   `/adq reload` - `[Admin]` Reload quests, player cooldowns, and custom quest templates from disk.
