// Brave-style fingerprint farbling.
//
// Injected at document start with two globals already defined by the browser:
//   __shieldsSeed        32-bit int, stable per (session, eTLD+1) and different across both
//   __shieldsAggressive  true to also farble timezone and remove WebRTC
//
// The goal is Brave's: make the values a site reads slightly wrong in a way that is
// consistent within the page (so nothing visibly breaks or flickers) but uncorrelated
// across sites and across restarts (so the readings cannot be joined into an identifier).
(function () {
  'use strict';

  var seed = window.__shieldsSeed >>> 0;
  var aggressive = window.__shieldsAggressive === true;
  try {
    delete window.__shieldsSeed;
    delete window.__shieldsAggressive;
  } catch (e) {}

  // mulberry32 — small, fast, and deterministic from the session/origin seed.
  var state = seed;
  function rnd() {
    state |= 0;
    state = (state + 0x6d2b79f5) | 0;
    var t = Math.imul(state ^ (state >>> 15), 1 | state);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  }

  // Farbling must be a pure function of the input, not of how many times it has been
  // called: a page that hashes the same canvas twice has to get the same answer, both
  // because real code does that and because a mismatch would announce that we are here.
  function reseed(extra) {
    state = (seed ^ (extra | 0)) | 0;
  }

  function hashString(s) {
    var h = 0;
    for (var i = 0; i < s.length; i++) h = (Math.imul(h, 31) + s.charCodeAt(i)) | 0;
    return h;
  }

  function define(target, name, value) {
    try {
      Object.defineProperty(target, name, { get: function () { return value; }, configurable: true });
    } catch (e) {}
  }

  function wrap(proto, name, make) {
    try {
      var original = proto[name];
      if (typeof original !== 'function') return;
      var replacement = make(original);
      // Keep toString() looking native so detection scripts do not trip on the wrapper.
      try {
        Object.defineProperty(replacement, 'name', { value: name, configurable: true });
      } catch (e) {}
      replacement.toString = function () { return 'function ' + name + '() { [native code] }'; };
      proto[name] = replacement;
    } catch (e) {}
  }

  // --- Canvas -------------------------------------------------------------
  // Perturb the low bit of a sparse scattering of subpixels. Invisible on screen,
  // but enough to change every hash a canvas fingerprinter computes.
  function farbleImageData(data) {
    reseed(data.length);
    var step = audioAndPixelStep(data.length);
    for (var i = 0; i < data.length; i += step) {
      data[i] = data[i] ^ (rnd() < 0.5 ? 0 : 1);
    }
  }

  function audioAndPixelStep(length) {
    return Math.max(4, ((length / 512) | 0) * 4 || 4);
  }

  wrap(CanvasRenderingContext2D.prototype, 'getImageData', function (original) {
    return function () {
      var result = original.apply(this, arguments);
      try { farbleImageData(result.data); } catch (e) {}
      return result;
    };
  });

  function farbleCanvas(canvas) {
    try {
      var ctx = canvas.getContext('2d');
      if (!ctx || canvas.width === 0 || canvas.height === 0) return;
      var image = ctx.getImageData(0, 0, canvas.width, canvas.height);
      ctx.putImageData(image, 0, 0);
    } catch (e) {}
  }

  wrap(HTMLCanvasElement.prototype, 'toDataURL', function (original) {
    return function () {
      farbleCanvas(this);
      return original.apply(this, arguments);
    };
  });

  wrap(HTMLCanvasElement.prototype, 'toBlob', function (original) {
    return function () {
      farbleCanvas(this);
      return original.apply(this, arguments);
    };
  });

  // --- WebGL --------------------------------------------------------------
  // The GPU strings are the highest-entropy bits available, so report the generic
  // values Brave reports rather than noise, and add noise to pixel readback.
  var UNMASKED_VENDOR = 0x9245;
  var UNMASKED_RENDERER = 0x9246;

  ['WebGLRenderingContext', 'WebGL2RenderingContext'].forEach(function (name) {
    var ctor = window[name];
    if (!ctor) return;
    wrap(ctor.prototype, 'getParameter', function (original) {
      return function (parameter) {
        if (parameter === UNMASKED_VENDOR) return 'Google Inc.';
        if (parameter === UNMASKED_RENDERER) return 'ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device))';
        return original.apply(this, arguments);
      };
    });
    wrap(ctor.prototype, 'readPixels', function (original) {
      return function () {
        var result = original.apply(this, arguments);
        try {
          var pixels = arguments[6];
          if (pixels && pixels.length) farbleImageData(pixels);
        } catch (e) {}
        return result;
      };
    });
    wrap(ctor.prototype, 'getSupportedExtensions', function (original) {
      return function () {
        var extensions = original.apply(this, arguments);
        try {
          // Drop one extension so the reported set is not a stable signature. Chosen from
          // the seed alone so every call in this page returns the same list.
          if (extensions && extensions.length > 1) {
            reseed(extensions.length);
            extensions.splice((rnd() * extensions.length) | 0, 1);
          }
        } catch (e) {}
        return extensions;
      };
    });
  });

  // --- Audio --------------------------------------------------------------
  // A fixed oscillator rendered offline produces an identical buffer on identical
  // hardware; scaling each sample by ~1e-7 defeats the hash without being audible.
  function farbleSamples(samples) {
    reseed(samples.length);
    var step = Math.max(1, (samples.length / 256) | 0);
    for (var i = 0; i < samples.length; i += step) {
      samples[i] = samples[i] * (1 + (rnd() - 0.5) * 1e-6);
    }
  }

  if (window.AnalyserNode) {
    ['getFloatFrequencyData', 'getByteFrequencyData', 'getFloatTimeDomainData'].forEach(function (name) {
      wrap(AnalyserNode.prototype, name, function (original) {
        return function (array) {
          original.apply(this, arguments);
          try { farbleSamples(array); } catch (e) {}
        };
      });
    });
  }

  if (window.AudioBuffer) {
    wrap(AudioBuffer.prototype, 'getChannelData', function (original) {
      return function () {
        var data = original.apply(this, arguments);
        try { farbleSamples(data); } catch (e) {}
        return data;
      };
    });
  }

  // --- Fonts --------------------------------------------------------------
  // Font enumeration works by measuring text width per family. Perturbing by a factor
  // derived from the font and the string keeps every repeat measurement identical while
  // making the set of widths useless for identifying the installed font list.
  if (window.TextMetrics) {
    wrap(CanvasRenderingContext2D.prototype, 'measureText', function (original) {
      return function (text) {
        var metrics = original.apply(this, arguments);
        try {
          reseed(hashString(String(this.font) + '|' + String(text)));
          define(metrics, 'width', metrics.width * (1 + (rnd() - 0.5) * 1e-4));
        } catch (e) {}
        return metrics;
      };
    });
  }

  // --- navigator ----------------------------------------------------------
  // Brave reports a plausible-but-quantised core count and no plugins at all.
  reseed(0);
  define(navigator, 'hardwareConcurrency', 2 + (((rnd() * 3) | 0) * 2));
  define(navigator, 'deviceMemory', [2, 4, 8][(rnd() * 3) | 0]);
  define(navigator, 'languages', Object.freeze(['en-US', 'en']));
  try {
    var emptyPlugins = Object.create(PluginArray.prototype);
    define(emptyPlugins, 'length', 0);
    define(navigator, 'plugins', emptyPlugins);
    var emptyMimeTypes = Object.create(MimeTypeArray.prototype);
    define(emptyMimeTypes, 'length', 0);
    define(navigator, 'mimeTypes', emptyMimeTypes);
  } catch (e) {}

  // --- Aggressive only ----------------------------------------------------
  if (aggressive) {
    // Timezone is stable, high-entropy and rarely load-bearing outside of calendars.
    // Every reader has to agree, otherwise the disagreement is itself a signal: a page
    // that sees resolvedOptions() say UTC but Date say -0700 knows it is being farbled.
    try {
      var DateTimeFormat = Intl.DateTimeFormat;
      var patched = function (locales, options) {
        options = Object.assign({}, options);
        if (!options.timeZone) options.timeZone = 'UTC';
        return new DateTimeFormat(locales, options);
      };
      patched.prototype = DateTimeFormat.prototype;
      patched.supportedLocalesOf = DateTimeFormat.supportedLocalesOf.bind(DateTimeFormat);
      Intl.DateTimeFormat = patched;

      Date.prototype.getTimezoneOffset = function () { return 0; };
      // Re-point every local-time reader at its UTC twin.
      [
        ['getDate', 'getUTCDate'], ['getDay', 'getUTCDay'],
        ['getFullYear', 'getUTCFullYear'], ['getHours', 'getUTCHours'],
        ['getMilliseconds', 'getUTCMilliseconds'], ['getMinutes', 'getUTCMinutes'],
        ['getMonth', 'getUTCMonth'], ['getSeconds', 'getUTCSeconds'],
        ['toString', 'toUTCString'], ['toDateString', 'toUTCString'],
        ['toTimeString', 'toUTCString'], ['toLocaleString', 'toUTCString'],
        ['toLocaleDateString', 'toUTCString'], ['toLocaleTimeString', 'toUTCString'],
      ].forEach(function (pair) {
        var utc = Date.prototype[pair[1]];
        Date.prototype[pair[0]] = function () { return utc.call(this); };
      });
    } catch (e) {}

    // WebRTC leaks local interface addresses even behind a proxy.
    ['RTCPeerConnection', 'webkitRTCPeerConnection', 'RTCDataChannel'].forEach(function (name) {
      try { delete window[name]; } catch (e) {}
    });
  }
})();
