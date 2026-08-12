# Google Voice HAR Endpoint Documentation

Generated from runtime browser traffic captured in `C:\Users\Vayun\Downloads\voice.google.com.har`. The capture contains one Google Voice web session loaded at `https://voice.google.com/`, including account/bootstrap requests, inbox/thread listing, contact lookup, realtime signaling setup, telephony websocket setup, and one observed SMS send request.

Analysis artifacts:

- Source HAR (capture 1, bootstrap + SMS): `C:\Users\Vayun\Downloads\voice.google.com.har`
- Source HAR (capture 2, calling + folders + thread actions): `C:\Users\Vayun\Downloads\2voice.google.com.har`
- Reference format: `C:\Users\Vayun\Documents\code\Modern-Apps\whatsapp-documentation.md`
- Generated documentation: `C:\Users\Vayun\Documents\code\Modern-Apps\voice-documentation.md`

Important caveats:

- This is HAR/runtime analysis only. No endpoint was replayed or tested outside the captured browser session.
- The HAR contains authenticated Google session traffic. This document intentionally does not preserve cookies, authorization-like headers, API key values, phone numbers, message body text, Gaia/account IDs, contact names, websocket keys, or opaque request tokens.
- Most Google Voice web APIs in this capture use `application/json+protobuf` with `alt=protojson`. Request and response bodies are positional arrays, so field names are only recoverable when implied by endpoint names, surrounding flow, or visible high-level shape.
- `Exact` below means the host, path, method, status, MIME type, and high-level body shape were observed in the HAR. It does not imply that the endpoint is public or callable without Google authentication/session state.
- Browser preflight `OPTIONS` requests are documented only when they explain a separate RPC surface. They are otherwise omitted from the main endpoint table.

## Capture Summary

| Metric | Observed value |
|---|---|
| HAR creator | `WebInspector 537.36` |
| Page URL | `https://voice.google.com/` |
| Capture start | `2026-08-12 00:54:05 UTC` |
| Total entries | 195 |
| Page count | 1 |
| Core Voice host | `clients6.google.com` |
| Voice ring-group host | `voice.clients6.google.com` |
| Realtime signaling host | `signaler-pa.clients6.google.com` |
| Web telephony host | `web.voice.telephony.goog` |

High-level flow observed in chronological order:

```text
voice.google.com root redirect
  -> voice.google.com/u/0/ web app shell and manifest
  -> Google support services: CSP, OneGoogle, analytics, WAA, People/PeopleStack
  -> Punctual signaling server selection and multi-watch channel setup
  -> Google Voice account, thread, threading-info, SIP, number-transfer, and port-info RPCs
  -> web.voice.telephony.goog websocket upgrade
  -> thread attribute update
  -> contact lookup
  -> api2thread/sendsms
```

## Reverse-Slice Summary

| Endpoint | HAR evidence | Input format | Output format | Purpose / notes |
|---|---|---|---|---|
| `GET https://voice.google.com/` | Observed once with status `302`. | No body. Browser navigation request. | Redirect / empty binary-like HAR content. | Entry point that redirects the browser to the account-scoped web app path. |
| `GET https://voice.google.com/u/0/` | Observed once with status `200`, `text/html`, response size about 155 KB. | No body. Requires browser session cookies. | HTML app shell containing Google Voice web bootstrap resources. | Main Google Voice web application load. |
| `GET https://voice.google.com/u/0/manifest.json` | Observed once with status `200`, `application/manifest+json`. | No body. | Manifest JSON with `name`, `short_name`, `background_color`, `display`, `start_url`, `scope`, `icons`, `theme_color`, `gcm_sender_id`, and `gcm_user_visible_only`. | Progressive web app manifest for Google Voice. |
| `GET https://voice.google.com/generate_204` | Observed three times with status `204`, `text/plain`, empty response. | No body. | Empty 204. | Connectivity/liveness probe used by the web app. |
| `POST https://clients6.google.com/voice/v1/voiceclient/account/get?alt=protojson&key=<redacted>` | Observed twice with status `200`, `application/json+protobuf`. | Positional protojson body shaped like `[null, {}]`; authenticated headers include Google browser/client metadata and session-bearing cookies omitted here. | Large positional protojson array. Sanitized response includes the Google Voice number, account/device/settings structures, forwarding or linked-number records, locale/country-like strings, and nested capability/config arrays. | Account bootstrap. This is the heaviest Voice-specific response in the capture and appears to seed the logged-in Voice account state. |
| `POST https://clients6.google.com/voice/v1/voiceclient/api2thread/list?alt=protojson&key=<redacted>` | Observed six times with status `200`, `application/json+protobuf`. | Positional protojson body. Largest observed request shape: `[{}, {}, {}, null, null, [null, <number>, <number>, <number>]]`. | Positional protojson array containing thread records. Sanitized records include thread IDs, timestamps/state numbers, message arrays, participant phone-number slots, contact/display slots, and a pagination-like object with `Length`. | Lists inbox/thread data. Multiple calls likely cover initial load, pagination, or category refreshes. |
| `POST https://clients6.google.com/voice/v1/voiceclient/api2thread/search?alt=protojson&key=<redacted>` | Observed twice with status `200`, `application/json+protobuf`. | Positional protojson body shaped like `[{ Length: <number> }, {}, null, null, null, [null, <number>, <number>, <number>]]`; the query value is intentionally not retained. | Positional protojson array of matching thread records with the same general record shape as `api2thread/list`. | Searches conversation threads. The HAR captures the search result shape but not a named schema. |
| `POST https://clients6.google.com/voice/v1/voiceclient/api2thread/sendsms?alt=protojson&key=<redacted>` | Observed once with status `200`, `application/json+protobuf`, shortly after contact lookup activity. | Positional protojson body. Sanitized shape: `[null, null, null, null, { Length: <number> }, { Length: <number> }, ...]`; the recipient, message text, thread/account IDs, and opaque tokens are intentionally not retained. | Positional protojson response shaped like `[null, { Length: <number> }, { Length: <number> }, {}, [<number>, <number>]]`. | Sends an SMS from the web app. This is the clearest captured write/action endpoint. |
| `POST https://clients6.google.com/voice/v1/voiceclient/threadinginfo/get?alt=protojson&key=<redacted>` | Observed five times with status `200`, `application/json+protobuf`. | Empty/null protojson body. | Positional protojson array of repeated triplets shaped like `[<number>, null, <number>]`. | Fetches per-thread or mailbox threading metadata. The exact field names are not recoverable from the HAR alone. |
| `POST https://clients6.google.com/voice/v1/voiceclient/thread/markallread?alt=protojson&key=<redacted>` | Observed once with status `200`, `application/json+protobuf`. | Empty object `{}`. | `null` / empty protojson response. | Marks visible thread set or mailbox as read. No message IDs were preserved in this document. |
| `POST https://clients6.google.com/voice/v1/voiceclient/thread/batchupdateattributes?alt=protojson&key=<redacted>` | Observed once with status `200`, `application/json+protobuf`. | Positional protojson body shaped like `[[<thread-id-like string>, null, null, <number>], [null, null, null, <number>], <number>]`. | Positional protojson response shaped like `[<thread-id-like string>, <number>, <number>, <number>, [<number>, <number>], <number>, ...]`. | Updates thread attributes, likely read/archive/star/category-style metadata. The endpoint name is the strongest evidence; exact attribute enum names are unresolved. |
| `POST https://clients6.google.com/voice/v1/voiceclient/sipregisterinfo/get?alt=protojson&key=<redacted>` | Observed once with status `200`, `application/json+protobuf`. | Positional protojson body shaped like `[{}, { Length: <number> }]`. | Positional protojson response shaped like `[[<phone-number>, <number>], null, null, [<string:56>, <string:24>]]` after sanitization. | Fetches SIP registration information used by the Voice web telephony/calling stack. The long strings are likely opaque SIP/registration credentials or tokens and are not retained. |
| `POST https://clients6.google.com/voice/v1/voiceclient/numbertransfer/list?alt=protojson&key=<redacted>` | Observed once with status `200`, `application/json+protobuf`. | Empty/null protojson body. | Empty/null protojson response. | Checks number transfer state. The account in this capture returned no visible transfer payload. |
| `POST https://clients6.google.com/voice/v1/voiceclient/getnumberportinfo?alt=protojson&key=<redacted>` | Observed once with status `200`, `application/json+protobuf`. | Empty/null protojson body. | Empty/null protojson response. | Checks number porting information. The account in this capture returned no visible porting payload. |
| `POST https://voice.clients6.google.com/$rpc/google.voice.ringgroups.v1.RingGroupsService/ListRingGroups` | Observed once with status `200`, `application/json+protobuf`; matching `OPTIONS` preflight also observed. | Positional protojson body shaped like `[<number>, {}]`; request headers include `x-goog-api-key`, `x-goog-authuser`, and browser metadata. | Empty/null protojson response. | Lists Voice ring groups. No ring groups were returned in this captured session. |
| `POST https://signaler-pa.clients6.google.com/punctual/v1/chooseServer?key=<redacted>` | Observed once with status `200`, `application/json+protobuf`; matching `OPTIONS` preflight also observed. | Positional protojson body with client/session capability arrays, sanitized as `[[null, null, null, [<number>, <number>], null, [null, [null, <number>], ...]], null, null, {}, {}]`. | Positional protojson response with several opaque `Length` objects. | Chooses or initializes a Google Punctual/WebChannel signaling server for realtime updates. |
| `POST https://signaler-pa.clients6.google.com/punctual/multi-watch/channel?...` | Observed once with status `200`, `application/x-www-form-urlencoded`, response `text/plain`; two `OPTIONS` preflights also observed. | Form fields include `count`, `ofs`, and `req0___data__` through `req5___data__`; query parameters include WebChannel-style values such as `CVER`, `VER`, `RID`, `gsessionid`, `t`, `zx`, and redacted `key`. | Short text response, not JSON. Response headers include `x-client-wire-protocol`. | Long-poll/WebChannel subscription for realtime multi-watch updates. Session IDs and channel payloads are intentionally not retained. |
| `GET https://web.voice.telephony.goog/websocket` | Observed once with status `101` websocket upgrade. | WebSocket upgrade headers include `Sec-WebSocket-Key`, `Sec-WebSocket-Protocol`, and `Origin: https://voice.google.com`; values are intentionally not retained. | WebSocket upgrade response with `Sec-WebSocket-Accept` and selected protocol. | Browser telephony websocket used by the Voice calling stack. The HAR does not contain decoded websocket frames. |
| `GET https://clients6.google.com/static/proxy.html?jsh=...&usegapi=...` | Observed once with status `200`, `text/html`. | No body; query parameters `jsh`, `usegapi`. | Small HTML proxy page. | Google API/gapi iframe proxy support used by the web app. |
| `POST https://peoplestack-pa.clients6.google.com/$rpc/peoplestack.PeopleStackAutocompleteService/Autocomplete` | Observed once with status `200`, `application/json+protobuf`; matching `OPTIONS` preflight observed. | Positional protojson body shaped like `[{}]`; search/contact values are not retained. | Large `application/json+protobuf` response, about 85 KB in the HAR. | PeopleStack autocomplete for contact/recipient selection. The payload likely includes contact suggestions and metadata, but names/emails/phone numbers are redacted from this document. |
| `POST https://peoplestack-pa.clients6.google.com/$rpc/peoplestack.PeopleStackAutocompleteService/Lookup` | Observed three times with status `200`, `application/json+protobuf`; matching `OPTIONS` preflights observed. | Positional protojson bodies shaped like `[{}]`, with observed request sizes around 30, 57, and 238 bytes. | Positional protojson responses shaped like `[[[null]]]` after sanitization. | Resolves selected contacts/recipients. Occurs before the captured `sendsms` request. |
| `POST https://peoplestack-pa.clients6.google.com/$rpc/peoplestack.PeopleStackAutocompleteService/Warmup` | Observed once with status `200`, `application/json+protobuf`; matching `OPTIONS` preflight observed. | Positional protojson body shaped like `[{}]`. | Empty/null protojson response. | Warms contact autocomplete data. |
| `POST https://people-pa.clients6.google.com/$rpc/google.internal.people.v2.InternalPeopleService/ListContactGroups` | Observed once with status `200`, `application/json+protobuf`; matching `OPTIONS` preflight observed. | Positional protojson body shaped like `[null]`. | Positional protojson response shaped like `[[[null]]]` after sanitization. | Loads People contact group metadata used by the account/contact picker integration. |
| `POST https://people-pa.clients6.google.com/$rpc/google.internal.people.v2.InternalPeopleService/GetContactGroups` | Observed once with status `200`, `application/json+protobuf`; matching `OPTIONS` preflight observed. | Positional protojson body shaped like `[[<string>]]`; group identifiers are not retained. | Positional protojson response shaped like `[[null]]` after sanitization. | Fetches specific People contact group data. |
| `POST https://waa-pa.clients6.google.com/$rpc/google.internal.waa.v1.Waa/Create` | Observed three times with status `200`, `application/json+protobuf`; matching `OPTIONS` preflights observed. | Protojson body sanitized as `{ Length: <number> }`. | Protojson response shaped like `[<string>]`; response sizes around 26 KB and 32 KB. | Google WAA web app activity/attribution support traffic, not Voice domain data. |
| `POST https://waa-pa.clients6.google.com/$rpc/google.internal.waa.v1.Waa/Ping` | Observed once with status `200`, `application/json+protobuf`; matching `OPTIONS` preflight observed. | Protojson body shaped like `[{ Length: <number> }]`. | Empty/null protojson response. | WAA heartbeat/ping. |
| `POST https://ogads-pa.clients6.google.com/$rpc/google.internal.onegoogle.asyncdata.v1.AsyncDataService/GetAsyncData` | Observed once with status `200`, `application/json+protobuf`; matching `OPTIONS` preflight observed. | Positional protojson body shaped like `[{}]`. | Protojson response shaped like `[null]`. | OneGoogle account/widget async data support traffic. |
| `POST https://csp.withgoogle.com/csp/proto/1c1e2c4c2abc4dce549475d4ab588dbd` | Observed eleven times. | CSP/reporting protobuf-style POSTs. | Empty/small report acknowledgements. | Content security policy or client-side security reporting. Not Voice product API data. |
| `POST https://play.google.com/log` | Observed 28 POSTs plus 17 `OPTIONS` preflights. | Google client logging payloads; values omitted. | Logging acknowledgements. | Telemetry/logging support traffic generated by Google web libraries. |
| `POST https://www.google-analytics.com/g/collect` | Observed four times. | Google Analytics collection payloads. | Analytics collector responses. | Web analytics support traffic. |
| `GET/POST https://www.google.com/recaptcha/enterprise...` and `GET https://www.gstatic.com/recaptcha/...` | Enterprise reCAPTCHA script, anchor, reload, clear, webworker, CSS, and logo requests observed. | Browser script/form traffic. | JavaScript, CSS, image, and reCAPTCHA responses. | Anti-abuse support surface loaded by the Voice web app. |

## Exact Local JSON / Model Shapes Recovered

### Google Voice Web Manifest

`GET https://voice.google.com/u/0/manifest.json` returned a normal JSON manifest with this recovered key shape:

```json
{
  "name": "string",
  "short_name": "string",
  "background_color": "string",
  "display": "string",
  "start_url": "string",
  "scope": "string",
  "icons": [
    {
      "src": "string",
      "sizes": "string",
      "type": "string"
    }
  ],
  "theme_color": "string",
  "gcm_sender_id": "string",
  "gcm_user_visible_only": true
}
```

### Voice Account Bootstrap Shape

`POST https://clients6.google.com/voice/v1/voiceclient/account/get?alt=protojson&key=<redacted>` used `application/json+protobuf`. Sanitized shape excerpt:

```json
[
  "<google-voice-number>",
  null,
  "<account-and-forwarding-settings-array>",
  "<capability-and-locale-settings-array>",
  "<feature/config arrays>",
  "..."
]
```

Observed field categories, inferred from endpoint name and sanitized values:

| Category | Evidence in response |
|---|---|
| Primary Voice number | First array slot sanitized as a phone number. |
| Forwarding / linked numbers | Nested arrays containing phone-number slots and short labels. |
| Device or endpoint records | Repeated arrays with 64-character string slots, numeric type/status fields, and nested timestamp-like arrays. |
| Locale/country/config | Short strings such as two-letter and three-letter codes plus numeric setting arrays. |
| Feature/capability flags | Large nested numeric arrays and repeated config records. |

### Thread List / Search Shape

`api2thread/list` and `api2thread/search` both returned positional thread records. Sanitized representative thread shape:

```json
[
  "<thread-id-or-label>",
  "<numeric-state-or-timestamp>",
  [
    "<message-id-or-token>",
    "<numeric-message-type-or-timestamp>",
    "<phone-number>",
    "<participant/contact-array>",
    "<numeric-state>",
    "<numeric-state>",
    "..."
  ],
  null,
  ["<display-or-participant-slot>", "<display-or-participant-slot>", null, null, null, null, "..."],
  ["<timestamp-or-state-number>", "<timestamp-or-state-number>"],
  "..."
]
```

Observed request controls:

| Endpoint | Sanitized request controls |
|---|---|
| `api2thread/list` | Empty option objects plus a trailing numeric window/page-control array: `[null, <number>, <number>, <number>]`. |
| `api2thread/search` | A `Length` object in the first slot, likely holding the search term in protobuf/string wrapper form; the actual term is not retained. |

### SMS Send Shape

`POST https://clients6.google.com/voice/v1/voiceclient/api2thread/sendsms?alt=protojson&key=<redacted>` is the captured write endpoint. The exact message text and recipient values were removed. Sanitized shape:

```json
{
  "request": [
    null,
    null,
    null,
    null,
    { "Length": "<number>" },
    { "Length": "<number>" },
    "..."
  ],
  "response": [
    null,
    { "Length": "<number>" },
    { "Length": "<number>" },
    {},
    ["<number>", "<number>"]
  ]
}
```

Interpretation from flow context:

| Slot group | Likely meaning |
|---|---|
| Early `null` slots | Optional thread/account/context fields omitted or encoded elsewhere. |
| `Length` wrapper objects | Protojson representation of byte/string fields, likely recipient/message/token-like values. |
| Tail fields | Additional send options, client metadata, or request identifiers. |
| Response wrappers | Server-assigned message/thread identifiers or opaque send result objects. |
| Response numeric pair | Status/timestamp-like metadata. |

### Thread Metadata And Attribute Updates

Two thread state mutation/read surfaces were captured:

| Endpoint | Request shape | Response shape | Notes |
|---|---|---|---|
| `threadinginfo/get` | `null` | Repeated `[<number>, null, <number>]` triplets | Polls or refreshes thread counters/metadata. |
| `thread/markallread` | `{}` | `null` | Empty ack after marking read. |
| `thread/batchupdateattributes` | `[[<thread-id-like string>, null, null, <number>], [null, null, null, <number>], <number>]` | `[<thread-id-like string>, <number>, <number>, <number>, [<number>, <number>], <number>, ...]` | Updates thread attributes; exact enum values unresolved. |

### SIP / Telephony Bootstrap

The capture shows both an HTTP bootstrap and a websocket transport:

```text
sipregisterinfo/get
  -> returns phone-number slot plus opaque registration strings
  -> web.voice.telephony.goog/websocket HTTP 101 upgrade
```

Recovered surfaces:

| Surface | Evidence | Role |
|---|---|---|
| SIP registration info | `POST https://clients6.google.com/voice/v1/voiceclient/sipregisterinfo/get?alt=protojson&key=<redacted>` | Retrieves web telephony registration material. Long strings are treated as secrets and omitted. |
| Telephony websocket | `GET https://web.voice.telephony.goog/websocket`, status `101` | Upgrades to a websocket for web calling/signaling/media-control traffic. No frames were decoded in the HAR. |
| Ring groups | `POST https://voice.clients6.google.com/$rpc/google.voice.ringgroups.v1.RingGroupsService/ListRingGroups` | Checks account ring group configuration; empty in this capture. |

## Messaging And Contact Flow

The captured SMS send is preceded by contact and thread activity. Best-supported reconstruction:

```text
PeopleStack warmup/autocomplete/lookup
  -> Google Voice api2thread/search or recipient/thread resolution
  -> Google Voice api2thread/sendsms
  -> Google logging/analytics events
```

Contact services involved:

| Service | Endpoint | Role |
|---|---|---|
| PeopleStack autocomplete | `/$rpc/peoplestack.PeopleStackAutocompleteService/Autocomplete` | Produces recipient suggestions from contacts. |
| PeopleStack lookup | `/$rpc/peoplestack.PeopleStackAutocompleteService/Lookup` | Resolves selected recipient/contact records. |
| PeopleStack warmup | `/$rpc/peoplestack.PeopleStackAutocompleteService/Warmup` | Primes autocomplete service state. |
| People contact groups | `/$rpc/google.internal.people.v2.InternalPeopleService/ListContactGroups` and `GetContactGroups` | Loads Google People group metadata used by recipient/contact UI. |

The actual contact names, phone numbers, account identifiers, and message text are present in the HAR but intentionally excluded from this documentation.

## Authentication, Headers, And Request Conventions

The core Voice RPCs share a common browser/API pattern:

| Field | Observed pattern |
|---|---|
| Method | `POST` for RPCs; `GET` for app shell, manifest, probes, assets, and websocket upgrade. |
| Body MIME | `application/json+protobuf` for Google RPC/Voice calls. |
| Query parameters | Voice RPCs use `alt=protojson` and `key=<redacted>`. Punctual/WebChannel calls include session/channel parameters such as `RID`, `VER`, `CVER`, `gsessionid`, `t`, and `zx`. |
| Browser metadata headers | `x-client-data`, `x-client-version`, `x-clientdetails`, `x-browser-channel`, `x-browser-validation`, `x-browser-year`, `x-goog-authuser`, `x-requested-with`, `x-origin`, and `x-referer` appear on Voice RPCs. |
| Auth/session material | Cookies and opaque Google account/session values are required in the capture but are not reproduced here. |
| Response MIME | Mostly `application/json+protobuf` for RPCs; `text/html` for app shell/proxy/preflights; `text/plain` for WebChannel; `x-unknown` for websocket upgrade in HAR. |

## Public Web / Static Asset Surfaces

These are observed supporting resources rather than private Google Voice backend APIs.

| Endpoint / host | Input format | Output format | Purpose |
|---|---|---|---|
| `https://www.gstatic.com/_/voice/_/js/...` | GET static JS URL. | JavaScript bundle. | Google Voice web app code. |
| `https://www.gstatic.com/_/voice/_/ss/...` | GET static CSS URL. | CSS stylesheet. | Google Voice web app styling. |
| `https://www.gstatic.com/voice-fe/audio/*.mp3` | GET static audio file. | MP3 audio. | DTMF, call-ended, silent audio, and calling tone assets. |
| `https://www.gstatic.com/birdsong/tones/...` | GET static audio file. | MP3 audio. | Ringing and busy tones. |
| `https://www.gstatic.com/voice-fe/icons/...` and product logo paths | GET static image. | PNG/WebP-like image assets. | Voice favicon and app branding. |
| `https://fonts.googleapis.com/...` and `https://fonts.gstatic.com/...` | GET CSS/font files. | CSS and WOFF2. | Google font loading. |
| `https://apis.google.com/js/api.js` and related `apis.google.com/_/scs/...` URLs | GET JavaScript. | Google API JS. | gapi/client/proxy support. |
| `https://payments.google.com/payments/.../integrator.js` | GET JavaScript. | Payments integrator script. | Payments support script loaded by Google web shell; no Voice payment API call was observed. |
| `https://ogs.google.com/u/0/widget/app` | GET widget URL. | OneGoogle widget content. | Google app/account switcher support. |
| `https://lh3.google.com/...` and `https://lh3.googleusercontent.com/...` | GET image URLs. | Profile/contact images. | Account/contact avatars; exact URLs are not retained. |

## Second Capture: Calling, Folders, And Thread Actions

Capture 2 (`C:\Users\Vayun\Downloads\2voice.google.com.har`, 448 entries, started `2026-08-12 03:17:01 UTC`) exercised placing/receiving calls, navigating folders, searching, and running conversation actions. It confirms several endpoints and flows that capture 1 only showed as static code.

### Newly Confirmed At Runtime

| Endpoint / surface | Evidence in capture 2 | Notes |
|---|---|---|
| `wss://web.voice.telephony.goog/websocket` SIP frames | 48 websocket frames (26 send, 22 receive) | Full calling signaling; see the calling section below. Capture 1 only showed the `101` upgrade. |
| `voice/v1/voiceclient/inboundcallrule/list` | 1 POST, status `200` | Custom call forwarding rules were listed. Create/update/delete/move still not observed. |
| `signaler-pa.clients6.google.com/punctual/v1/refreshCreds` | 1 POST | Realtime signaling credential refresh, not seen in capture 1. |
| `voice/v1/voiceclient/thread/batchupdateattributes` | 8 POSTs (vs 1 in capture 1) | Confirms read/archive/spam/block toggles funnel through this one endpoint. |
| `voice/v1/voiceclient/api2thread/list` | 21 POSTs across folder enums 1–5 | Confirms folder/tab navigation uses a folder integer in the first slot. |
| `voice/v1/voiceclient/api2thread/search` | 12 POSTs with incremental query strings | Confirms search-as-you-type. |

### Folder Navigation Shape (`api2thread/list`)

Observed sanitized request bodies:

```text
[1,20,15,null,null,[null,1,1,1]]
[2,20,15,null,null,[null,1,1,1]]
[3,20,15,null,null,[null,1,1,1]]
[4,20,15,null,null,[null,1,1,1]]
[5,20,15,null,null,[null,1,1,1]]
[2,100,50]
```

- Slot 0 is a folder/tab enum. Values `1`–`5` were observed as the user moved between tabs (inbox/messages/calls/voicemail/spam-style folders). The exact enum-to-tab mapping is not proven from the wire alone.
- Slots 1–2 are page size / window controls (for example `20,15` or `100,50`).
- The trailing `[null,1,1,1]` is a fixed request-options array.

This is strong evidence that voicemail, calls, spam, and archive lists are **not** separate endpoints; they are the same `api2thread/list` call with a different folder enum.

### Search Shape (`api2thread/search`)

Observed sanitized request bodies:

```text
["",200,null,null,null,[null,1,1,1]]
["g",200,...]
["gv",200,...]
["gv h",200,...]
["gv har",200,...]
["s",200,...]
["sp",200,...]
["spa",200,...]
["<phone-number>",200,...]
```

- Slot 0 is the raw query string, sent incrementally on each keystroke.
- Slot 1 (`200`) is a result limit.
- Searching by phone number is supported (value redacted here).

### Thread Action Shape (`thread/batchupdateattributes`)

Observed sanitized request bodies (IDs redacted):

```text
[[[["t.<phone-number>",null,0],[null,null,1],1]]]
[[[["t.<phone-number>",null,1],[null,null,1],1]]]
[[[["t.<phone-number>",null,null,1],[null,null,null,1],1]]]
[[[["t.<short-id>",null,null,1],[null,null,null,1],1]]]
[[[["c.<opaque-id>",null,null,1],[null,null,null,1],1]]]
```

- Each entry keys a thread by an ID with a type prefix: `t.` for a message/conversation thread and `c.` for a call-log entry.
- The differing boolean slot positions (a value toggled in slot index 2 vs 3, and in the second sub-array) correspond to different attribute mutations. This is how the client applies read/unread, archive/unarchive, and spam/block-style state changes through a single endpoint rather than dedicated `archive`/`spam`/`block` endpoints.
- Exact attribute-slot-to-action mapping is not fully proven, but the pattern confirms these actions share this endpoint.

## Voice Calling (SIP over WebSocket)

Capture 2 shows Google Voice web calling runs SIP over a secure WebSocket (`wss://web.voice.telephony.goog/websocket`) with WebRTC media. Outbound SIP requests are readable text frames; inbound frames from the server are binary (opcode 2) and were not decoded, but the client-side responses reveal the full exchange.

### Signaling Flow

SIP request methods sent by the web client:

| Method | Count | Role |
|---|---|---|
| `REGISTER` | 2 | Registers the web endpoint with `web.c.pbx.voice.sip.google.com`. |
| `INVITE` | 3 | Call setup (outbound call plus re-INVITEs). |
| `PRACK` | 2 | Provisional response acknowledgement (100rel). |
| `ACK` | 3 | Final response acknowledgement. |
| `BYE` | 2 | Call teardown / hangup. |

SIP response status lines emitted by the client (for inbound calls):

| Response | Count | Meaning |
|---|---|---|
| `100 Trying` | 3 | Received/processing. |
| `180 Ringing` | 3 | Inbound call ringing on web. |
| `183 Session Progress` | 3 | Early media / session progress. |
| `200 OK` | 2 | Call answered / request success. |
| `487 Request Terminated` | 1 | Inbound call canceled before answer. |
| `603 Decline` | 1 | Inbound call declined/rejected on web. |

Reconstructed call lifecycle:

```text
REGISTER -> 200 (web endpoint registered)
Outbound: INVITE -> 100/180/183 -> PRACK -> 200 OK -> ACK -> media -> BYE
Inbound:  INVITE(recv) -> 100 Trying -> 180 Ringing -> 183 Session Progress
            -> either 200 OK (answer) or 603 Decline (reject) or 487 (terminated)
```

### Media (WebRTC / SDP)

The SDP offer/answer in the SIP bodies (`Content-Type: application/sdp`) shows:

| Attribute | Observed value |
|---|---|
| Transport | `UDP/TLS/RTP/SAVPF` (SRTP over DTLS) and legacy `RTP/AVP` line |
| Audio codecs | `opus/48000/2` (payload 111, `minptime=10;useinbandfec=1`), `red/48000/2`, `PCMU/8000`, `PCMA/8000`, `G722/8000`, `CN/8000` |
| DTMF | `telephone-event` at 8000 and 48000 (RFC 4733), payloads 101/110/126 |
| ICE | `a=ice-options:trickle`, host/srflx candidates, `ice-ufrag`/`ice-pwd` |
| Security | `a=fingerprint:sha-256 ...` DTLS fingerprints |
| Direction | `sendrecv`, `recvonly`, `sendonly` observed across offers/answers |
| Feedback | `transport-cc`, `rrtr` RTCP feedback |
| Bundle | `a=group:BUNDLE` |

Notable SIP headers:

```text
Allow: INVITE,ACK,CANCEL,BYE,UPDATE,MESSAGE,OPTIONS,REFER,INFO,PRACK
Supported: timer,100rel,ice,replaces,outbound,record-aware
User-Agent: GoogleVoice <version-redacted>
```

Interpretation: web calling is standards-based SIP-over-WSS signaling to Google's PBX (`*.pbx.voice.sip.google.com`) with DTLS-SRTP-secured WebRTC audio, Opus-preferred, and in-band DTMF via `telephone-event`. DTMF keypad, mute (`sendonly`/`recvonly` transitions), answer, decline, and hangup (`BYE`) are all represented. Media payload bytes themselves are not in the HAR. Inbound server frames are binary and remain undecoded.


The loaded Google Voice web bundle embedded in `C:\Users\Vayun\Downloads\voice.google.com.har` exposes additional RPC wrappers and route strings that were not exercised by the captured sessions. This section is static evidence from the loaded app code, not proof that every endpoint is enabled for this account or region. Endpoints later confirmed at runtime by capture 2 are noted inline and in the "Second Capture" section above; the remaining rows are still static-only.

### App Routes Present In Loaded JS

The bundle contains route literals for these product surfaces:

```text
/archive
/billing
/blocked
/callrule
/calls
/inbox
/managecallrules
/manageringroups
/messages
/porting
/portingprogress
/promo
/search
/settings
/spam
/transfer
/voicemail
```

### Voice RPCs Present In JS But Not Fully Exercised

All rows below are static-only except `inboundcallrule/list`, which was confirmed at runtime in capture 2.

| Endpoint | Inferred feature area | What was missing from the HAR |
|---|---|---|
| `voice/v1/voiceclient/api2thread/get` | Thread detail | Opening a single conversation deeply enough to fetch a specific thread by ID. |
| `voice/v1/voiceclient/thread/updateattributes` | Single-thread state updates | Per-thread archive/spam/block/read/star-like mutations outside the captured batch update. |
| `voice/v1/voiceclient/thread/batchdelete` | Conversation deletion | Deleting one or more whole conversations/threads. |
| `voice/v1/voiceclient/threaditem/batchdelete` | Message/item deletion | Deleting individual messages, calls, voicemails, or thread items. |
| `voice/v1/voiceclient/clearhistory` | History clearing | Clearing conversation or account history. |
| `voice/v1/voiceclient/rcs/sendmessage` | RCS / richer messaging | Sending an RCS-style message. The capture only exercised SMS send. |
| `voice/v1/voiceclient/account/update` | Account/settings writes | Changing settings such as forwarding, devices, caller ID, voicemail preferences, availability, or privacy options. |
| `voice/v1/voiceclient/clientaccesspermission/get` | Client access permissions | Permission/access state checks beyond the account bootstrap. |
| `voice/v1/voiceclient/idverification/get` | Identity verification | Identity verification flow state. |
| `voice/v1/voiceclient/phoneverification/startchallenge` | Phone verification | Starting linked-number or account phone verification. |
| `voice/v1/voiceclient/phoneverification/verify` | Phone verification | Completing phone verification challenge. |
| `voice/v1/voiceclient/emergencyaddress/verifyemergencyaddress` | Emergency calling | Emergency address verification. |
| `voice/v1/voiceclient/communication/startclicktocall` | Click-to-call | Starting a call through a linked phone/click-to-call flow instead of only initializing web SIP/websocket state. |
| `voice/v1/voiceclient/billing/transaction/list` | Billing / credits | Loading billing history or transactions. |
| `voice/v1/voiceclient/billing/encryptbuyflowparameters` | Billing / credits | Preparing encrypted parameters for a credit purchase or buy flow. |
| `voice/v1/voiceclient/billing/refundcall` | Billing / credits | Refunding or disputing a billed call. |
| `voice/v1/voiceclient/checkportineligibility` | Number porting | Checking whether a number can be ported in. |
| `voice/v1/voiceclient/cancelnumberportin` | Number porting | Canceling a port-in flow. |
| `voice/v1/voiceclient/updatenumberportin` | Number porting | Updating a pending port-in flow. |
| `voice/v1/voiceclient/purchasenumberportin` | Number porting | Purchasing or submitting port-in. |
| `voice/v1/voiceclient/purchasenumberportout` | Number porting | Purchasing or preparing port-out. |
| `voice/v1/voiceclient/numbertransfer/create` | Number transfer | Creating a number transfer. |
| `voice/v1/voiceclient/numbertransfer/execute` | Number transfer | Executing a number transfer. |
| `voice/v1/voiceclient/numbertransfer/cancel` | Number transfer | Canceling a number transfer. |
| `voice/v1/voiceclient/relocknumber` | Number retention / lock | Relocking or keeping a number. |
| `voice/v1/voiceclient/inboundcallrule/list` | Custom call forwarding | Now observed in capture 2. Listing works; create/update/delete/move still not exercised. |
| `voice/v1/voiceclient/inboundcallrule/create` | Custom call forwarding | Creating a custom rule for contacts, groups, or anonymous callers. |
| `voice/v1/voiceclient/inboundcallrule/update` | Custom call forwarding | Updating an existing custom call rule. |
| `voice/v1/voiceclient/inboundcallrule/delete` | Custom call forwarding | Deleting a custom call rule. |
| `voice/v1/voiceclient/inboundcallrule/move` | Custom call forwarding | Reordering custom call rules. |
| `/$rpc/google.voice.ringgroups.v1.RingGroupsService/UpdateUserRingGroupSettings` | Ring groups | Updating ring-group availability or user settings. The capture only called `ListRingGroups`, which returned empty. |

### Feature Strings Present But Under-Mapped

The same JS bundle includes UI strings and code paths for these features even when a distinct RPC was not recovered in this pass:

| Feature area | Static evidence | Likely missing behavior |
|---|---|---|
| Voicemail | Route `/voicemail`; strings for voicemail transcription, active greetings, rename, delete, and playback controls. | Playing voicemail, reading transcripts, managing greetings, setting active greeting, deleting/renaming greetings. |
| Archive / spam / block | Strings such as `Conversation archived`, `Voicemail restored`, `Block number`, `Unblock number`, `Mark as spam`, and `Unmark as spam`. | Archive/restore, spam/unspam, block/unblock across calls, conversations, and voicemails. |
| Calls | Confirmed in capture 2 via SIP-over-WSS (see the calling section): placing/receiving calls, ringing, decline, answer, hangup, DTMF, and mute-style direction changes. | Remaining unknowns: call-waiting/hold, transfer/REFER flows, media permission-denied path, and multi-party behavior. |
| Call forwarding / screening | Strings for `Forward calls`, `Send to voicemail`, `Call screening`, and custom rule creation. | Forwarding decisions, anonymous caller rules, group/contact rules, voicemail greeting selection, and call screening options. |
| Devices and linked numbers | Strings for `Ring these devices`, `Forward to these numbers`, linked number verification errors, and settings links. | Enabling/disabling device ringing, adding/removing/verifying linked numbers, forwarding to external numbers. |
| Billing / credits | Route `/billing`; strings for `Add credit`, `Change payment method`, `Current balance`, `Billing history`, `Auto-recharge`, and calling rates. | Loading balances, adding credit, changing payment method, viewing transactions, international calling rates. |
| Attachments / MMS | Strings for `MMS`, `SMS`, `Sent attachments`, `gv-send-attachment-list`, `Download attachment`, and `Delete message`. | Sending/receiving media attachments, attachment download, attachment removal, and MMS-specific send behavior. A standalone upload endpoint was not recovered from the simple string pass. |
| Promos / onboarding | Routes `/promo` and `/signup`; static assets such as onboarding GIFs and Voice Notes promo media. | Signup, onboarding, upgrade/promo flows, and feature education surfaces. |

## Remaining Boundaries

- Exact protobuf field names for Google Voice RPCs are not recoverable from this HAR alone. The capture exposes paths, methods, counts, status codes, MIME types, positional array shapes, and static endpoint strings, but not `.proto` descriptors.
- Static JS evidence can reveal uncalled endpoints and feature routes, but it cannot prove which flows are enabled for the captured account, region, Workspace/consumer account type, or current experiment flags.
- Websocket frames for `wss://web.voice.telephony.goog/websocket` are partially decoded in capture 2: outbound SIP text frames are readable, but inbound frames are binary (opcode 2) and were not decoded, and RTP/SRTP media payloads are not present in the HAR.
- The SMS send request body contains sensitive message and recipient material in the source HAR. This document records only the sanitized positional shape and endpoint role.
- Contact autocomplete and lookup responses likely contain names, emails, avatars, and phone numbers. They are represented here only as service roles and sanitized shapes.
- Punctual/WebChannel messages include session/channel identifiers and opaque data fields. This document records the setup pattern and parameter names but not channel payload values.
- Google support traffic such as CSP reports, Play logging, WAA, OneGoogle async data, Analytics, and reCAPTCHA is included for completeness but was not reverse-sliced into product schemas because it is generic Google web infrastructure rather than Voice-specific behavior.
