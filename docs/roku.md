# Roku Channel

The Roku client is an independent BrightScript/SceneGraph implementation of the
MovieBox catalogue. It shares the wire contract with the desktop client but no
code: everything under `roku/app` is maintained on its own.

## Layout

| Path | Purpose |
| :--- | :--- |
| `roku/app/manifest` | Channel manifest: id, title, resolution, launch support |
| `roku/app/source/main.brs` | Entry point: creates the scene and pumps the message loop |
| `roku/app/components/MainScene.brs` | Section navigation, screen stack, dialogs, deep links |
| `roku/app/components/screens/` | Browse, Search, Details, Episodes, Player, Providers, Settings |
| `roku/app/components/widgets/` | NavBar, PosterItem, EpisodeItem, SelectDialog, Spinner, KeyItem |
| `roku/app/components/tasks/ApiTask.brs` | Every network call runs here, off the render thread |
| `roku/app/components/lib/` | API client, source resolvers, subtitles, addons, shared helpers |
| `roku/scripts/package.sh` | Builds the sideloadable channel zip |
| `roku/bsconfig.json` | BrightScript static-check configuration |

`roku/build/` is generated output and is ignored by git. Never edit it.

## Build and validate

Static check (the only automated verification the channel has; it catches syntax,
unknown components and wrong arity, not runtime behaviour):

```bash
npx --yes brighterscript@0.73.8 bsc --project roku/bsconfig.json
```

Package for sideloading:

```bash
./roku/scripts/package.sh   # writes roku/build/moviebox-roku.zip
```

The zip must keep `manifest` at its root; the packaging script enforces that by
zipping the contents of `roku/app`, not the directory.

## Install on a device

1. Enable developer mode on the Roku (Home ×3, Up ×2, Right, Left, Right, Left, Right).
2. Open `http://<roku-ip>/` and upload `roku/build/moviebox-roku.zip`.
3. The channel appears under Development on the home screen.

## Settings

The Settings section stores plain `pref_*` values in the `library` registry
section, which is what the player and the catalogue already read:

| Preference | Values | Effect |
| :--- | :--- | :--- |
| `hideHindi` | `on` (default), `off` | Drops India-only rows, titles and the Hindi tag |
| `maxRes` | `0` (auto), `1080`, `720`, `480` | Resolution cap used when picking a source |
| `subtitles` | `off`, `spa`, `eng` | Preferred subtitle track |
| `audioLang` | ``, `spa`, `eng` | Preferred audio track on multi-language files |

## Deep links

The channel accepts `contentid` and `mediatype` launch parameters in either
casing — Roku sends them lowercase, a typed link may send camelCase — from Roku
Search, voice search, or a `roInput` event while running.

| `mediatype` | Result |
| :--- | :--- |
| `movie` | Plays that movie directly, at its saved bookmark |
| `series` | Opens the series and starts its bookmarked episode; season 1 episode 1 when there is no bookmark |
| anything else, or missing | Opens the details screen |
| a `contentid` the catalogue does not know | Returns to Home with a notice |

Direct to Play means no screen stands between the Roku Search selection and the
video (certification items 5.1 and 5.2).

## Roku Search feed

`search-feed` builds the JSON feed Roku's search crawler reads. It ships with the
crate, next to `moviebox-tui`:

```bash
search-feed --language en --countries us,mx --output roku-search-feed.json
```

`--language` and `--countries` are required: the feed states which languages and
markets it serves, so neither is guessed. `--quality` (`sd`, `hd`, `hd+`, `fhd`,
`uhd`) is the quality claim published for every asset and defaults to `hd`.
`--input <dump.json>` builds the same feed from a saved catalogue dump instead of
fetching live; that is the path the tests use. Titles that cannot satisfy Roku's
schema (no title, no poster, no year, id over 50 characters) are dropped and
counted, and the command exits 1 instead of writing an empty feed over a good one.

The feed carries movies and series. Every `playId` is the subject id the channel
resolves from `contentid`, and every asset `type` is the `mediatype` the channel
expects, so a feed entry cannot drift from the launch path; one test pins both
directions. Genres are translated to Roku's closed enum through
`roku/search-feed/genres.json` and advisory ratings through
`roku/search-feed/ratings.json`, which also carry the allowed value lists the
tests check against.

Registering it is Roku's side and outside this repository:

1. Host the JSON file at a public URL. Roku crawls it and supports one optional
   HTTP basic-auth pair.
2. Developer Dashboard → the app → Search feeds → new search feed. Only public,
   published apps can be selected, so a sideloaded or Development channel cannot
   participate; sideloading only helps to test the deep links.
3. Supply the provider logo (143×113 PNG), the teaser logo (165×60 PNG) and a
   deep-link sample per media type, then submit. Roku validates the feed,
   completes deep-link certification and only then publishes it.
4. Limits: at most 20 submissions per week, up to 24 hours of propagation, and a
   feed is not reprocessed unless it is resubmitted.

## Caching

Catalogue responses are cached on `tmp:/cache` and reused until their TTL
expires: homepage 30 minutes, search 10 minutes, details and season lists 24
hours. Signed `play-info` responses are never cached because the CDN URLs they
return expire. Payloads above 400 KB are skipped, and the cache is capped at 60
files: the oldest entries are pruned first (an index next to the files records
when each one was written), because `tmp:` is small and shared with downloaded
subtitles. `tmp:/subs` keeps at most 40 subtitle files under the same rule, so a
long binge does not fill the partition.

## Deadlines

Every network path has a deadline so a dead connection cannot leave the viewer
on a spinner: details 25 s, search 20 s, stream lookup 35 s, addon check 20 s.
Details and streams then offer their retry or other-provider path, search clears
its spinner with a hint, and the signed-host fallback walk inside one request is
budgeted at 25 s instead of 7 hosts × 12 s.

## Known limitations

- No DRM: protected titles are not playable.
- The Search feed is built by this repository but is not registered with Roku
  yet, so the platform's global search does not list the channel until it is.
- Screen reader coverage is partial: browse tiles, search results and episodes set
  `AUDIO_GUIDE_TEXT` so the focused item is spoken. The player overlay, toasts and
  the custom keyboard keys are still silent.
- Playback, focus and dialog behaviour are only verified by the static check;
  there is no device or harness test in CI.
