# nanojson (vendored)

Vendored from https://github.com/TeamNewPipe/nanojson at pinned commit `e9d656ddb49a412a5a0a5d5ef20ca7ef09549996` (commit referenced in `gradle/libs.versions.toml` before migration).

## Why vendored
- Previously consumed via JitPack (`com.github.TeamNewPipe:nanojson`) which builds on-demand from GitHub commits — irreproducible risk, SPOF if JitPack sunsets.
- Critical to `youpipe` / `:youpipe:extractor` for YouTube JSON parsing.

## Contents
Files under `src/main/java/com/grack/nanojson/`:
- `JsonObject.java`
- `JsonArray.java`
- `JsonParser.java`
- `JsonWriter.java`
- `JsonBuilder.java`
- `JsonStringWriter.java`
- `JsonAppendableWriter.java`
- `JsonParserException.java`

## License
Apache License 2.0 — Copyright 2011 The nanojson Authors.
See `LICENSE` file. Original project license headers remain in source files.
