# Somewhere Up There: notes for Claude

A compass that points to the ISS and counts down to its next visible pass. Three places: a
home-screen widget (2×2 and 4×2), the app (one full screen), and a Glyph Toy on the Phone (3)'s
Glyph Matrix. Sibling of Somewhere to Nothing (`terraformist-dev/somewhere-to-nothing`), whose
compass, widget and Glyph code this started from.

Owner has no coding experience and works only from a phone. Explain changes in plain English,
never ask them to run commands, and always finish by pushing to `main` and confirming the
GitHub Actions build went green (the build publishes `somewhere-up-there.apk` as the latest release).

## Project

Android app (Kotlin, no AndroidX, no Compose), package `dev.terraformist.upthere`, minSdk 34.
Builds only in GitHub Actions (`.github/workflows/build.yml`); the sandbox cannot reach Google's
Maven or Gradle servers. The debug build is signed with `app/upthere.keystore` (committed on
purpose) so updates install over the old app. Don't change the key or applicationId.

## Orbit maths (pure Kotlin, no Android: test it on a computer)

- `Orbit.kt`: TLE parsing (with checksums) and SGP4 for near-Earth orbits, following Vallado's
  reference code with WGS-72 constants. Checked against python `sgp4` 2.27: positions agree to
  about 1e-9 km. Only the near-Earth branch is implemented (fine: the ISS's period is ~92 min).
- `Sky.kt`: TEME → Earth-fixed (GMST only), observer on WGS-84, azimuth/elevation/range, ground
  point, a low-precision Sun, and a cylindrical shadow test. Checked against Skyfield 1.55:
  az/el within 0.003°.
- `Passes.kt`: a pass is **visible** when elevation ≥ 10°, the Sun ≤ −6° at the observer, and the
  ISS is sunlit. Coarse 60 s scan for above-horizon spans, then 5 s steps inside them. Pass start
  and end are the visible stretch, not rise/set. 10 days ahead; ~40 ms on a laptop.
  Rise times matched Skyfield's 10° events within the 5 s step.
- Many passes (e.g. pre-dawn in autumn) begin mid-sky as the ISS leaves the Earth's shadow.
  When the visible start is above 15° (`Pass.startsHigh`), the words say "APPEARS NNW 86° UP"
  instead of "RISES NNW" (`Words.rises`), so nobody stares at the horizon.
- To test: download `kotlin-compiler-2.0.21.zip` from JetBrains' GitHub releases, compile
  `Orbit.kt Sky.kt Passes.kt Geo.kt` with a small `main`, and compare with `pip install sgp4 skyfield`.

## Orbit data (`OrbitData.kt`)

CelesTrak `gp.php?CATNR=25544&FORMAT=TLE` (fallback `live.ariss.org/iss.txt`), at most every 12 h,
30 min back-off after a failure. The build workflow also fetches it into `assets/iss.tle`
(gitignored; non-fatal if it fails). The newest of saved and packed is used. After a new orbit,
call `Now.forget()` and `Reminders.schedule()`.

## Stages (`Now.kt`, agreed with the owner in the mockup)

- **WAITING** (next pass within 24 h): widget points to where it'll **rise**; the app and Glyph
  point at the ISS **now** (bearing to the ground point under it). The owner chose this split
  on purpose because a widget can't redraw often enough to follow the ISS. Keep it.
- **SOON** (within 60 min): everything points to where it'll rise. App headline = the direction
  in dots ("WSW"). Glyph: outer ring fills clockwise as the hour passes, plus a needle.
- **OVERHEAD**: compass becomes a sky dome (centre = straight up, ring = horizon, map-style so
  east is right when north is up). Track dots bright behind, faint ahead; red station on it.
  The app's dome turns with the heading; the widget's is north-up.
- **QUIET** (≥ 24 h): days to go, compass dimmed. **NONE**: nothing in 10 days.
- Also NO_PERMISSION, LOOKING (no location yet), NO_ORBIT.

## Design

- Nothing look: black, `#1A1A1A` widget cards, white dots, ghost dots, one red accent (`#D71921`),
  which is only ever the station (body + three-dot solar panel each side). Labels in Space Mono.
- Numbers are dots (`DotFont.LARGE` 5×7, `DotFont.SMALL` 3×5 for the matrix).
  **The owner's rules: a decimal point is ONE dot, a colon is TWO single dots, a comma is three
  dots swooping down-left.** Never swap in a font for numbers.
- `Art.kt` draws compass, dome, numbers and labels for both widget and app. 120-unit box:
  ring of 48 dots at r54, ghost disc, 15-dot arrow, station at the top of the ring.
- Times are 24-hour, UK weekday names. Countdown formats live in `Words.kt`.

## How each surface behaves

- **Widget** (`UpThereWidget`): one bitmap at its real size; layout switches at aspect 1.6.
  Redraw timer: every minute (WAITING/SOON), every 15 s (OVERHEAD), else 30 min, using a
  non-waking exact RTC alarm (`USE_EXACT_ALARM`), so it never wakes the phone just to redraw.
- **App** (`UpThereActivity` + `UpThereView`): redraws every 200 ms. Button toggles reminders;
  hidden during a pass (the view draws a hint instead).
  Tapping the title opens the About card, a floating card like Somewhere to Nothing's: an invisible
  `title_tap` view sits over the drawn title so the title itself looks unchanged. Card text is in
  `strings.xml` (`about_*`); the "Look up." heading is dot-matrix (`DotTextView`), which is why
  `DotFont.LARGE` has lower-case k, o, p, u. The owner asked for the "let the widget follow you"
  background-location prompt to be removed; don't bring it back.
- **Reminders** (`Reminders.kt`): off until tapped. Exact waking alarm 5 min before each visible
  pass; posts one notification; reschedules on boot, app pause, widget update and new orbit.
- **Glyph Toy** (`UpThereToyService`, `MatrixArt`): live for 60 s after a Glyph Button touch or
  while the screen is on, then a still frame. Long-press shows time to go for 4 s (h:mm waiting,
  m:ss in the final hour and during a pass, "6D" quiet). `Facing.BACK`.
- **Glyph switch-on** (`MatrixArt.intro`, 1.2 s, once each time the toy is picked): a faint
  horizon line, a bright dot rising over it and arcing across like a pass with a fading trail,
  then a crossfade into the normal picture. The owner wants each Somewhere toy to have its own
  switch-on so he can tell them apart while cycling: Nothing dissolves to a dot that shoots to
  the edge, Someone has two dots meeting in a beating heart. Keep this one different from those.

## Glyph Matrix SDK 2.0 (same .aar as the sibling apps)

`GlyphMatrixManager.getInstance(ctx)`, `init(Callback)`, `register(Glyph.DEVICE_23112)`,
`setMatrixFrame(IntArray)` (625 ints, 0..4095), `turnOff()`, `unInit()`. Row indents
`9,7,5,4,3,2,2,1,1,0,0,0,0,0,0,0,1,1,2,2,3,4,5,7,9` (`GlyphShape`).

## Images

Launcher icon, widget picker previews and Glyph Toy preview are PNGs in `res/drawable-nodpi/`,
drawn to match `Art.kt` and the mockup.
