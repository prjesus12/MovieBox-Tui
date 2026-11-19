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

The channel accepts `contentId` and `mediaType` launch parameters (Roku Search,
voice search, or a `roInput` event while running). `mediaType` containing
`series`, `season` or `episode` opens the title as a series; anything else opens
as a movie. There is no Roku Search feed published yet.

## Caching

Catalogue responses are cached on `tmp:/cache` and reused until their TTL
expires: homepage 30 minutes, search 10 minutes, details and season lists 24
hours. Signed `play-info` responses are never cached because the CDN URLs they
return expire. Payloads above 400 KB are skipped, and the cache is emptied once
it holds 60 files, because `tmp:` is small and shared with downloaded subtitles.

## Catalogue tabs

Home arrives as plain subject rows, but Movies, TV Shows and Anime are curated
pages built mostly out of category and people tiles: their `customData` items
carry a real `subject` only on some rows, and the Movies page carries none at all.
The channel renders the tiles it can open and, when a category page is left with
almost nothing, fills it with the Home rows that belong to that catalogue (movies,
series, anime by genre). A page that still has nothing to show, or a catalogue
request that fails, lands on a retry screen instead of an endless spinner.

## Known limitations

- No DRM: protected titles are not playable.
- No Roku Search feed, so the platform's global search does not list the channel.
- Screen reader coverage is partial: browse tiles, search results and episodes set
  `AUDIO_GUIDE_TEXT` so the focused item is spoken. The player overlay, toasts and
  the custom keyboard keys are still silent.
- Playback, focus and dialog behaviour are only verified by the static check;
  there is no device or harness test in CI.
