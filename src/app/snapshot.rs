//! The session's state as the mobile client's UI needs it: one JSON object,
//! pulled a few times a second through the JNI layer (see `src/jni.rs`),
//! rather than a call per field. The desktop window reads the same
//! [`Session`] fields directly; this is how the same state crosses to Kotlin.
//!
//! The shape is the client's contract — `data/SnapshotModels.kt` on the
//! Android side — so a field is added or renamed here and there together.

use super::session::Session;
use crate::engine::data::data;
use crate::reader::model::WarningKind;
use serde_json::{Value, json};

/// The warning kinds as the client names them.
fn kind_name(kind: WarningKind) -> &'static str {
    match kind {
        WarningKind::Read => "read",
        WarningKind::HeldToolsUnknown => "held",
        WarningKind::Note => "note",
    }
}

/// Everything the mobile UI shows of `s`, as one JSON object:
///
/// - `scanning`, `solving`, `rounds`, `rollouts` — what the session is doing;
/// - `reading` — the board read (`model`), the screen's label, the days left
///   and the warnings to check, or null;
/// - `advice` — the move to play (`label`, `why`, `option` into `ranking`),
///   or null;
/// - `ranking` — the solved options, best first;
/// - `mark` — where to click in the game frame px (`x`, `y`, half `w`, half
///   `h`), or null;
/// - `tools`, `theme`, `themeName`, `daysLeft` — the run as the player set it;
/// - `problems`, `missed`, `runOver` — why nothing is on show, when so.
///
/// `advice` repeats a row of `ranking` when the playouts settled it; the
/// client draws the sizing itself.
pub fn take(s: &Session) -> Value {
    let reading = s.reading.as_ref().map(|r| {
        let warnings = r
            .warnings
            .iter()
            .map(|w| json!({ "cell": w.cell, "kind": kind_name(w.kind), "text": w.text }))
            .collect::<Vec<_>>();
        json!({
            "model": r.model,
            "screen": r.screen.label(),
            "daysLeft": r.days_left,
            "warnings": warnings,
        })
    });
    let advice = s.advice().map(|a| {
        json!({
            "label": a.label,
            "why": s.why_shown(&a),
            "option": a.option,
        })
    });
    let ranking = s
        .ranking
        .iter()
        .enumerate()
        .map(|(i, o)| {
            json!({
                "rank": i + 1,
                "label": o.label,
                "mean": o.mean,
                "se": o.se,
                "gapSe": if o.gap_se.is_finite() { json!(o.gap_se) } else { Value::Null },
                "n": o.n,
                "best": o.best,
                "policy": o.policy,
            })
        })
        .collect::<Vec<_>>();
    let mark = s.mark().map(|(x, y, w, h)| json!({ "x": x, "y": y, "w": w, "h": h }));
    let tools = s.tools.iter().map(|t| t.key()).collect::<Vec<_>>();
    json!({
        "scanning": s.scanning(),
        "solving": s.solving,
        "rounds": s.rounds,
        "rollouts": s.rollouts,
        "reading": reading,
        "advice": advice,
        "ranking": ranking,
        "mark": mark,
        "tools": tools,
        "theme": s.theme,
        "themeName": data().theme_name(s.theme),
        "daysLeft": s.reading.as_ref().and_then(|r| r.days_left),
        "problems": s.problems,
        "missed": s.missed,
        "runOver": s.run_over,
    })
}
