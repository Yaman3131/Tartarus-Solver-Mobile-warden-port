//! A calibration probe for phone frames (a screen narrower than 16:9).
//!
//! The PC reader normalises a frame to the 1918 x 1078 reference by its
//! height (k = h / 1078), which suits a 16:9-or-wider frame. A phone in
//! landscape is narrower, so scaling by width (k = w / 1918) keeps the board
//! and the cell ring at their reference size and leaves the frame taller
//! than 1078, with the excess to place vertically.
//!
//!   cargo run --profile fast-test --example mobile_probe -- <frame.png>
//!
//! Prints, for the height-normalised layout the reader uses now and for
//! width-normalised crops at every vertical offset in steps of 8 px, the
//! mean and minimum confidence of the cell reads — the offset that scores
//! best is the vertical placement a narrow-frame layout should use.

use std::path::Path;
use warden::reader::{REF_H, REF_W, Rgba, decode_png_raw, layout, scene};

fn score(img: &Rgba) -> (f32, f32, u8) {
    let s = scene::read_scene(img);
    let confs: Vec<f32> = s.cells.iter().map(|c| c.beast_conf).collect();
    let mean = confs.iter().sum::<f32>() / confs.len().max(1) as f32;
    let min = confs.iter().copied().fold(f32::MAX, f32::min);
    let warden = s.warden.unwrap_or(0);
    (mean, min, warden)
}

fn main() {
    let Some(file) = std::env::args().nth(1) else {
        eprintln!("usage: mobile_probe <frame.png>");
        std::process::exit(1);
    };
    let bytes = std::fs::read(&file).expect("frame");
    let img = decode_png_raw(&bytes).expect("png");
    println!("{file}: {}x{}", img.w, img.h);

    // The reader as it stands: height-normalised zone layout.
    let (board, _, u) = layout::lay_out_scaled(&img, None);
    let (mean, min, warden) = score(&board);
    println!("height-normalised (u {u}): mean beast conf {mean:.3}, min {min:.3}, warden {warden}");

    // Width-normalised: the frame at reference width, then a 1078-tall window.
    let h = (img.h as f32 * REF_W as f32 / img.w as f32).round() as usize;
    let wide = img.resize(REF_W, h);
    println!("width-normalised: {REF_W}x{h} (k {:.3})", img.w as f32 / REF_W as f32);
    let centre = (h - REF_H) / 2;
    let mut best = (f32::MIN, 0usize);
    for y in centre.saturating_sub(12)..=(centre + 12).min(h - REF_H) {
        let win = wide.crop(warden::reader::Rect::new(0, y as i32, REF_W as i32, REF_H as i32));
        let (mean, min, warden) = score(&win);
        println!("  offset {y:>4}: mean {mean:.3}, min {min:.3}, warden {warden}");
        if mean > best.0 {
            best = (mean, y);
        }
    }
    println!("best offset {} (mean {:.3}), centred is {centre}", best.1, best.0);
    let _ = Path::new(&file);
}
