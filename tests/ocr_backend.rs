//! The OCR backend seam: with an engine injected (as the Android client
//! injects ML Kit), `ocr` asks it instead of the platform's, and the lines it
//! returns — boxes in the prepared crop's pixels — come back in frame pixels.
//!
//! A test binary of its own: the backend is process-wide.

use std::sync::Arc;
use warden::reader::Rgba;
use warden::reader::ocr::{self, Line, set_backend};

/// A frame that is not black, so the contrast stretch leaves it alone.
fn frame(w: usize, h: usize) -> Rgba {
    Rgba { w, h, data: [40u8, 80, 120, 255].repeat(w * h) }
}

#[test]
fn injected_backend_reads_and_maps_boxes() {
    assert!(!ocr::has_backend());
    // Every prepared crop reads as one line at (10, 20) of size 30 x 40, in
    // the crop's own pixels.
    let previous = set_backend(Arc::new(|inputs: Vec<Option<Rgba>>| {
        inputs
            .into_iter()
            .map(|input| match input {
                None => Vec::new(),
                Some(_) => vec![("abc".to_string(), [10.0, 20.0, 30.0, 40.0])],
            })
            .collect()
    }));
    assert!(previous.is_none());
    assert!(ocr::has_backend());
    assert_eq!(ocr::language().as_deref(), Some("en"));
    assert!(ocr::problem().is_none());

    // The region is read at 2x, so the crop's box halves back into frame px
    // and shifts by the region's corner.
    let img = frame(1918, 1078);
    let region = warden::reader::Rect::new(100, 200, 300, 50);
    let lines = ocr::ocr(&img, region, 2.0);
    assert_eq!(
        lines,
        vec![Line { text: "abc".to_string(), rect: warden::reader::Rect::new(105, 210, 15, 20) }]
    );

    // An empty region yields no input, so no line.
    assert!(ocr::ocr(&img, warden::reader::Rect::new(5000, 5000, 10, 10), 1.0).is_empty());
}
