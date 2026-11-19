# Roku channel parity and hardening

Large ODD feature. Channel source: `roku/build/staging/` (BrightScript + SceneGraph XML).
Reference clients: `src/` (TUI) and `ios/MovieBox` (Swift).

## Specs

S1. `roku/build/staging/components/MainScene.brs` must not contain the chained
    assignment/comparison `screen.altMode = action.altMode = true`; the intent
    ("pass the caller's altMode flag through as a boolean") must be explicit and
    behavior must not change for callers that pass `false`.

S2. A screen that reloads must not leave a previous `ApiTask` running or observed:
    starting a new request on `BrowseScreen` and on `PlayerScreen` stops and
    unobserves the in-flight task first, and a late `response` never dereferences
    a cleared task reference.

S3. `PlayerScreen.onVideoState` must not index `m.sources` outside its bounds when
    reporting a playback error.

S4. Watch-progress writes must not call `roRegistrySection.Flush()` on every
    15-second position tick. Periodic saves write without flushing; pause,
    finished and player close flush.

S5. Online subtitle lookup must not attach subtitles from a guessed IMDb id: when
    neither the normalized title nor the release year corroborates a Cinemeta hit,
    `SUB_FindImdbId` returns `""` and the caller reports
    "Couldn't identify this title online." (parity with the TUI
    `pick_best_candidate`, which demands an exact normalized title).

S6. `hide_hindi` and provider toggles must be user preferences, not hardcoded
    behavior (currently `MovieBoxApi` drops India-origin subjects unconditionally).

S7. Details/home/search responses must be cached with TTLs in the task thread,
    mirroring `src/cache.rs` (24h homepage/details, 2h search).

S8. A settings screen must expose the persisted `pref_*` values the player
    already reads (`subtitles`, `audioLang`, `maxRes`) plus the S6 toggles.

S9. A deep link with `contentId` must route to the matching screen type; the
    current handler ignores `mediaType` and always opens details.

S10. Poster, episode and dialog widgets must expose accessibility labels
     (`speechText`) so Roku screen readers are not silent.

S11. The channel source must live outside `roku/build/` (a build output dir), a
     real `roku/scripts/package.sh` must exist, and a CI workflow must build it.

## Tasks

| ID | Specs | Route | Status |
|----|-------|-------|--------|
| T1 | S1, S3, S5 | inline | done — compiler-verified, no device |
| T2 | S2 | inline | done — compiler-verified, no device |
| T3 | S4 | inline | done — compiler-verified, no device |
| T4 | S7 | inline | done — home 30m, search 10m, details/seasons 24h; play-info never cached |
| T5 | S6, S8 | inline | done — Settings section, hideHindi pref gates the catalogue filters |
| T6 | S9 | inline | done for mediaType routing; autoplay needs a DetailsScreen field |
| T7 | S10 | inline | done — AUDIO_GUIDE_TEXT on tiles and episodes (Roku TTS docs) |
| T8 | S11 | inline | done — source moved to `roku/app`, script, CI, docs |

## Log

L1 (user, verbatim): "Que mas se puede mejorar o agregar en la version de Roku?"

L2 (agent analysis accepted by the user, summarized): four blocks — (1) concrete
bugs, (2) gaps versus the TUI client, (3) Roku-specific improvements, (4)
packaging. Evidence gathered by reading all 23 `.brs`/`.xml` files plus the TUI
reference modules.

L3 (user, verbatim): "1 2 3 4" — all four blocks, in that order.

L4 (decision): no Roku hardware and no `bsc` toolchain in the repo, so verification
uses `brighterscript` installed outside the repository (`/tmp/roku-verify`),
comparing diagnostics against a pre-change baseline. Runtime behavior remains
unverified without a device; that is reported as such, never as a pass.

L5 (decision, user): commits go to a new `feat/roku-hardening` branch, one commit
per slice.

L6 (decision, user): after the correctness slice, the next slice is Settings plus
the configurable `hideHindi` preference.

L7 (evidence): `e979a60` slice 1, compiler diagnostics equal to the pre-change
baseline (zero). Slice 2 commit hash recorded below.

L8 (deviation, recorded): S7 asked for homepage caching too. Home payloads reach
the 400 KB cap that protects `tmp:/` (shared with downloaded subtitles), so the
cap silently skips the largest pages; that is intended, not a bug.

L9 (incident, recorded): while this work was in progress another process in the
same checkout moved HEAD to `main`, pulled it, and committed `41f3ed7` onto
`feat/roku-hardening`. The packaging commit was therefore made on `main` against
a tree without these fixes. Resolved with the user's authorization: redone on
`feat/roku-hardening` (`6134f07`, fixes preserved), `main` reset to `origin/main`,
and the discarded commit kept reachable as `roku-main-packaging-rescue`.

L10 (evidence): commits per slice — `e979a60` correctness, `f480c49` settings,
`6134f07` packaging, `e57e24e` tile field list, plus the accessibility slice.
Every one compiles with zero brighterscript diagnostics; none is device-tested.

L11 (decision): accessibility scope from the Roku text-to-speech reference —
`RowList`/`MarkupGrid`/`LabelList` speak `ContentMetaData::AUDIO_GUIDE_TEXT` (else
`TITLE`), so only the item nodes were changed. Player overlay and toasts stay
silent and are documented as a limitation rather than guessed at.

L12 (review 1): lineage `review-5fb19c16152cafc9`, lenses risk/resilience/
readability over `41f3ed7..05782cd`. Approved; authority burned. Three
non-blocking findings: R2-001 (ItemNodeFields comment overclaimed),
R2-002 (AUDIO_GUIDE_TEXT written twice), R4-001 (package.sh did not exclude
__MACOSX).

L13 (review 2): `86c641e` closes those three findings. Red/green proof for
R4-001: the old zip patterns admitted 4 `__MACOSX` entries, the new ones admit 0
while stray directories were present. Lineage `review-4708eeaddae71d29`,
lenses resilience/readability, approved with no findings; authority burned.
