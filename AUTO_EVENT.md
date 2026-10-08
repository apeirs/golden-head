# Auto Event - design and status

Goal: one module (`auto-event`, Pit category) that detects the running Pit event and plays it to win.
Combat events = sprint + hit at a random interval under 100 ms (AutoFight, default 40-95 ms).

## Building blocks
- `utils/Mover` - sprint toward the goal, hop a one-block step, strafe around a wall, walk off a drop.
- `modules/ClickTarget` - find closest target block/entity, get there, spam-click (Dragon Egg, Care Package).
- `modules/AutoFight` - combat: real players only (tab-listed, no v2-UUID NPCs), friends/TDM teammates skipped,
  beasts first, unhittable targets (no hurtTime after 12 hits) skipped 5 s. `extraFilter` / `holdZone` hooks.
- Existing: QuickMaths, DragonEgg, CarePackage, TeamDeathmatch, Beast.

## Events (Hypixel Pit; server is a clone, verify in-game)
Minor: Auction, Care Package, Everyone Gets a Bounty, 2x Rewards, King of the Ladder, King of the Hill,
Giant Cake, Dragon Egg, Quick Maths.
Major: Spire, Squads, Blockhead, Raffle, Robbery, Pizza, Rage Pit, Team Deathmatch, Beast.

## Detection (utils/EventTracker)
Chat: "MAJOR EVENT! SPIRE in 1 min" (pending), "MINOR EVENT! X starting now / for N min / dropping now" (start),
"PIT EVENT ENDED: X" / "X OVER" (end); boss bar with an event name = running. Unit-tested on these lines.

## Per-event plan (modules/AutoEvent)
| Event | Play |
|---|---|
| Dragon Egg / Care Package / Giant Cake | DragonEgg / CarePackage / GiantCake (ClickTarget) |
| Quick Maths | QuickMaths (always on while Auto Event is) |
| KOTH | largest diamond_block cluster (fresh blocks first) -> AutoFight holdZone on top, targets near it |
| KOTL | green/lime terracotta cluster -> hold top tier |
| TDM / Beast | AutoFight + TeamDeathmatch / Beast |
| Rage Pit, Blockhead, 2x, Everyone Bounty | AutoFight + AutoGoldenHead |
| Robbery | AutoFight; walk over gold_nugget drops when nobody is within 5 blocks |
| Raffle | collect name_tag drops, deposit at note block/jukebox nearest spawn XZ (hold tag, right-click) |
| Pizza | villager (outside spawn) right-click holding pizza; refill/deposit at "pizza" hologram stand |
| Squads | stand at nearest banner not our color until it flips (learn our color from first flip) |
| Spire | 70 s before: go to portal (nether/end portal/gateway nearest spawn XZ); enter; then AutoFight |
| Auction | open menu via the chat click command, print slots, close. Never bids. |

## Status
- [x] Mover, AutoFight, SpawnArea (ZAimbot off in spawn, spawn targets skipped)
- [x] EventTracker + AutoEvent handlers (compiled, mixins load in dev client)
- [ ] In-game verification per event (needs the server; use .pitinfo and chat logs)
- [ ] Auction bidding (needs a menu dump first)
