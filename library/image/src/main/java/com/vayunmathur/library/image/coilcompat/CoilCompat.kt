package com.vayunmathur.library.image.coilcompat

// This file provides minimal Coil-compat types that some old code referenced via
// fully-qualified `coil.xxx`. After migration those imports are removed, but keeping
// shims eases transition if any file still uses `coil.size.Size` etc via alias.
// No runtime behavior – just typealiases to our own types.

// Legacy: import coil.size.Size -> now com.vayunmathur.library.image.Size but we expose via
// replacement file that search-and-replace will handle. This file intentionally left light.
