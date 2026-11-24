//! Roku Search feed tests (specs S1–S6 in `odd/tasks/roku-search-feed.md`).
//!
//! Every test is offline: the feed generator is pure, so the catalogue is a
//! dump in memory and nothing here opens a socket.

use moviebox_tui::providers::models::MediaType;
use moviebox_tui::searchfeed::{
    self, AdvisoryRating, AssetType, DropReason, DumpItem, DumpMediaType, Feed, Report,
};

fn item(id: &str, title: &str, media_type: DumpMediaType) -> DumpItem {
    DumpItem {
        provider: "moviebox".into(),
        id: id.into(),
        title: title.into(),
        media_type,
        year: Some("2010".into()),
        poster_url: Some(format!("https://images.example/{id}.jpg")),
        genre: Some("Action".into()),
        rating: Some("PG-13".into()),
        description: Some(format!("{title} short description.")),
    }
}

fn config(extra: &[&str]) -> searchfeed::Config {
    let mut argv = vec![
        "--language".to_string(),
        "en".to_string(),
        "--countries".to_string(),
        "us,mx".to_string(),
    ];
    argv.extend(extra.iter().map(|arg| (*arg).to_string()));
    searchfeed::resolve(searchfeed::parse_args(&argv).expect("arguments parse"))
        .expect("arguments resolve")
}

// --- S2: language and countries are required inputs -----------------------

#[test]
fn searchfeed_missing_language_is_rejected_with_a_clear_message() {
    let argv = vec!["--countries".to_string(), "us".to_string()];
    let raw = searchfeed::parse_args(&argv).expect("arguments parse");
    let error = searchfeed::resolve(raw).expect_err("missing --language must fail");

    assert!(
        error.contains("--language"),
        "the error must name the missing flag, got: {error}"
    );
    assert!(
        error.to_lowercase().contains("required"),
        "the error must say the flag is required, got: {error}"
    );
}

#[test]
fn searchfeed_missing_countries_is_rejected_with_a_clear_message() {
    let argv = vec!["--language".to_string(), "en".to_string()];
    let raw = searchfeed::parse_args(&argv).expect("arguments parse");
    let error = searchfeed::resolve(raw).expect_err("missing --countries must fail");

    assert!(
        error.contains("--countries"),
        "the error must name the missing flag, got: {error}"
    );
    assert!(
        error.to_lowercase().contains("required"),
        "the error must say the flag is required, got: {error}"
    );
}

// --- S3: every required field, 200-char truncation on a char boundary -----

#[test]
fn searchfeed_asset_carries_every_required_field() {
    let mut source = item("671234", "Example Movie", DumpMediaType::Movie);
    source.description = Some("A short description.".into());

    let asset = searchfeed::asset_from(&source, "en", "hd").expect("asset is emittable");

    assert_eq!(asset.id, "671234");
    assert_eq!(asset.asset_type, AssetType::Movie);
    assert_eq!(asset.titles.len(), 1);
    assert_eq!(asset.titles[0].value, "Example Movie");
    assert_eq!(asset.titles[0].languages, vec!["en".to_string()]);
    assert_eq!(asset.short_descriptions.len(), 1);
    assert_eq!(asset.short_descriptions[0].value, "A short description.");
    assert_eq!(
        asset.short_descriptions[0].languages,
        vec!["en".to_string()]
    );
    assert_eq!(asset.genres, vec!["action".to_string()]);
    assert_eq!(asset.advisory_ratings.len(), 1);
    assert_eq!(asset.advisory_ratings[0].source, "MPAA");
    assert_eq!(asset.advisory_ratings[0].value, "PG-13");
    assert_eq!(asset.images.len(), 1);
    assert_eq!(asset.images[0].image_type, "main");
    assert_eq!(asset.images[0].url, "https://images.example/671234.jpg");
    assert_eq!(asset.images[0].languages, vec!["en".to_string()]);
    assert_eq!(asset.release_year, 2010);
    assert_eq!(asset.content.play_options.len(), 1);
    let option = &asset.content.play_options[0];
    assert_eq!(option.license, "free");
    assert_eq!(option.quality, "hd");
    assert_eq!(option.play_id, "671234");
}

#[test]
fn searchfeed_long_multibyte_title_and_description_are_truncated_not_dropped() {
    // Three-byte characters: slicing by byte at 200 would panic or split a char.
    let long_title = "字".repeat(250);
    let long_description = "é".repeat(400);
    let mut source = item("671235", &long_title, DumpMediaType::Movie);
    source.description = Some(long_description);

    let asset = searchfeed::asset_from(&source, "en", "hd").expect("long text is truncated");

    let title = &asset.titles[0].value;
    assert_eq!(title.chars().count(), 200);
    assert_eq!(title, &"字".repeat(200));
    assert!(
        title.len() > 200,
        "the value is still valid multi-byte UTF-8"
    );

    let description = &asset.short_descriptions[0].value;
    assert_eq!(description.chars().count(), 200);
    assert_eq!(description, &"é".repeat(200));

    // A plain ASCII title longer than 200 is truncated too, never dropped.
    let ascii = searchfeed::asset_from(
        &item("671236", &"a".repeat(300), DumpMediaType::Movie),
        "en",
        "hd",
    )
    .expect("ascii title is truncated");
    assert_eq!(ascii.titles[0].value, "a".repeat(200));
}

// --- S4: playId/type mapping pinned in both directions --------------------

/// The channel's own rule from `roku/app/components/MainScene.brs`: a
/// `mediaType` containing series/episode/season requests a series (stype 2),
/// anything else a movie (stype 1).
fn channel_stype(media_type: &str) -> u8 {
    let media_type = media_type.to_lowercase();
    if media_type.contains("series")
        || media_type.contains("episode")
        || media_type.contains("season")
    {
        2
    } else {
        1
    }
}

#[test]
fn searchfeed_play_id_and_type_map_both_ways() {
    // Catalogue -> feed: the asset type is exactly the deep-link mediaType, and
    // playId is exactly the MovieBox subject id the channel resolves.
    let cases = [
        (
            DumpMediaType::Movie,
            MediaType::Movie,
            "movie",
            1_u8,
            "671000",
        ),
        (
            DumpMediaType::Series,
            MediaType::Series,
            "series",
            2_u8,
            "672000",
        ),
    ];

    for (dump_type, provider_type, expected, expected_stype, subject_id) in cases {
        let source = item(subject_id, "Mapped", dump_type);
        let asset = searchfeed::asset_from(&source, "en", "hd").expect("asset");

        // Direction 1: provider media type -> feed type -> channel mediaType.
        assert_eq!(asset.asset_type, AssetType::from_media_type(provider_type));
        assert_eq!(asset.asset_type.as_str(), expected);
        assert_eq!(asset.asset_type.to_media_type(), provider_type);

        // The channel resolves contentId verbatim and derives stype from mediaType.
        let channel_content_id = asset.content.play_options[0].play_id.clone();
        assert_eq!(channel_content_id, subject_id);
        assert_eq!(channel_stype(asset.asset_type.as_str()), expected_stype);

        // Direction 2: feed type string -> channel interpretation -> provider type.
        let parsed = AssetType::parse(asset.asset_type.as_str()).expect("known type");
        assert_eq!(parsed, asset.asset_type);
        assert_eq!(channel_stype(asset.asset_type.as_str()), expected_stype);
        assert_eq!(
            parsed.to_media_type(),
            if channel_stype(asset.asset_type.as_str()) == 2 {
                MediaType::Series
            } else {
                MediaType::Movie
            }
        );
        // A movie mediaType must never be read as a series by the channel's rule.
        assert_eq!(channel_stype("movie"), 1);
        assert_eq!(channel_stype("series"), 2);
    }
}

// --- S5: genres come from the closed enum, never a free-form string -------

#[test]
fn searchfeed_genres_are_closed_enum_values() {
    let catalog = searchfeed::genre_catalog();

    assert!(
        catalog.roku_genres.len() > 200,
        "the genre enum must be the full Roku list, got {}",
        catalog.roku_genres.len()
    );
    let mut unique = catalog.roku_genres.clone();
    unique.sort();
    unique.dedup();
    assert_eq!(
        unique.len(),
        catalog.roku_genres.len(),
        "the closed genre enum must not repeat values"
    );

    for (tag, mapped) in &catalog.mapping {
        assert!(
            !mapped.is_empty(),
            "catalogue tag {tag:?} maps to no Roku genre"
        );
        for genre in mapped {
            assert!(
                catalog.roku_genres.contains(genre),
                "catalogue tag {tag:?} maps to {genre:?}, which is outside rokuGenres"
            );
        }
    }

    for genre in &catalog.fallback {
        assert!(
            catalog.roku_genres.contains(genre),
            "fallback genre {genre:?} is outside rokuGenres"
        );
    }

    // A mapped tag becomes its enum value(s), case-insensitively.
    assert_eq!(
        searchfeed::map_genres(Some("Sci-Fi")),
        vec!["science fiction".to_string()]
    );
    assert_eq!(
        searchfeed::map_genres(Some("kids")),
        vec!["children".to_string()]
    );
    // An unmapped tag falls back to the closed fallback list, not a free string.
    assert_eq!(searchfeed::map_genres(Some("telenovela")), catalog.fallback);
    // A missing tag does the same as an unmapped one.
    assert_eq!(searchfeed::map_genres(None), catalog.fallback);
}

#[test]
fn searchfeed_advisory_ratings_are_allowed_per_source() {
    let catalog = searchfeed::rating_catalog();

    for (raw, entry) in &catalog.mapping {
        let allowed = catalog
            .sources
            .get(&entry.source)
            .unwrap_or_else(|| panic!("rating {raw:?} uses unknown source {:?}", entry.source));
        assert!(
            allowed.contains(&entry.value),
            "rating {raw:?} maps to {:?} {:?}, which that source does not allow",
            entry.source,
            entry.value
        );
    }
    let default = &catalog.default;
    assert!(
        catalog
            .sources
            .get(&default.source)
            .is_some_and(|values| values.contains(&default.value)),
        "the default rating {:?} {:?} is not allowed by its source",
        default.source,
        default.value
    );

    assert_eq!(searchfeed::map_rating(Some("pg-13")).value, "PG-13");
    assert_eq!(searchfeed::map_rating(Some("TV-MA")).source, "USA_PR");
    let expected = AdvisoryRating {
        source: default.source.clone(),
        value: default.value.clone(),
    };
    assert_eq!(searchfeed::map_rating(Some("nonsense")), expected);
    assert_eq!(searchfeed::map_rating(None), expected);
}

// --- S6: drop reasons, counts, and the exit-code rule ---------------------

#[test]
fn searchfeed_each_drop_reason_is_counted() {
    let good = item("good-1", "Good", DumpMediaType::Movie);

    let mut no_title = item("no-title", "placeholder", DumpMediaType::Movie);
    no_title.title = "   ".into();

    let mut no_poster = item("no-poster", "No poster", DumpMediaType::Movie);
    no_poster.poster_url = None;

    let mut no_year = item("no-year", "No year", DumpMediaType::Movie);
    no_year.year = Some("not-a-year".into());

    let long_id = item(&"i".repeat(51), "Long id", DumpMediaType::Movie);
    let empty_id = item("", "Empty id", DumpMediaType::Movie);
    let first_dup = item("dup-1", "First duplicate", DumpMediaType::Movie);
    let second_dup = item("dup-1", "Second duplicate", DumpMediaType::Movie);

    let items = vec![
        good, no_title, no_poster, no_year, long_id, empty_id, first_dup, second_dup,
    ];

    let (assets, report) = searchfeed::build_feed(items, "en", "hd");

    assert_eq!(
        assets.len(),
        2,
        "the good item and the first duplicate emit"
    );
    assert_eq!(report.emitted, 2);
    assert_eq!(report.dropped(DropReason::MissingTitle), 1);
    assert_eq!(report.dropped(DropReason::MissingPoster), 1);
    assert_eq!(report.dropped(DropReason::MissingYear), 1);
    assert_eq!(report.dropped(DropReason::IdTooLong), 1);
    assert_eq!(report.dropped(DropReason::MissingId), 1);
    assert_eq!(report.dropped(DropReason::DuplicateId), 1);
    assert_eq!(report.total_dropped(), 6);
    assert_eq!(assets[0].id, "good-1");
    assert_eq!(assets[1].id, "dup-1");
    assert_eq!(assets[1].titles[0].value, "First duplicate");

    // Titles whose live details fetch failed are dropped for their own reason.
    let mut live_report = Report::default();
    live_report.record_n(DropReason::DetailsUnavailable, 3);
    assert_eq!(live_report.dropped(DropReason::DetailsUnavailable), 3);
    assert_eq!(live_report.total_dropped(), 3);
}

#[test]
fn searchfeed_zero_assets_exit_one_and_one_asset_exits_zero() {
    let config = config(&[]);

    let (empty_assets, empty_report) = searchfeed::build_feed(Vec::new(), "en", "hd");
    assert!(empty_assets.is_empty());
    assert_eq!(empty_report.emitted, 0);

    let nothing = searchfeed::run(Vec::new(), &config).expect("run succeeds");
    assert_eq!(nothing.exit_code, 1, "no assets means exit code 1");
    assert!(nothing.feed.assets.is_empty());

    let mut first_without_poster = item("no-poster", "No poster", DumpMediaType::Movie);
    first_without_poster.poster_url = None;
    let mut second_without_poster = item("no-poster-2", "No poster 2", DumpMediaType::Movie);
    second_without_poster.poster_url = Some("   ".into());

    let all_dropped = searchfeed::run(vec![first_without_poster, second_without_poster], &config)
        .expect("run succeeds");
    assert_eq!(all_dropped.exit_code, 1);
    assert_eq!(
        all_dropped.report.dropped(DropReason::MissingPoster),
        2,
        "every unemittable item is reported, not silently skipped"
    );

    let emitted = searchfeed::run(vec![item("671001", "One", DumpMediaType::Movie)], &config)
        .expect("run succeeds");
    assert_eq!(emitted.exit_code, 0, "one asset means exit code 0");
    assert_eq!(emitted.report.emitted, 1);
}

// --- S1: the emitted document is a Roku Search feed root ------------------

#[test]
fn searchfeed_emitted_json_parses_back_into_a_roku_root() {
    // Default quality (no --quality flag) is hd.
    let config = config(&[]);
    assert_eq!(config.quality, "hd");

    let movie = item("671002", "Feed Movie", DumpMediaType::Movie);
    let series = item("672002", "Feed Series", DumpMediaType::Series);

    let result = searchfeed::run(vec![movie, series], &config).expect("feed is generated");
    assert_eq!(result.exit_code, 0);

    let root: serde_json::Value = serde_json::from_str(&result.json).expect("feed JSON parses");
    assert_eq!(root["version"], "1");
    assert_eq!(root["defaultLanguage"], "en");
    assert_eq!(
        root["defaultAvailabilityCountries"],
        serde_json::json!(["us", "mx"])
    );
    let assets = root["assets"].as_array().expect("assets array");
    assert_eq!(assets.len(), 2);

    for asset in assets {
        assert!(asset["id"].as_str().is_some());
        assert!(matches!(asset["type"].as_str(), Some("movie" | "series")));
        assert_eq!(asset["titles"][0]["languages"], serde_json::json!(["en"]));
        assert!(asset["shortDescriptions"][0]["value"].as_str().is_some());
        assert!(asset["genres"].as_array().is_some());
        assert!(asset["advisoryRatings"][0]["source"].as_str().is_some());
        assert_eq!(asset["images"][0]["type"], "main");
        assert!(asset["releaseYear"].as_i64().is_some());
        assert_eq!(asset["content"]["playOptions"][0]["license"], "free");
        assert_eq!(asset["content"]["playOptions"][0]["quality"], "hd");
        assert!(
            asset["content"]["playOptions"][0]["playId"]
                .as_str()
                .is_some()
        );
    }

    let typed: Feed = serde_json::from_str(&result.json).expect("typed root parses");
    assert_eq!(typed, result.feed);
    assert_eq!(typed.assets[0].asset_type, AssetType::Movie);
    assert_eq!(typed.assets[1].asset_type, AssetType::Series);
}
