fn main() {
    let bytes = std::fs::read("trainingdata/s16-mobile/game-01.png").unwrap();
    let r = warden::reader::decode_png_raw(&bytes).unwrap();
    let (w, data) = (r.w, r.data);
    // width-scaled: k = 2800/1918; days rect (800,0,320,60)
    let k = w as f32 / 1918.0;
    let (x, y, cw, ch) = ((800.0 * k) as usize, 0usize, (320.0 * k) as usize, (60.0 * k) as usize);
    let mut px = Vec::with_capacity(cw * ch * 4);
    for yy in y..y + ch {
        let s = (yy * w + x) * 4;
        px.extend_from_slice(&data[s..s + cw * 4]);
    }
    let mut out = Vec::new();
    {
        let mut e = png::Encoder::new(&mut out, cw as u32, ch as u32);
        e.set_color(png::ColorType::Rgba);
        e.set_depth(png::BitDepth::Eight);
        let mut wr = e.write_header().unwrap();
        wr.write_image_data(&px).unwrap();
        wr.finish().unwrap();
    }
    std::fs::write("/tmp/days1x.png", &out).unwrap();
    println!("crop {cw}x{ch} at {x},{y}");
}
