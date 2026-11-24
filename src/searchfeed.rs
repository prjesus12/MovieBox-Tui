//! Roku Search feed generation (specs S1–S6 of `odd/tasks/roku-search-feed.md`).
//!
//! The feed is data Roku crawls, never something playback code asks for: the
//! transformation here is pure so the mapping can be tested without a network,
//! and the command in `src/bin/search-feed.rs` is the only caller.
//!
//! Spec S4 is the load-bearing rule: an asset's `playId` is exactly the
//! MovieBox subject id the channel resolves as `contentId`, and the asset
//! `type` is exactly the `mediaType` the channel handles.

use std::collections::{BTreeMap, HashSet};
use std::fmt;
use std::path::PathBuf;
use std::sync::LazyLock;

use serde::{Deserialize, Serialize};

use crate::providers::models::{CatalogItem, MediaDetails, MediaType};

/// Roku Search feed schema version (`version: "1"`).
pub const FEED_VERSION: &str = "1";
/// Spec S3: asset ids are at most 50 characters.
pub const MAX_ID_CHARS: usize = 50;
/// Spec S3: titles and short descriptions are at most 200 characters.
pub const MAX_TEXT_CHARS: usize = 200;
/// Default `--quality`; one of [`QUALITIES`].
pub const DEFAULT_QUALITY: &str = "hd";
/// Roku's allowed `playOptions[].quality` values.
pub const QUALITIES: [&str; 5] = ["sd", "hd", "hd+", "fhd", "uhd"];
/// `--language` default is not a guess: the flag is required (spec S2).
pub const REQUIRED_LANGUAGE_FLAG: &str = "--language";
/// `--countries` is required too (spec S2).
pub const REQUIRED_COUNTRIES_FLAG: &str = "--countries";
/// Image type Roku Search requires for the poster.
pub const IMAGE_TYPE_MAIN: &str = "main";
/// A free ad-supported title, which is what MovieBox offers.
pub const LICENSE_FREE: &str = "free";
/// MovieBox homepage tab id for movies (matches the TUI/roku channel).
pub const MOVIES_TAB: &str = "2";
/// MovieBox homepage tab id for TV shows (matches the TUI/roku channel).
pub const SERIES_TAB: &str = "5";

pub const HELP: &str = "\
usage: search-feed --language <iso639-1> --countries <us,mx> [options]

Builds the Roku Search feed for the MovieBox catalogue.

Required:
  --language <code>    ISO 639-1 language for titles, e.g. en (spec S2)
  --countries <list>   comma-separated ISO Alpha-2 markets, e.g. us,mx (spec S2)

Options:
  --input <path>       build from a catalogue dump instead of fetching live
  --output <path>      write the feed here instead of stdout
  --quality <value>    sd, hd, hd+, fhd or uhd (default: hd)
  -h, --help           print this help

Exit codes: 0 when at least one asset was emitted, 1 when none was, 2 on a
usage error. The drop report always goes to stderr.";

/// The two asset kinds Roku Search accepts for this feature (spec S3/S4).
///
/// The serialized value is the `type` Roku expects *and* the deep-link
/// `mediaType` the channel handles, so the two cannot drift apart.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AssetType {
    Movie,
    Series,
}

impl AssetType {
    /// The Roku `type` value, which is also the deep-link `mediaType` value.
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Movie => "movie",
            Self::Series => "series",
        }
    }

    /// Parse a Roku `type`/`mediaType` value (spec S7: case-insensitive).
    pub fn parse(value: &str) -> Option<Self> {
        match value.trim().to_ascii_lowercase().as_str() {
            "movie" => Some(Self::Movie),
            "series" => Some(Self::Series),
            _ => None,
        }
    }

    pub const fn from_media_type(media_type: MediaType) -> Self {
        match media_type {
            MediaType::Movie => Self::Movie,
            MediaType::Series => Self::Series,
        }
    }

    pub const fn to_media_type(self) -> MediaType {
        match self {
            Self::Movie => MediaType::Movie,
            Self::Series => MediaType::Series,
        }
    }

    /// The provider `stype` the channel derives from this `mediaType`:
    /// `roku/app/components/MainScene.brs` sets 1 for movies and 2 for series.
    pub const fn channel_stype(self) -> u8 {
        match self {
            Self::Movie => 1,
            Self::Series => 2,
        }
    }
}

/// Media type in the catalogue dump (`movie` / `series`).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DumpMediaType {
    #[default]
    Movie,
    Series,
}

impl From<DumpMediaType> for MediaType {
    fn from(value: DumpMediaType) -> Self {
        match value {
            DumpMediaType::Movie => Self::Movie,
            DumpMediaType::Series => Self::Series,
        }
    }
}

impl From<MediaType> for DumpMediaType {
    fn from(value: MediaType) -> Self {
        match value {
            MediaType::Movie => Self::Movie,
            MediaType::Series => Self::Series,
        }
    }
}

/// One catalogue entry, as read from `--input` or derived from the live API.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct DumpItem {
    #[serde(default)]
    pub provider: String,
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub media_type: DumpMediaType,
    #[serde(default, deserialize_with = "de_optional_year")]
    pub year: Option<String>,
    #[serde(default)]
    pub poster_url: Option<String>,
    #[serde(default)]
    pub genre: Option<String>,
    #[serde(default)]
    pub rating: Option<String>,
    #[serde(default)]
    pub description: Option<String>,
}

/// Accept a year written as a JSON number or a string; both appear in dumps.
fn de_optional_year<'de, D>(deserializer: D) -> Result<Option<String>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    #[derive(Deserialize)]
    #[serde(untagged)]
    enum Year {
        Text(String),
        Number(i64),
    }

    Ok(
        Option::<Year>::deserialize(deserializer)?.map(|year| match year {
            Year::Text(text) => text,
            Year::Number(number) => number.to_string(),
        }),
    )
}

/// Text localized to one or more languages (`titles` / `shortDescriptions`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct LocalizedText {
    pub value: String,
    pub languages: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AdvisoryRating {
    pub source: String,
    pub value: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AssetImage {
    #[serde(rename = "type")]
    pub image_type: String,
    pub url: String,
    pub languages: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlayOption {
    pub license: String,
    pub quality: String,
    pub play_id: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Content {
    pub play_options: Vec<PlayOption>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Asset {
    pub id: String,
    #[serde(rename = "type")]
    pub asset_type: AssetType,
    pub titles: Vec<LocalizedText>,
    pub short_descriptions: Vec<LocalizedText>,
    pub genres: Vec<String>,
    pub advisory_ratings: Vec<AdvisoryRating>,
    pub images: Vec<AssetImage>,
    pub release_year: i32,
    pub content: Content,
}

/// The Roku Search feed root (spec S1).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Feed {
    pub version: String,
    pub default_language: String,
    pub default_availability_countries: Vec<String>,
    pub assets: Vec<Asset>,
}

/// Why a catalogue entry did not become an asset (spec S6).
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum DropReason {
    /// The catalogue entry has no usable id, so `playId` could not be exact.
    MissingId,
    /// The catalogue entry has no title.
    MissingTitle,
    /// The catalogue entry has no poster, so `images[].main` is impossible.
    MissingPoster,
    /// The catalogue entry has no usable release year.
    MissingYear,
    /// The id is longer than [`MAX_ID_CHARS`].
    IdTooLong,
    /// Another asset already claimed this id.
    DuplicateId,
    /// The live API could not return details for the entry (live mode only).
    DetailsUnavailable,
    /// The requested `quality` is not one of Roku's accepted values.
    InvalidQuality,
}

impl DropReason {
    pub const ALL: [Self; 8] = [
        Self::MissingId,
        Self::MissingTitle,
        Self::MissingPoster,
        Self::MissingYear,
        Self::IdTooLong,
        Self::DuplicateId,
        Self::DetailsUnavailable,
        Self::InvalidQuality,
    ];

    pub const fn label(self) -> &'static str {
        match self {
            Self::MissingId => "missing id",
            Self::MissingTitle => "missing title",
            Self::MissingPoster => "missing poster",
            Self::MissingYear => "missing year",
            Self::IdTooLong => "id too long",
            Self::DuplicateId => "duplicate id",
            Self::DetailsUnavailable => "details unavailable",
            Self::InvalidQuality => "invalid quality",
        }
    }
}

/// Emitted and dropped counts for one run (spec S6).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Report {
    pub emitted: usize,
    dropped: BTreeMap<DropReason, usize>,
}

impl Report {
    pub fn record(&mut self, reason: DropReason) {
        self.record_n(reason, 1);
    }

    pub fn record_n(&mut self, reason: DropReason, count: usize) {
        if count > 0 {
            *self.dropped.entry(reason).or_default() += count;
        }
    }

    pub fn dropped(&self, reason: DropReason) -> usize {
        self.dropped.get(&reason).copied().unwrap_or(0)
    }

    pub fn total_dropped(&self) -> usize {
        self.dropped.values().sum()
    }
}

impl fmt::Display for Report {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "emitted {}, dropped {}",
            self.emitted,
            self.total_dropped()
        )?;
        let reasons: Vec<String> = DropReason::ALL
            .iter()
            .filter(|reason| self.dropped(**reason) > 0)
            .map(|reason| format!("{}: {}", reason.label(), self.dropped(*reason)))
            .collect();
        if !reasons.is_empty() {
            write!(f, " ({})", reasons.join(", "))?;
        }
        Ok(())
    }
}

/// Roku's closed genre enum plus the catalogue-tag translation table.
#[derive(Debug, Deserialize)]
pub struct GenreCatalog {
    /// Used for any catalogue tag the mapping does not list.
    pub fallback: Vec<String>,
    /// Lowercased catalogue tag -> Roku genre values.
    pub mapping: BTreeMap<String, Vec<String>>,
    /// Roku's closed enum.
    #[serde(rename = "rokuGenres")]
    pub roku_genres: Vec<String>,
}

/// One `advisoryRatings[]` entry as stored in the ratings data file.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RatingEntry {
    pub source: String,
    pub value: String,
}

/// Per-source allowed rating values plus the catalogue-rating translation.
#[derive(Debug, Deserialize)]
pub struct RatingCatalog {
    /// Applied when nothing in `mapping` matches.
    pub default: RatingEntry,
    /// Source -> values that source allows.
    pub sources: BTreeMap<String, Vec<String>>,
    /// Lowercased catalogue rating -> advisory rating.
    pub mapping: BTreeMap<String, RatingEntry>,
}

static GENRES: LazyLock<GenreCatalog> = LazyLock::new(|| {
    serde_json::from_str(include_str!("../roku/search-feed/genres.json"))
        .expect("roku/search-feed/genres.json must be valid JSON")
});

static RATINGS: LazyLock<RatingCatalog> = LazyLock::new(|| {
    serde_json::from_str(include_str!("../roku/search-feed/ratings.json"))
        .expect("roku/search-feed/ratings.json must be valid JSON")
});

/// The checked-in genre enum and mapping (spec S5).
pub fn genre_catalog() -> &'static GenreCatalog {
    &GENRES
}

/// The checked-in advisory-rating table.
pub fn rating_catalog() -> &'static RatingCatalog {
    &RATINGS
}

/// Translate one catalogue genre tag; never returns a free-form string (S5).
pub fn map_genres(tag: Option<&str>) -> Vec<String> {
    let catalog = genre_catalog();
    let mapped = tag
        .map(str::trim)
        .filter(|tag| !tag.is_empty())
        .map(|tag| tag.to_lowercase())
        .and_then(|tag| catalog.mapping.get(&tag).cloned());
    mapped.unwrap_or_else(|| catalog.fallback.clone())
}

/// Translate one catalogue content rating, falling back to the default entry.
pub fn map_rating(rating: Option<&str>) -> AdvisoryRating {
    let catalog = rating_catalog();
    let entry = rating
        .map(str::trim)
        .filter(|rating| !rating.is_empty())
        .map(|rating| rating.to_lowercase())
        .and_then(|rating| catalog.mapping.get(&rating).cloned())
        .unwrap_or_else(|| catalog.default.clone());
    AdvisoryRating {
        source: entry.source,
        value: entry.value,
    }
}

/// Truncate to `max` characters on a char boundary (never splits a code point).
pub fn truncate_chars(text: &str, max: usize) -> String {
    if text.chars().count() <= max {
        return text.to_string();
    }
    text.chars().take(max).collect()
}

/// A release year Roku can print: four digits in a plausible range.
pub fn usable_year(raw: &str) -> Option<i32> {
    let year = raw.trim().parse::<i32>().ok()?;
    (1800..=2200).contains(&year).then_some(year)
}

/// Normalize `--quality` to one of Roku's allowed values.
pub fn normalize_quality(raw: &str) -> Result<&'static str, String> {
    match raw.trim().to_ascii_lowercase().as_str() {
        "sd" | "480p" => Ok("sd"),
        "hd" | "720p" => Ok("hd"),
        "hd+" => Ok("hd+"),
        "fhd" | "1080p" | "full hd" => Ok("fhd"),
        "uhd" | "4k" | "2160p" => Ok("uhd"),
        other => Err(format!(
            "--quality {other:?} is not a Roku quality; expected one of {}",
            QUALITIES.join(", ")
        )),
    }
}

fn normalize_language(raw: &str) -> Result<String, String> {
    let language = raw.trim().to_ascii_lowercase();
    let valid = language.len() == 2 && language.chars().all(|c| c.is_ascii_alphabetic());
    if valid {
        Ok(language)
    } else {
        Err(format!(
            "--language {raw:?} is not an ISO 639-1 code; expected two letters such as \"en\""
        ))
    }
}

fn parse_countries(raw: &str) -> Result<Vec<String>, String> {
    let mut countries: Vec<String> = Vec::new();
    for country in raw.split(',') {
        let country = country.trim().to_ascii_lowercase();
        if country.is_empty() {
            continue;
        }
        let valid = country.len() == 2 && country.chars().all(|c| c.is_ascii_alphabetic());
        if !valid {
            return Err(format!(
                "--countries {country:?} is not an ISO Alpha-2 code; expected values such as us,mx"
            ));
        }
        if !countries.contains(&country) {
            countries.push(country);
        }
    }
    if countries.is_empty() {
        return Err(format!(
            "--countries {raw:?} lists no ISO Alpha-2 code; expected values such as us,mx"
        ));
    }
    Ok(countries)
}

/// Raw command line values, before the required ones are enforced.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct RawArgs {
    pub input: Option<PathBuf>,
    pub output: Option<PathBuf>,
    pub language: Option<String>,
    pub countries: Option<String>,
    pub quality: Option<String>,
    pub help: bool,
}

/// Validated command line.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Config {
    pub input: Option<PathBuf>,
    pub output: Option<PathBuf>,
    pub language: String,
    pub countries: Vec<String>,
    pub quality: String,
}

fn next_value(iter: &mut std::slice::Iter<'_, String>, flag: &str) -> Result<String, String> {
    iter.next()
        .cloned()
        .ok_or_else(|| format!("{flag} requires a value"))
}

/// Parse the command line by hand, like the rest of the crate.
pub fn parse_args(argv: &[String]) -> Result<RawArgs, String> {
    let mut args = RawArgs::default();
    let mut iter = argv.iter();
    while let Some(arg) = iter.next() {
        match arg.as_str() {
            "--input" => args.input = Some(PathBuf::from(next_value(&mut iter, "--input")?)),
            "--output" => args.output = Some(PathBuf::from(next_value(&mut iter, "--output")?)),
            "--language" => args.language = Some(next_value(&mut iter, "--language")?),
            "--countries" => args.countries = Some(next_value(&mut iter, "--countries")?),
            "--quality" => args.quality = Some(next_value(&mut iter, "--quality")?),
            "-h" | "--help" => args.help = true,
            other => {
                return Err(format!(
                    "unknown argument {other:?}\n{}",
                    HELP.lines().next().unwrap_or_default()
                ));
            }
        }
    }
    Ok(args)
}

/// Enforce the required inputs of spec S2 and normalize the rest.
pub fn resolve(args: RawArgs) -> Result<Config, String> {
    let language = args.language.ok_or_else(|| {
        format!("{REQUIRED_LANGUAGE_FLAG} is required (ISO 639-1, e.g. --language en)")
    })?;
    let language = normalize_language(&language)?;
    let countries = args.countries.ok_or_else(|| {
        format!("{REQUIRED_COUNTRIES_FLAG} is required (ISO Alpha-2, e.g. --countries us,mx)")
    })?;
    let countries = parse_countries(&countries)?;
    let quality = match args.quality {
        Some(quality) => normalize_quality(&quality)?.to_string(),
        None => DEFAULT_QUALITY.to_string(),
    };
    Ok(Config {
        input: args.input,
        output: args.output,
        language,
        countries,
        quality,
    })
}

/// Build one asset, or say why the entry cannot satisfy the spec (S3/S6/S4).
///
/// `playId` is the `DumpItem::id` unchanged: that is the MovieBox subject id
/// the channel receives as `contentId` (spec S4).
pub fn asset_from(item: &DumpItem, language: &str, quality: &str) -> Result<Asset, DropReason> {
    if item.id.trim().is_empty() {
        return Err(DropReason::MissingId);
    }
    if item.title.trim().is_empty() {
        return Err(DropReason::MissingTitle);
    }
    let poster = item
        .poster_url
        .as_deref()
        .map(str::trim)
        .filter(|url| !url.is_empty())
        .ok_or(DropReason::MissingPoster)?;
    let release_year = item
        .year
        .as_deref()
        .and_then(usable_year)
        .ok_or(DropReason::MissingYear)?;
    if item.id.chars().count() > MAX_ID_CHARS {
        return Err(DropReason::IdTooLong);
    }

    let language = language.to_string();
    let title = truncate_chars(item.title.trim(), MAX_TEXT_CHARS);
    let description = item
        .description
        .as_deref()
        .map(str::trim)
        .filter(|description| !description.is_empty())
        .map(|description| truncate_chars(description, MAX_TEXT_CHARS))
        .unwrap_or_else(|| title.clone());
    // Never silently publish a different quality claim than the caller asked for.
    let quality = match normalize_quality(quality) {
        Ok(value) => value,
        Err(_) => return Err(DropReason::InvalidQuality),
    };

    Ok(Asset {
        id: item.id.clone(),
        asset_type: AssetType::from_media_type(item.media_type.into()),
        titles: vec![LocalizedText {
            value: title,
            languages: vec![language.clone()],
        }],
        short_descriptions: vec![LocalizedText {
            value: description,
            languages: vec![language.clone()],
        }],
        genres: map_genres(item.genre.as_deref()),
        advisory_ratings: vec![map_rating(item.rating.as_deref())],
        images: vec![AssetImage {
            image_type: IMAGE_TYPE_MAIN.to_string(),
            url: poster.to_string(),
            languages: vec![language],
        }],
        release_year,
        content: Content {
            play_options: vec![PlayOption {
                license: LICENSE_FREE.to_string(),
                quality: quality.to_string(),
                play_id: item.id.clone(),
            }],
        },
    })
}

/// Turn catalogue entries into assets and count what was dropped (S6).
///
/// The first entry to claim an id is emitted; later entries with that id are
/// dropped as duplicates so the feed keeps unique, stable ids.
pub fn build_feed(items: Vec<DumpItem>, language: &str, quality: &str) -> (Vec<Asset>, Report) {
    let mut report = Report::default();
    let mut assets = Vec::new();
    let mut seen: HashSet<String> = HashSet::new();

    for item in &items {
        match asset_from(item, language, quality) {
            Ok(asset) => {
                if !seen.insert(asset.id.clone()) {
                    report.record(DropReason::DuplicateId);
                    continue;
                }
                report.emitted += 1;
                assets.push(asset);
            }
            Err(reason) => report.record(reason),
        }
    }

    (assets, report)
}

/// Wrap emitted assets in the Roku Search feed root (S1).
pub fn feed_from(assets: Vec<Asset>, language: &str, countries: &[String]) -> Feed {
    Feed {
        version: FEED_VERSION.to_string(),
        default_language: language.to_string(),
        default_availability_countries: countries.to_vec(),
        assets,
    }
}

/// One finished run: the feed, its report, the serialized JSON, and the exit
/// code the command must return (S6: 1 when nothing was emitted).
#[derive(Debug, Clone)]
pub struct RunResult {
    pub feed: Feed,
    pub report: Report,
    pub json: String,
    pub exit_code: i32,
}

/// Build and serialize a feed from catalogue entries; no I/O.
pub fn run(items: Vec<DumpItem>, config: &Config) -> Result<RunResult, String> {
    let (assets, report) = build_feed(items, &config.language, &config.quality);
    let feed = feed_from(assets, &config.language, &config.countries);
    let json = serde_json::to_string_pretty(&feed)
        .map_err(|error| format!("cannot serialize the Roku Search feed: {error}"))?;
    let exit_code = i32::from(feed.assets.is_empty());
    Ok(RunResult {
        feed,
        report,
        json,
        exit_code,
    })
}

/// Derive a dump entry from a live homepage row plus its details.
///
/// The row's id is kept verbatim so `playId` stays the subject id the channel
/// resolves; the details only fill in what the listing row lacks.
pub fn dump_from_details(item: &CatalogItem, details: &MediaDetails) -> DumpItem {
    let media_type = if details.is_series() || item.media_type == MediaType::Series {
        DumpMediaType::Series
    } else {
        DumpMediaType::Movie
    };
    let title = if details.title.trim().is_empty() {
        item.title.clone()
    } else {
        details.title.clone()
    };
    DumpItem {
        provider: item.id.provider.cache_key().to_string(),
        id: item.id.value.clone(),
        title,
        media_type,
        year: details.year.clone().or_else(|| item.year.clone()),
        poster_url: details
            .poster_url
            .clone()
            .or_else(|| item.poster_url.clone()),
        genre: details.genres.first().cloned(),
        // The catalogue has no content rating; the ratings file's default
        // advisory rating is the honest answer.
        rating: None,
        description: details.description.clone(),
    }
}

/// Parse a `--input` catalogue dump.
pub fn parse_dump(text: &str) -> Result<Vec<DumpItem>, String> {
    serde_json::from_str(text).map_err(|error| format!("cannot parse the catalogue dump: {error}"))
}

/// The tab ids the live fetch walks, in feed order: movies then series.
pub fn live_tabs() -> [&'static str; 2] {
    [MOVIES_TAB, SERIES_TAB]
}
