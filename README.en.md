# Area Miner (Minecraft 1.21.11 / Fabric)

[简体中文](README.md) | English

A **client-side only** Fabric mod: pick two blocks in turn as the corners of a cuboid region, show the region outline, then mine block by block through the client interaction manager. It adds no server-side code, runs no commands, and never modifies world blocks directly.

## Default keys

- **Z**: cycles corner 1 and corner 2. Aim at a block first, then press; the selection includes both endpoint blocks.
- **F8**: start/stop. Pressing F8 while running stops it; it also stops automatically when everything is done, on a dimension change, or when the player dies.
- Both keys can be changed in Minecraft under Options → Controls → Key Binds → "Area Miner".

The selection is drawn with a cyan outline; the block currently being processed is marked with an orange outline.

A progress line stays in the top-left corner: `区域挖掘 已拆 12 / 共 345（剩 333）`. "共" (total) is the number of mineable blocks that entered the task list at start, "已拆" (mined) is the number of blocks confirmed gone from the world (blocks re-mined after the server rolls them back count once); when everything is mined the text turns green, and it resets and hides after picking new corners.

## How it works

1. **Pick a target**: after each block it recomputes, **from nearest to farthest by distance** (measured from the player's feet). It takes the nearest mineable block: "directly hittable by the current line of sight" or "exposed on the surface" (at least one of the six neighboring directions is air/fluid/replaceable). It therefore clears the ring around your feet first and works outward, never reaching for cells buried in solid blocks. The block you are standing on gets an extra penalty to avoid stepping into a hole.
2. **Walk over**: when the target is out of interaction range, it runs A* over standable ground cells (4 directions, up 1 step, down up to 3 blocks), then holds the forward key to walk along the waypoints, jumping automatically when a step up is needed. A waypoint counts as reached only when **the player is really standing at that block's height** (`|dy| < 0.5`): neither direction is considered reached early — standing on a carpet/snow layer puts your feet 1/16 higher, and older versions misjudged the previous step, or the next landing spot, as "reached", so they did not jump and did not walk down, staying put. Being stuck for 1.5 s triggers a jump-sprint and a replan; if two consecutive attempts make no progress it skips that block and shows an action-bar hint. While walking it temporarily takes over the forward/jump keys and restores them on stop or arrival.
3. **Mine**: it turns the crosshair toward the block's **actual hit point**, then uses `attackBlock` / `updateBlockBreakingProgress` through the normal left-click mining flow, waiting for the ray to really hit that block. A block the client predicts as broken **is not removed from the task list immediately**: it watches for about 4 seconds, and if the server brings the block back (lag, protection, prediction corrected) it re-queues and mines it again; after the whole selection is done it also re-checks the selection to pick up missed blocks and continue, instead of reporting "done" right away.
4. **Tools**: **it re-selects a tool for every block**, choosing the hotbar item with the "fastest vanilla mining speed" for that block (calling `PlayerEntity.getBlockBreakingSpeed` directly, so tool tier, Efficiency, and Haste match manual switching); tools about to break are skipped. Blocks with hardness ≤ 0.15 (carpet, snow layer, flowers, torches, etc.) break in a few hits anyway, so it switches to **an empty hand or a non-tool item**: this neither wastes durability nor leaves you "holding shears/a hoe for carpet and slowly hitting the next stone with it". Because it still uses normal interaction calls, Fabric's `AttackBlockCallback` fires as usual.

A single block with no breaking progress for 3 seconds (e.g. land protection, insufficient tool) is skipped and the next one is processed instead of stalling the whole task; it stops with a message only when all remaining blocks are unreachable. If the player neither moves nor breaks a block for more than 3 seconds, the action bar shows `区域挖掘等待中：目标 …，剩余 … 格` to help identify which block it is stuck on.

## Block shapes

Hit detection uses the block's **outline shape** (not the full 1×1×1 cube) and casts rays through sample points inside the shape, so:

- non-full blocks such as carpets, snow layers, slabs, buttons, and flower pots can be selected and mined correctly;
- the standability check allows the feet to overlap thin blocks up to 0.6 high, such as carpets and snow layers, so you can walk normally on carpeted ground.

## Movement and safety limits

- Maximum selection volume: 32,768 blocks; before starting it checks that all chunks covering the selection are loaded.
- Pathfinding limit: 6000 nodes, about 25 ms per run; a waypoint with no progress for 50 ticks triggers a replan, and 3 consecutive failures skip that block.
- Never mines air, fluids, or blocks with negative hardness.
- Never mines blocks outside the selection to open a path, never bridges, swims, climbs walls, or drops more than 2 blocks; if no route exists it stops instead of detouring.
- Travels long distances along ground paths; flying, boats/minecarts, and other transport are not supported.
- Automatic movement temporarily occupies the forward/jump keys; do not leave the game or enter dangerous terrain while it is moving.
- Multiplayer servers may forbid automation; check the server rules first. A client mod does not bypass server permissions, land protection, or game-mode restrictions.

## Building

Requires JDK 21 or newer, Gradle 9.0 or newer, and access to Fabric Maven and Mojang's download sites:

```powershell
gradle build
```

The output is in `build/libs/`. Put the remapped jar into the client instance's `mods` folder and install the Fabric Loader and Fabric API for the same Minecraft version. The project pins Minecraft 1.21.11, Yarn build 6, Fabric Loader 0.20.1, and Fabric API 0.141.6+1.21.11.

## Release

Pushing a `v*` tag triggers a GitHub Actions build; only after a successful build is a GitHub release created with the remapped mod jar attached. The workflow uses JDK 21 and Gradle 9.0.

**Release notes come from the matching version section in `CHANGELOG.md`** (a heading such as `## 1.1.3`), so write what changed for that version in `CHANGELOG.md` before releasing; when the section is missing it falls back to a single hint pointing at `CHANGELOG.md`.

## Acknowledgements

Pathfinding and target selection were informed by the design ideas of client-side auto-pathfinders such as Baritone; rendering and input APIs follow Fabric's official API. The implementation does not copy source code from Baritone, Litematica, or VeinMiner.

## License

This project is licensed under the **MIT License**; see [LICENSE](LICENSE) for the full terms.

You may freely use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, provided that the copyright notice and this permission notice are retained in copies or substantial portions of the Software.

```
Copyright (c) 2026 CNGM222
https://github.com/CNGM222/fabric-area-miner
```
