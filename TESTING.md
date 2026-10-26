# Portal Glimpse — release checklist

**This is the gate. A version does not go to Modrinth until this list passes on that version.**

Run it before tagging any release, and after every Minecraft / Fabric / Iris / Sodium bump. Section 1
comes first on purpose: those are the failures a green Gradle build cannot catch. Everything after it is
grouped into **runs** — each run is one world setup, so the whole list is a few sittings rather than a
hundred separate launches.

Every item is written as **do this → expect that**. If the "expect" doesn't happen, it's a regression.
Record the outcome in the verdict at the bottom; "didn't check" is not a pass.

---

## 1. Version-bump hazards — check these FIRST

These have all bitten this project before. A clean build says nothing about any of them.

### 1a. If the MINECRAFT version changed

The render stack this mod is built on is replaced above 1.21.4. Expect nothing below this subsection to
be runnable until the port compiles again.

- [ ] Bump these together in `gradle.properties`: `minecraft_version`, `yarn_mappings`, `loader_version`,
      `fabric_api_version`, `cloth_config_version`, `modmenu_version`.
      *Mismatched yarn against minecraft_version fails in a way that reads like your code is wrong.*
- [ ] **Set `"minecraft"` in `fabric.mod.json` to the version you are shipping.** One jar per version.
      *Forget this and the jar refuses to load on the very version you just built it for.*
- [ ] Re-check the access widener against the new mappings: `RenderLayer.of` by exact descriptor, plus
      eight `RenderPhase` fields. *Gone at 1.21.5 — `RenderPipeline` replaces the phase system.*
- [ ] The three core shader programs in `assets/portal-glimpse/shaders/core/` still compile and link.
      *Vanilla's core-shader format and uniform set move between versions. A broken one draws pink or
      black and the only evidence is a log line, not an error on screen.*
- [ ] Every Fabric API hook still exists with the same signature — `WorldRenderEvents` /
      `WorldRenderContext` (incl. `context.frustum()`), `HudRenderCallback`, the `ScreenEvents` trio,
      `ClientChunkEvents`, `ClientSendMessageEvents`.
- [ ] All 13 mixin targets still exist, still match, **and still have the same return type**.
      *A mixin whose target was renamed fails loudly; one whose RETURN TYPE changed compiles, applies, and
      crashes only on the path that calls it — that is the 1.21.4 `getFov` Double/Float crash.*
- [ ] **Captures written by the PREVIOUS version still load** — old glimpses survive the upgrade.
      *People update with a full capture folder. Do not strand it.*
- [ ] Blocks still register. *1.21.2 began deriving the loot-table key from the block id during
      construction, so the settings must carry the registry key before the block is built.*

### 1b. Every release

- [ ] `build/classes/java/main/portalglimpse.mixins.refmap.json` exists and is non-empty.
      *Missing refmap = mixins silently never fire. No crash, no warning, hooks just don't run.*
- [ ] Refmap entry count equals mixins declared minus the `remap=false` ones (Cloth, Iris, Sodium).
      *If a mixin quietly stopped resolving, this arithmetic is the only offline sign.*
- [ ] Every class listed in `portalglimpse.mixins.json` exists in the jar.
      *A stale entry is a hard crash on load, and it is NOT a compile error.*
- [ ] `validateAccessWidener` passes, and the RTT glimpse still renders.
- [ ] **Run the built jar in a real launcher profile**, not the dev client.
      *Dev and production differ: a class in `net.minecraft.*` works in dev and throws `IllegalAccessError` in prod.*
- [ ] Load with a shaderpack, open settings → the pack name shows, not "none detected".
      *`IrisCompat` reads `Iris.getIrisConfig()` reflectively. "None detected" means Iris moved it.*
- [ ] Sodium installed → portal blocks still hide. Iris → RTT still draws.
      *Both mixins are `@Pseudo`, so they degrade to "never fires" — which looks like a feature quietly not working.*
- [ ] The clean-up row still lines up with the sliders above it, at a wide AND a narrow window.
      *It reproduces Cloth's own layout formula; if Cloth changes it, this row drifts.*
- [ ] Check the installed mod set against the version: Sodium and Iris pin each other.
      *Sodium declares a `breaks` range on Iris. A mismatch is a loader dialog, not a crash.*

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
- [ ] Nether → Overworld as well as Overworld → Nether. *Both directions, both veil colours.*
- [ ] A portal whose destination was never visited → no glimpse, plain vanilla portal, no error.

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
- [ ] Walk a full circle around a portal → no seam pops at any angle.
- [ ] Eight-plus captured portals in view at once → no frame-time cliff.
      *Nearest-N, frustum culling and the cached interior bbox all exist to stop this. Nothing
      else in this list would notice if they stopped working.*
- [ ] Build a **non-rectangular** portal (an L or a plus) → glimpse fills the actual shape.
- [ ] Build a **wide** portal (10+) → image covers it without stretching or distortion.
- [ ] Stand so clouds/rain are behind the portal → they're occluded, not drawn over it.
- [ ] Look at a portal through water, and through another portal → no z-fighting or double-draw.
- [ ] **Walk into the portal** (with `/pgdebug`, Numpad 0 to block travel) → the destination box
      pushes ahead of you smoothly. No snap.
- [ ] While inside, **look back** → the destination fades in around you (the encasement).
- [ ] Have a second player stand in a captured portal → they read as standing in the destination.
- [ ] Same in third person (F5) on yourself.
- [ ] Turn **Entity over panorama** off → the player draws normally again, no deferral.

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
- [ ] Delete a capture PNG by hand while the game is closed → reopens without crashing, and that
      portal falls back to vanilla.

## 5. RUN D — the travel experience

*The loading screen is a headline feature with its own shader. Do not fold it into Run A.*

- [ ] Travel → the hold screen shows the **destination cubemap**, not the vanilla dirt background.
      *That is `portal_loading`. If a core shader failed to load, this is where it shows.*
- [ ] The backdrop is oriented to **the angle you entered at**, and moving the mouse during the hold
      does not spin it.
- [ ] The portal swirl tiles **over** that backdrop at the destination dimension's veil opacity.
- [ ] "Loading terrain…" is still readable over it.
- [ ] Travel to a destination never captured → plain vanilla loading screen, no black screen.
- [ ] The hold releases on its own; it never needs a keypress.
- [ ] Arrive → the arrival veil fades out rather than cutting.

## 6. RUN E — ghosting and the frame

*Vanilla only, no Sodium. Sodium's own path is Run J.*

- [ ] A captured portal's purple portal blocks are **hidden** behind the glimpse, not drawn over it.
- [ ] The obsidian frame is cloned correctly all the way around — **check every side, including the
      bottom row**. *There is an old unconfirmed report that one side is missed.*
- [ ] Frame the portal in a non-obsidian setting (cobblestone, deepslate, a stairs or slab wall) →
      the cloned frame matches the surrounding block family rather than defaulting.
      *That is `BlockFamilies`. Nothing else in this list exercises it.*
- [ ] Break a frame block → the ghost updates, no floating leftovers.
- [ ] Fly 200 blocks away and back → ghost blocks re-mesh, nothing stale.

## 7. RUN F — shaders

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
- [ ] Switch **back** to RTT without restarting → the panorama returns, no leftover overlay.
- [ ] Capture under shaders → no ghost/phantom blocks in the resulting cubemap.
- [ ] Drink a potion, then capture → **no effect swirl baked into the glimpse**.
- [ ] Fast head movement under RTT → the panorama tracks. Lower the prediction slider if it wobbles.
- [ ] Turn the shaderpack off mid-session → the vanilla path takes over cleanly, no crash.

## 8. RUN G — interruption

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
- [ ] Die during the hold → respawn works, no stuck screen.

## 9. RUN H — interface

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
- [ ] Veil preview boxes above the opacity sliders update live as you drag.
- [ ] The preview panorama strip pans and wraps, and stays inside its box.
- [ ] Open the settings on a **fresh profile** → the first-run preview hint appears once, and not again.
- [ ] Open settings from Mod Menu as well as from the pause portal → the same screen.
- [ ] Every tooltip is one line and not clipped.

## 10. RUN I — every config entry

*One pass, top to bottom. Each entry gets changed, observed, and changed back.*

- [ ] **Glimpses visible** off → all glimpses vanish, portals are vanilla. On → they return.
- [ ] **Entity over panorama** — behaviour in Run B; confirm the toggle persists.
- [ ] **Detection chat** off → no "Portal detected" line, detection itself still works.
- [ ] **Creeper warning** — covered in Run G.
- [ ] **Pause menu button** — covered in Run H.
- [ ] **Shader render method** RTT ↔ OVERLAY — covered in Run F.
- [ ] **God-ray occluder** — covered in Run F.
- [ ] **Shader veil** — covered in Run F.
- [ ] **Panorama FOV** at 20 and at 60 → the view narrows and widens, no distortion at either end.
- [ ] **RTT motion prediction** at 0 and 100 → 0 lags on fast turns, 100 overshoots. 50 is the default.
- [ ] **Nether veil opacity** at 0 and 255 → the Overworld-side portal's tint follows.
- [ ] **Overworld veil opacity** at 0 and 255 → the Nether-side portal's tint follows.
- [ ] **Auto-capture cooldown** at 60 → suppresses re-capture. (0 is covered in Run A.)
- [ ] **Postcard fade distance** low and high → the flat glimpse appears closer or further out.
- [ ] **Capture chunk radius** — covered in Run K.
- [ ] Every slider takes effect **on save**, with no restart.
- [ ] The reset arrows restore the documented defaults.

## 11. RUN J — compatibility

- [ ] Sodium, no shaders → portal blocks hide correctly, swirl animates.
- [ ] **Fabulous** graphics → glimpse still occludes properly.
- [ ] Fast and Fancy graphics.
- [ ] Multiplayer server → captures land under `portalglimpse/<server>/`, not in the world folder.
- [ ] Two clients on a LAN world → each sees glimpses; the other player reads as inside the portal.
- [ ] Join a server **without** the mod → no crash, the mod simply does nothing server-side.
- [ ] Mod Menu shows the icon, description and version correctly.

## 12. RUN K — install, persistence, uninstall

*Everything here is about someone who is not you.*

- [ ] **Delete `config/portal-glimpse.json`, start the game** → defaults load, no crash, the file is rewritten.
- [ ] Change several settings, restart → all of them persisted.
- [ ] Hand-corrupt the config with bad JSON, start → recovers to defaults instead of crashing.
- [ ] Change render distance in vanilla video settings → capture radius follows it **down**, never back up.
- [ ] **Remove the mod from a world that has captures, then load that world** → it opens, with no
      missing-block screen. *`OccluderBlock` is registered; a world that has seen it must still load without it.*
- [ ] Put the mod back → the captures are still there and still display.
- [ ] Install fresh alongside a populated capture folder from the previous version → it loads them.

## 13. RUN L — the debug surface

*`/pgdebug` ships but stays hidden. It must be inert by default and harmless when on.*

- [ ] With `/pgdebug` **off**: every debug key does nothing (Numpad, H, J, K, L, Page Up/Down).
- [ ] `/pgdebug` on → the confirmation line appears, and the MCP server prints its port.
- [ ] Each key does what it claims: veil ± (Numpad 9/6), FOV ± (8/2), glimpses (H), postcard fade (J),
      debug cubemap (K), block travel (Numpad 0), ghost freeze (1), RTT blit (3), pack brightness (7/4),
      gamma (Page Up/Down), calibration report (Numpad .), RTT veil (Numpad *), cull cone (Numpad /),
      nearest-N (Numpad Enter), depth write (Numpad -), RTT pass (Numpad +), flat depth (L).
- [ ] `/pgdebug` off again → live debug state is cleared, not left applied.
- [ ] Nothing debug-related is visible or reachable without the command.

---

## 14. Ship

- [ ] `/pgdebug` **off** by default — it gates the debug keys, manual capture, and the MCP server.
- [ ] MCP server is NOT listening when debug is off (`curl 127.0.0.1:25585/mcp` refuses).
- [ ] `mod_version` bumped in `gradle.properties`, and unique to this Minecraft version.
- [ ] The `"minecraft"` value in `fabric.mod.json` matches the version you actually tested.
- [ ] Read the manifest back **out of the built jar**, not out of the source — version, deps, icon path.
- [ ] The icon is present and is not AI-generated. *In-game screenshots only.*
- [ ] The Modrinth AI disclosure is still accurate for anything added this release.
- [ ] Known-broken items are disclosed, not hidden.
- [ ] The changelog names what changed since the last release of THIS Minecraft version.

---

## 15. Verdict

Fill this in per version. A blank line is a fail.

```
Version        : 0.1.x
Minecraft      : 1.21.x
Tested by      :
Date           :

Section 1 hazards      PASS / FAIL
RUN A  core loop       PASS / FAIL
RUN B  rendering       PASS / FAIL
RUN C  data integrity  PASS / FAIL
RUN D  travel screen   PASS / FAIL
RUN E  ghosting        PASS / FAIL
RUN F  shaders         PASS / FAIL
RUN G  interruption    PASS / FAIL
RUN H  interface       PASS / FAIL
RUN I  config entries  PASS / FAIL
RUN J  compatibility   PASS / FAIL
RUN K  install/persist PASS / FAIL
RUN L  debug surface   PASS / FAIL
Ship                   PASS / FAIL

New known-broken items found (must be disclosed):

VERDICT:  SHIP  /  DO NOT SHIP
```

**Rule: any FAIL in Section 1, A, B, C, D, K or Ship is DO NOT SHIP.** Those are the ones a player hits
without trying. A FAIL in E, F, G, H, I, J or L may still ship, provided it is written into the
known-broken list below AND into the Modrinth description.

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
- **1.21.2, 1.21.3 and 1.21.4 — the `getFov` crash.** `GameRendererMixin` declares
  `CallbackInfoReturnable<Double>`, but `getFov` has returned `float` since **1.21.2** (it is `double` only on
  1.21 / 1.21.1, which is why master is fine). It throws during a capture under shaders — the one path that
  calls it — which is why the 1.21.2 and 1.21.3 test sessions never hit it. First seen on 1.21.4, but all
  three branches carry it. Diagnosed, not yet fixed.
- **1.21.5 — parked.** `RenderPipeline` replaces the phase system and removes the primitives the shader
  seam fix is built on.
- **Peaceful difficulty refills the hunger bar** — not a mod issue, but it bit us while staging footage.
  Use `doMobSpawning false` on Easy instead.
