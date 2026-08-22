# Portal Glimpse — release checklist

Run this before tagging a release, and after every Minecraft / Fabric / Iris / Sodium bump.

Section 1 comes first on purpose: those are the failures a green Gradle build cannot catch. Everything
after it is grouped into **runs** — each run is one world setup, so the whole list is a few sittings rather
than a hundred separate launches.

Every item is written as **do this → expect that**. If the "expect" doesn't happen, it's a regression.

---

## 1. Version-bump hazards — check these FIRST

These have all bitten this project before. A clean build says nothing about any of them.

### 1a. If the MINECRAFT version changed

Anything past 1.21.1 is a **port, not a bump** — the render stack this mod is built on is replaced above
that line. Expect nothing below this subsection to be runnable until the port compiles again.

- [ ] Bump these together in `gradle.properties`: `minecraft_version`, `yarn_mappings`, `loader_version`,
      `fabric_api_version`, `cloth_config_version`, `modmenu_version`.
      *Mismatched yarn against minecraft_version fails in a way that reads like your code is wrong.*
- [ ] **Widen `"minecraft"` in `fabric.mod.json`.** It is pinned to `>=1.21 <=1.21.1`.
      *Forget this one and the jar refuses to load on the very version you just built it for.*
- [ ] Re-check the access widener against the new mappings: `RenderLayer.of` by exact descriptor, plus
      eight `RenderPhase` fields. *`RenderPhase` is the piece most likely to be gone outright.*
- [ ] The three core shader programs in `assets/portal-glimpse/shaders/core/` still compile and link.
      *Vanilla's core-shader format and uniform set move between versions. A broken one draws pink or
      black and the only evidence is a log line, not an error on screen.*
- [ ] Every Fabric API hook still exists with the same signature — `WorldRenderEvents` /
      `WorldRenderContext` (incl. `context.frustum()`), `CoreShaderRegistrationCallback`, `HudRenderCallback`,
      the `ScreenEvents` trio, `ClientChunkEvents`, `ClientSendMessageEvents`.
- [ ] All 13 mixin targets still exist and still match. *Vanilla renderer classes get refactored hardest;
      `IrisPipelineMixin` and `SodiumLevelSliceMixin` are `@Pseudo` and fail silently.*
- [ ] **Captures written by the PREVIOUS version still load** — old glimpses survive the upgrade.
      *People update with a full capture folder. Do not strand it.*

### 1b. Every release

- [ ] `build/classes/java/main/portalglimpse.mixins.refmap.json` exists and is non-empty.
      *Missing refmap = mixins silently never fire. No crash, no warning, hooks just don't run.*
- [ ] Every class listed in `portalglimpse.mixins.json` exists in the jar.
      *A stale entry is a hard crash on load, and it is NOT a compile error.*
- [ ] `validateAccessWidener` passes, and the RTT glimpse still renders.
      *It needs `RenderLayer.of`, `RenderPhase.ALL_MASK`, `DEPTH_MASK` and several more.*
- [ ] **Run the built jar in a real launcher profile**, not the dev client.
      *Dev and production differ: a class in `net.minecraft.*` works in dev and throws `IllegalAccessError` in prod.*
- [ ] Load with a shaderpack, open settings → the pack name shows, not "none detected".
      *`IrisCompat` reads `Iris.getIrisConfig()` reflectively. "None detected" means Iris moved it.*
- [ ] Sodium installed → portal blocks still hide. Iris → RTT still draws.
      *Both mixins are `@Pseudo`, so they degrade to "never fires" — which looks like a feature quietly not working.*
- [ ] The clean-up row still lines up with the sliders above it, at a wide AND a narrow window.
      *It reproduces Cloth's own layout formula; if Cloth changes it, this row drifts.*

---

## 2. RUN A — core loop, no shaders

*Fresh creative world, then survival. No Iris.*

- [ ] Light a new portal → chat says "Portal detected" (if the toggle is on).
- [ ] `/pgdebug` on, light another → the line now includes coordinates and a block count.
- [ ] Light a portal inside an already-loaded chunk → detected within ~1 second.
      *That is the proximity scan, not chunk-load. It is a separate code path.*
- [ ] Build a portal straddling a chunk border, fly away and back → detected, not half-detected.
- [ ] **Travel through it** → loading screen holds a beat longer than vanilla, then releases.
- [ ] Come back → the origin portal now shows the destination.
- [ ] The glimpse shows real terrain, not void or grey.
- [ ] Travel again immediately → no re-capture (5-minute cooldown).
- [ ] Set cooldown to 0 in settings, travel → captures every time.

## 3. RUN B — rendering detail, no shaders

*Use the portal from Run A.*

- [ ] Stand 40 blocks away → flat postcard.
- [ ] Walk toward it → postcard crossfades out, parallax panorama takes over. No pop.
- [ ] Strafe left and right up close → the view shifts with real perspective.
      *If it doesn't move, the panorama isn't rendering and you're seeing the postcard.*
- [ ] Back away past ~52 blocks → fades out cleanly, no hard cutoff.
- [ ] Look at the portal from a sharp angle → still reads as depth, not a flat sticker.
- [ ] The swirl renders **over** the glimpse, not under it.
- [ ] Only the face toward you draws — the far side doesn't shine through.
- [ ] Eight-plus captured portals in view at once → no frame-time cliff.
      *Nearest-N, frustum culling and the cached interior bbox all exist to stop this. Nothing
      else in this list would notice if they stopped working.*
- [ ] Build a **non-rectangular** portal (an L or a plus) → glimpse fills the actual shape.
- [ ] Build a **wide** portal (10+) → image covers it without stretching or distortion.
- [ ] Stand so clouds/rain are behind the portal → they're occluded, not drawn over it.
- [ ] **Walk into the portal** (with `/pgdebug`, Numpad 0 to block travel) → the destination box
      pushes ahead of you smoothly. No snap.
- [ ] While inside, **look back** → the destination fades in around you (the encasement).
- [ ] Have a second player stand in a captured portal → they read as standing in the destination.
- [ ] Same in third person (F5) on yourself.

## 4. RUN C — reshaping and data integrity

*This path DELETES records and MOVES PNG files. Test it properly.*

- [ ] Capture a portal, break it, rebuild it **bigger** → glimpse survives, whole opening.
      *No half-glimpse-half-vanilla. That was the bug.*
- [ ] Same but **smaller** → glimpse survives.
- [ ] Same but **reshaped sideways** (move it one block over) → glimpse survives.
- [ ] Build two captured portals adjacent, merge into one → one glimpse survives, no patchwork.
- [ ] **Restart the client** after each of the above → still correct.
      *The old bug only showed its teeth across a reload, because loading re-claimed both records.*
- [ ] Two **separate** portals 20 blocks apart → they do NOT steal each other's captures.
- [ ] Break a captured portal entirely, relight in the same spot → behaves sanely.

## 5. RUN D — shaders

*Repeat the important ones per pack. Roster: BSL and Photon full, Solas partial.*

- [ ] Each pack: glimpse is neither black nor blown out.
- [ ] Settings shows the right tier — green supported / gold partial / yellow not supported.
- [ ] Load an **unlisted** pack (Bliss, Complementary) → "not supported" screen appears.
- [ ] Load **Solas** → "partially supported" screen, naming the crease caveat.
- [ ] From that screen, switch packs via the Iris button → the prompt updates instead of naming the old pack.
- [ ] The swirl is **off** by default under shaders; the no-shader swirl is unaffected.
- [ ] Turn "Portal swirl under shaders" on → swirl returns.
- [ ] Sun behind you, portal ahead → god rays do **not** pierce the glimpse.
- [ ] Turn the God-Ray Occluder off → rays return. *(Confirms the cage is what's doing it.)*
- [ ] **Walk across chunk borders near a portal** → no black occluder blocks left hanging in the air.
- [ ] Switch to **OVERLAY** method, stand 40+ blocks away → the flat postcard shows.
      *Not just the swirl over an empty portal. That was the overlay postcard-floor bug.*
- [ ] Capture under shaders → no ghost/phantom blocks in the resulting cubemap.
- [ ] Drink a potion, then capture → **no effect swirl baked into the glimpse**.
- [ ] Fast head movement under RTT → the panorama tracks. Lower the prediction slider if it wobbles.

## 6. RUN E — interruption

*Survival, `/gamerule naturalRegeneration false` so health stays put.*

- [ ] Put a mob on the far side of a portal, travel → **first hit** drops the loading screen instantly.
- [ ] The respawn-anchor "deplete" sound plays.
- [ ] Take a hit that armour fully absorbs → still cancels.
      *This is why it reads `hurtTime` and not the health number.*
- [ ] Eat during the hold, then take damage → still cancels.
      *Health-based detection missed this; the baseline moved.*
- [ ] Damage **during** the capture (not just the wait) → capture is abandoned, portal keeps its old glimpse.
- [ ] Creeper 10 blocks from the arrival portal, walking at you → loading text goes
      **"Creeper?" → "Creeper is approaching!"** → screen drops, "Creeper!" flashes green/white.
- [ ] Creeper standing around not approaching → **"The Creeper is chilling around"**, hold continues.
- [ ] Turn "Creeper warning" off → no flash, but the cancel still happens.
- [ ] Let a capture time out (30s, low render distance) → captures **what loaded** rather than skipping.
- [ ] Arrive somewhere with no portal at all → aborts cleanly, no softlock.

## 7. RUN F — interface

- [ ] Pause menu shows the portal beside Advancements, swirl animating.
- [ ] Hover → white outline appears. No grey button behind it.
- [ ] Click → settings open. Escape returns to the pause menu.
- [ ] **Right-hold and drag** → it moves. Release, reopen the menu → it stayed.
- [ ] Resize the window → it keeps its position relative to Advancements.
- [ ] Restart the client → position persisted.
- [ ] Turn its config toggle off → gone next time the menu opens.
- [ ] Settings: disk usage shows a real figure, not a stuck "measuring…".
- [ ] Clean Up → lists worlds and servers, largest first, with sizes and portal counts.
- [ ] Select one → button arms and turns red. Change the selection → it **disarms**.
- [ ] Confirm → files deleted, and the total re-measures.
- [ ] Every slider takes effect **on save**, no restart: veil opacities, panorama FOV, postcard distance,
      capture radius, cooldown, RTT prediction.
- [ ] Veil preview boxes above the opacity sliders update live as you drag.
- [ ] Change render distance in vanilla video settings → capture radius follows it **down**, never back up.
- [ ] With `/pgdebug` **off**: every debug key does nothing (Numpad, H, J, K, L).

## 8. RUN G — compatibility

- [ ] Sodium, no shaders → portal blocks hide correctly, swirl animates.
- [ ] **Fabulous** graphics → glimpse still occludes properly.
- [ ] Fast / Fancy graphics.
- [ ] Multiplayer server → captures land under `portalglimpse/<server>/`, not in the world folder.
- [ ] Two clients on a LAN world → each sees glimpses; the other player reads as inside the portal.
- [ ] Join a server **without** the mod → no crash, mod simply does nothing server-side.

---

## 9. Ship

- [ ] `/pgdebug` **off** by default — it gates the debug keys, manual capture, and the MCP server.
- [ ] MCP server is NOT listening when debug is off (`curl 127.0.0.1:25585/mcp` refuses).
- [ ] `mod_version` bumped in `gradle.properties`.
- [ ] The `"minecraft"` range in `fabric.mod.json` matches the versions you actually tested —
      no wider, no narrower.
- [ ] Modrinth AI disclosure still accurate for anything added this release.
- [ ] Known-broken items are disclosed, not hidden.

---

## Known broken / parked

Kept here so a future version bump doesn't mistake them for regressions.

- **Manual capture (ctrl+shift+right-click)** — broken. Behind `/pgdebug`, and disabled under shaders.
- **Solas edge creases** — its AO is derived from `depthtex0` alone with no material input, so no
  render-program or material trick can opt out. Ships as PARTIAL with a caveat.
- **Cubemap face seams under shaders** — per-face auto-exposure drift, not dimension hue. Ghost-free and
  seam-free pull against each other and `SETTLE_FRAMES` is that dial. An exposure-levelling pass was built
  and reverted.
- **`L` flat-depth lever** — unresolved. Check it as a *performance* problem first: it adds a second full
  draw pass, roughly doubling glimpse geometry.
- **Peaceful difficulty refills the hunger bar** — not a mod issue, but it bit us while staging footage.
  Use `doMobSpawning false` on Easy instead.
