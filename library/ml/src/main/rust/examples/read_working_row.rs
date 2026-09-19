//! Read one working-table row via the Rust reader and print it, for comparison
//! against the Python decode of the same tensor.
use std::path::PathBuf;
use modelrunner::nets::gemma4;
use modelrunner::weights::{graph, Weights};

fn main() {
    let mut args = std::env::args().skip(1);
    let (Some(embed), Some(tok)) = (args.next().map(PathBuf::from), args.next())
    else {
        println!("usage: read_working_row <embed.maml> <token>");
        return;
    };
    let token: u32 = tok.parse().unwrap();
    let bytes = std::fs::read(&embed).unwrap();
    let weights = Weights::parse(&bytes, graph::GEMMA4_EMBED).unwrap();
    let reader = weights.reader();
    match reader.int4_row(
        gemma4::embed::TOKENS,
        gemma4::embed::TOKENS + 1,
        &[gemma4::VOCAB, gemma4::D_MODEL],
        token,
    ) {
        Ok(row) => {
            println!("row {token}: n={}", row.len());
            let absmax = row.iter().map(|x| x.abs()).fold(0.0f32, f32::max);
            let mean: f32 = row.iter().sum::<f32>() / row.len() as f32;
            println!("absmax={absmax:.4} mean={mean:+.5}");
            print!("first 8:");
            for x in row.iter().take(8) {
                print!(" {x:.4}");
            }
            println!();
            // Raw bytes for exact comparison.
            let mut hasher = std::collections::hash_map::DefaultHasher::new();
            use std::hash::{Hash, Hasher};
            for x in row.iter() {
                x.to_bits().hash(&mut hasher);
            }
            println!("fnv-ish {:016x}", hasher.finish());
        }
        Err(why) => println!("read failed: {why}"),
    }
}
