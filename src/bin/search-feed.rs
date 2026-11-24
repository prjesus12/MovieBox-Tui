//! `search-feed`: build the Roku Search feed for the MovieBox catalogue.
//!
//! Spec S1: one command produces the feed file, and playback code never calls
//! it. Spec S2: `--language` and `--countries` are required. Spec S6: a title
//! that cannot satisfy the schema is dropped and counted, and the exit code is
//! 0 only when at least one asset was emitted.

use std::path::Path;

use moviebox_tui::providers::models::ProviderKind;
use moviebox_tui::searchfeed::{self, DropReason, DumpItem};
use moviebox_tui::service::MovieBoxService;

#[tokio::main]
async fn main() {
    let argv: Vec<String> = std::env::args().skip(1).collect();
    let code = match run(&argv).await {
        Ok(code) => code,
        Err(error) => {
            eprintln!("search-feed: {error}");
            2
        }
    };
    std::process::exit(code);
}

async fn run(argv: &[String]) -> Result<i32, String> {
    let raw = searchfeed::parse_args(argv)?;
    if raw.help {
        println!("{}", searchfeed::HELP);
        return Ok(0);
    }
    let config = searchfeed::resolve(raw)?;

    let (items, unavailable) = match config.input.as_deref() {
        Some(path) => (load_dump(path)?, 0),
        None => fetch_live().await?,
    };

    let mut result = searchfeed::run(items, &config)?;
    result
        .report
        .record_n(DropReason::DetailsUnavailable, unavailable);

    if result.exit_code != 0 {
        // Spec S6: no assets means the command failed, and an empty feed must
        // not overwrite a good one.
        eprintln!(
            "search-feed: no assets emitted; nothing written\nsearch-feed: {}",
            result.report
        );
        return Ok(result.exit_code);
    }

    match config.output.as_deref() {
        Some(path) => std::fs::write(path, format!("{}\n", result.json))
            .map_err(|error| format!("cannot write {}: {error}", path.display()))?,
        None => println!("{}", result.json),
    }
    eprintln!("search-feed: {}", result.report);
    Ok(result.exit_code)
}

fn load_dump(path: &Path) -> Result<Vec<DumpItem>, String> {
    let text = std::fs::read_to_string(path)
        .map_err(|error| format!("cannot read {}: {error}", path.display()))?;
    searchfeed::parse_dump(&text).map_err(|error| format!("{}: {error}", path.display()))
}

/// Walk the movies and series homepage tabs, then fetch details for each row.
///
/// Rows whose details cannot be fetched are skipped and counted, never emitted
/// half-filled.
async fn fetch_live() -> Result<(Vec<DumpItem>, usize), String> {
    let service = MovieBoxService::new();
    let mut items = Vec::new();
    let mut unavailable = 0usize;

    for tab in searchfeed::live_tabs() {
        let (catalog, _metrics) = service
            .homepage(tab, 1)
            .await
            .map_err(|error| format!("cannot fetch MovieBox tab {tab}: {error}"))?;
        for entry in catalog {
            match service
                .details_typed(ProviderKind::MovieBox, &entry.id.value)
                .await
            {
                Ok(details) => items.push(searchfeed::dump_from_details(&entry, &details)),
                Err(_) => unavailable += 1,
            }
        }
    }

    Ok((items, unavailable))
}
