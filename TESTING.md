# Portal Glimpse — release checklist

Run this before tagging a release, and after every Minecraft / Fabric / Iris / Sodium bump.

The order matters: the first section is what breaks *silently* on a version bump, and a green Gradle build
proves none of it. Everything below that is only worth testing once the mod actually loads.

---

## 1. Version-bump hazards — check these FIRST

These have all bitten this project before. A clean build says nothing about any of them.

- [ ] **Mixins still apply.** `build/classes/java/main/portalglimpse.mixins.refmap.json` exists and is
      non-empty. A missing refmap means mixins silently never fire — no crash, no warning, the hooks just
      don't run.
- [ ] **Every class in `portalglimpse.mixins.json` exists.** A stale entry is a hard crash on load, and it
      is NOT a compile error.
- [ ] **The access widener still resolves.** `validateAccessWidener` passes, and the RTT glimpse still
      renders (it needs `RenderLayer.of` plus several `RenderPhase` constants).
- [ ] **Run the built jar in a real profile**, not the dev client. Dev and production differ: a class placed
      in `net.minecraft.*` works in dev and throws `IllegalAccessError` in production.
- [ ] **Iris internals.** `IrisCompat` reads `Iris.getIrisConfig()` and `IrisConfig.getShaderPackName()`
      reflectively. If the pack name shows as "none detected" with a pack loaded, Iris moved them.
- [ ] **Sodium and Iris mixin targets** (`SodiumLevelSliceMixin`, `IrisPipelineMixin`). Both are `@Pseudo`
      with `remap = false`, so they degrade to "never fires" rather than crashing — which looks like a
      feature quietly not working.
- [ ] **Cloth Config layout.** The clean-up row reproduces Cloth's own entry geometry
      (`x + entryWidth - 150`, width `150 - resetWidth - 2`). If Cloth changes it, that row stops lining up.

## 2. Core — no shaders

- [ ] Light a portal: it is detected (turn on `/pgdebug` to see the chat line).
- [ ] Travel through it: the loading screen holds, then the glimpse appears on the origin portal.
- [ ] The glimpse shows real terrain, not void.
- [ ] Walk up to a portal: the flat postcard crossfades out and the parallax panorama takes over.
- [ ] Back away past the cutoff: the glimpse fades out cleanly, no pop.
- [ ] Stand in a portal: no glimpse until you step clear, then it fades in.
- [ ] A second player standing in a portal reads as standing in the destination.

## 3. Portal reshaping — the fragile one

The rule is OVERLAP, and this path **deletes records and moves PNG files**. Verify it end to end.

- [ ] Capture a portal, break it, rebuild it **bigger**: the glimpse survives, whole opening, no vanilla half.
- [ ] Same but **smaller**: glimpse survives.
- [ ] Same but **reshaped sideways**: glimpse survives.
- [ ] Merge two captured portals into one: one glimpse survives, no half-and-half.
- [ ] Restart the client afterwards: still correct. (The old bug only showed its teeth across a reload.)
- [ ] Two *separate* portals near each other do NOT steal each other's captures.

## 4. Shaders

Roster: **BSL** and **Photon** = full, **Solas** = partial (edge creases), everything else unsupported.

- [ ] Each supported pack: glimpse is neither black nor blown out.
- [ ] Solas shows the "partially supported" screen; an unlisted pack shows "not supported".
- [ ] Swap packs at runtime: the prompt re-evaluates instead of naming the old pack.
- [ ] Veil is OFF by default under shaders, and the no-shader swirl is unaffected.
- [ ] God rays do not pierce the glimpse (RTT + occluder on).
- [ ] Cross chunk boundaries near a portal: **no black occluder blocks left hanging.**
- [ ] Capture under shaders: no ghost/phantom blocks in the cubemap.

## 5. Interface

- [ ] Pause-menu portal appears beside Advancements and opens the settings.
- [ ] Right-drag moves it; the position survives a restart and a window resize.
- [ ] Its config toggle hides it (takes effect next time the menu opens).
- [ ] Disk usage reads a real figure, not a stuck "measuring…".
- [ ] Clean-up screen lists worlds and servers; deleting needs two clicks and frees the space.
- [ ] Sliders take effect on save without a restart.

## 6. Compatibility

- [ ] Sodium installed, no shaders: portal blocks still hide correctly.
- [ ] Fabulous graphics.
- [ ] Multiplayer (captures land under `portalglimpse/<server>/`).

## 7. Ship

- [ ] `/pgdebug` **off** by default — it gates the detection chat, all debug keys, and manual capture.
- [ ] `mod_version` bumped in `gradle.properties`.
- [ ] Known-broken things are disclosed, not hidden: Solas creases carry their caveat; manual capture is
      behind `/pgdebug` and vanilla-only.

---

## Known broken / parked

Kept here so a future version bump doesn't mistake them for regressions.

- **Manual capture (ctrl+shift+right-click)** — broken. Behind `/pgdebug`, and disabled under shaders.
- **Solas edge creases** — its AO is derived from `depthtex0` alone with no material input, so no
  render-program or material trick can opt out. Shipping as PARTIAL with a caveat.
- **Cubemap face seams under shaders** — per-face auto-exposure drift, not dimension hue. Ghost-free and
  seam-free pull against each other and `SETTLE_FRAMES` is that dial. An exposure-levelling pass was built
  and reverted.
- **`L` flat-depth lever** — unresolved. Worth re-checking as a *performance* problem first: it adds a
  second full draw pass, roughly doubling glimpse geometry.
