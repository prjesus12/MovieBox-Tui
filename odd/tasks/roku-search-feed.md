# Roku Search feed and deep-link launch

Feature: make the Roku channel eligible for Roku Search (platform search and voice
search) by producing the JSON search feed Roku crawls and by making the channel's
deep-link launch behave as Roku certification requires.

Authoritative spec, retrieved 2026-11-24: https://developer.roku.com/dev/docs/search-feed,
https://developer.roku.com/dev/docs/implementing-deep-linking,
https://developer.roku.com/dev/docs/implementing-search,
https://developer.roku.com/dev/docs/direct-to-play.

## Specs

S1. The repository produces one Roku Search feed file — JSON, spec `version: "1"`,
    root fields `version`, `defaultLanguage`, `defaultAvailabilityCountries`,
    `assets` — from a command that is never invoked by playback code.

S2. Language and markets are inputs, not guesses: the command requires
    `--language` (ISO 639-1, lowercase) and `--countries` (ISO Alpha-2, lowercase,
    comma separated) and fails with a message when either is missing.

S3. Every asset carries: `id` (≤50 chars, unique, stable for the same title across
    runs), `type` (`movie` for a movie, `series` for a series), `titles[].value`
    (≤200 chars), `shortDescriptions[].value` (≤200 chars), `genres[]`,
    `advisoryRatings[]`, `images[]` with one `main` poster, `releaseYear`, and
    `content.playOptions[]` with `license: "free"`, a `quality` and a `playId`.

S4. `playId` is exactly the `contentId` the Roku channel resolves for that title
    (the MovieBox subject id), and `type` maps to the `mediaType` the channel
    handles: `movie` → `movie`, `series` → `series`. One test pins the mapping in
    both directions so the feed and the channel cannot drift apart.

S5. `genres[]` values come only from Roku's closed enum stored in
    `roku/search-feed/genres.json` (227 values, taken verbatim from Roku's schema;
    an earlier draft of this line said 216, which was a miscount). Catalogue tags are translated by
    that file's `mapping`; an unmapped tag falls back to the file's `fallback`
    list, never to a free-form string.

S6. A title that cannot satisfy the spec (no title, no poster, no usable year, or
    an id longer than 50 characters) is dropped instead of emitted partially, and
    the command reports emitted and dropped counts by reason. Exit code is 0 when
    at least one asset was emitted and 1 when none was.

S7. The Roku channel accepts the launch keys case-insensitively: `contentid` and
    `contentId`, `mediatype` and `mediaType`.

S8. A `mediaType` of `movie` starts that movie in playback at its bookmark, with no
    intermediate screen; `series` starts the series at its bookmarked episode; any
    other or missing `mediaType` keeps today's details-screen behaviour; a
    `contentId` the catalogue does not know lands on Home instead of an error
    screen.

S9. `docs/roku.md` documents the registration path (Developer Dashboard → app →
    Search feeds), its prerequisites (a published public app, a 143×113 provider
    logo, a 165×60 teaser logo, Roku's deep-link certification, at most 20
    submissions per week, up to 24 h propagation) and states plainly that a
    sideloaded channel cannot participate.

## Tasks

| ID | Specs | Route | Status |
|----|-------|-------|--------|
| T1 | S1, S2, S4, S5, S6 | worker — `src/searchfeed.rs`, `src/bin/search-feed.rs`, `Cargo.toml`, `src/lib.rs`, tests | done — 10 integration tests, 471 lib tests, `bsc` clean |
| T2 | S3 | worker (same unit as T1) | done — `cargo test searchfeed`: 10 passed |
| T3 | S7, S8 | worker — `roku/app/**` | done — compiler-verified, no device |
| T4 | S9 | inline — `docs/roku.md`, this log | done |
| T5 | all | inline — verification and native review | pending |

## Log

L1 (user, verbatim): "Si" — accepting the previous proposal, quoted: "¿Arrancamos
con el Roku Search feed, que es el que más rinde por línea escrita?"

L2 (research, cited): Roku's current mechanism is the JSON search feed plus deep
linking and Direct to Play; the legacy XML feed lost support on 2025-01-01. Feed
registration happens in the Developer Dashboard and "Only public apps that have
been published can be selected", so a sideloaded channel cannot participate; the
feed must be hosted at a URL Roku crawls, with optional HTTP basic auth, at most
20 submissions per week and up to 24 h propagation.

L3 (decision): the feed carries movies and series only. Roku accepts `series`
assets whose Direct to Play launches the bookmarked episode, so per-episode assets
(and per-episode `playId` encoding) are deferred to a follow-up instead of being
half-implemented here.

L4 (decision): the genre enum is checked in as data
(`roku/search-feed/genres.json`) taken verbatim from Roku's schema rather than as
Rust code, so the closed list and our tag mapping are reviewed as one file.

L5 (correction, recorded): S5 first said the genre enum holds 216 values. Counting
`roku/search-feed/genres.json` gives 227 unique values, and every `mapping` and
`fallback` entry is inside it, so the count in the spec was the error and the data
file was right. The spec now states 227 and the test asserts closed-set membership
rather than a literal count.

L6 (worker evidence, T1/T2): RED was `unresolved import moviebox_tui::searchfeed`
before the module existed; GREEN is 10 integration tests in `tests/searchfeed.rs`,
and the whole lib suite is 471 passed. The binary was exercised directly: 2 assets
emitted with exit 0, a missing `--language` giving exit 2, and an all-dropped dump
giving exit 1 with nothing written.

L7 (worker evidence, T3): `bsc` reports zero diagnostics for the channel. The worker
also proved the check is a real signal by injecting a syntax error into a copy,
which produced `error BS1066` and `Found 1 error`. Behaviour stays statically
verified only: no device, no scene-graph harness.

L8 (decision, worker-raised): the direct-play field on `DetailsScreen` and the
`autoPlay` field on `EpisodesScreen` are added at runtime with `addFields`, because
both `.xml` files were outside that worker's edit surface. Reads are guarded with
`hasField`, so a missing field degrades to "no direct playback", never to a crash.
A stale-`contentId` notice reuses `MainScene`'s existing dialog rather than adding a
toast surface.
