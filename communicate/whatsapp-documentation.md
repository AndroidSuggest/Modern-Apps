# WhatsApp APK Endpoint Documentation

Generated from static analysis of the WhatsApp APK pulled from the connected Android device. This pass goes beyond URL-string extraction: endpoint strings were traced back to DEX string xrefs, decompiled JADX output, config defaults, and visible model/parser code where possible.

Analysis artifacts:

- APK files: `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\apks`
- Main JADX output: `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap`
- Fallback/raw JADX output: `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-fallback-out`
- Raw dex URL strings: `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\dex_url_strings.txt`
- DEX string xrefs: `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\dex_string_xrefs.txt`
- Full generated inventory: `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\full_endpoint_inventory.csv`

Important caveats:

- This is static analysis only. No endpoint was called or tested.
- JADX `--deobf` was already used. No ProGuard/R8 mapping file was available, so original semantic names cannot be fully recovered.
- DEX xrefs are the most reliable call-site locator when JADX drops literals. Some xref class names collide with JADX filesystem names, so a few exact method bodies remain better represented by DEX owner signatures than by readable Java.
- `Exact` below means recovered from URL templates, DEX xrefs, decompiled request code, or decompiled model/parser code. It does not imply the endpoint is public or callable without WhatsApp auth/session state.

## Reverse-Slice Summary

| Endpoint | DEX owner / strongest local evidence | Input format | Output format | Purpose / notes |
|---|---|---|---|---|
| `https://static.whatsapp.net/wa/static/payments/upi/india_bill_pay_get_categories?unique_key=%s&is_dev=%s&version=%s` | DEX xref: `LX/F0R;->A02()V`, `classes8.dex`; see `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\dex_string_xrefs.txt:135-138`. | Exact GET template with `unique_key`, `is_dev`, `version`. | JSON category metadata. Recovered fields from docs/diffs and downstream code: popular/grouped categories, category IDs/names, rank/order, image URLs, last-updated timestamp. | India bill-pay category static metadata. JADX normal output has class-name collision for `F0R`, so request method body was not safely mapped to a Java file. |
| `https://static.whatsapp.net/wa/static/payments/upi/india_billers_by_category?should_fetch_biller_details=true&category_id=%s&unique_key=%s&is_dev=%s&version=%s` | DEX xref: `LX/F0R;->A02()V`, `classes8.dex`; `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\dex_string_xrefs.txt:139-142`. | Exact GET template with `should_fetch_biller_details=true`, `category_id`, `unique_key`, `is_dev`, `version`. | JSON by-category biller list. Recovered response example shape: `category_id`, `biller_list[]`, each biller with `biller_id`, `image_url`, `name`, `rank`. | India biller static metadata by category. Cache file pattern from trace context: `payments_india_billers_<category_id>.json`. |
| `https://static.whatsapp.net/wa/static/payments/upi/india_billpay_operators_and_circles?unique_key=%s&is_dev=%s&version=%s` | DEX xref: `LX/F0R;->A03()V`, `classes8.dex`; `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\dex_string_xrefs.txt:143-145`. Model evidence: `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\p000X\C33483ElW.java:7-38`, `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\p000X\C33639Eo3.java:5-49`. | Exact GET template with `unique_key`, `is_dev`, `version`. | JSON parsed into `RechargesInfo(operatorInfoList, circleInfoList)`. Operator model fields recovered exactly: `operatorId`, `operatorName`, `operatorImageUrl`, `mappedBillerId`, `rank`. Circle list is present as `circleInfoList`; individual circle model fields were only recovered by downstream use as circle reference/code and display name. | Static operator/circle metadata for India prepaid recharge bill payments. |
| `https://static.whatsapp.net/wa/static/payments/upi/bank_list?provider=%s` | DEX xref: `LX/FHi;->BZJ()V`, `classes8.dex`; `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\dex_string_xrefs.txt:147-150`. | Exact GET template with `provider`. | JSON bank list; exact model fields not recovered in this pass. | UPI provider bank list. Local JADX file with similar name maps to unrelated class because of deobfuscation/case collision, so parser model remains unresolved. |
| `https://static.whatsapp.net/wa/static/payments/remittance/get_partners/?sender_country=%s&receiver_country=%s` | DEX xref: `LX/Egk;->A00(Ljava/lang/String;Ljava/lang/String;)Ljava/util/ArrayList;`, `classes8.dex`; `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\dex_string_xrefs.txt:151-154`. | Exact GET template with `sender_country`, `receiver_country`. | Returns `ArrayList` of partner records per DEX method signature; exact partner fields not recovered. | Remittance partner lookup for sender/receiver country pair. |
| `https://static.whatsapp.net/wa/static/sticker?cat=sticker_search&terms=%s&country=%s` | DEX xref: `LX/5TP;->invokeSuspend(Ljava/lang/Object;)Ljava/lang/Object;`, `classes4.dex`; `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\dex_string_xrefs.txt:45-48`. Search dispatch evidence: `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\com\whatsapp\stickers\contextualsuggestion\StickerSearchManager$performSearch$2.java`. | Exact GET template with `cat=sticker_search`, `terms`, `country`. Local code maps a `searchKey` through `EmojiGroupMapper`, joins terms with a space, and applies config key `22573` as the result/request limit before dispatch. | JSON array of sticker records. Recovered fields include `mimetype`, `file-hash`, `enc-file-hash`, `direct-path`, `media-key`, `width`, `height`, `file-size`, `sticker-pack-id`, `animated`, `preview_webp_id`. Local model evidence is `C114864z2` and `C111014sR`. | Sticker search endpoint. Downstream local result model is `Sticker` with URL/direct-path, MIME, dimensions, file size, hashes, metadata, premium/lottie flags, and storage location. |
| `https://static.whatsapp.net/sticker?cat=all&lg=` | String evidence in `classes4.dex`; model evidence in `C112594vE`. | GET with `cat=all`, `lg=<locale>`. | Sticker pack catalog JSON. Local pack model fields include `id`, `name`, `publisher`, `description`, `size`, `trayImageId`, `trayImagePreviewId`, `previewImageIds`, `stickers`, `order`, `playLink`, `iOSLink`, animated/lottie/avatar flags. | Fetch all sticker packs/catalog for locale. Exact request method owner was not resolved beyond string/model evidence. |
| `https://static.whatsapp.net/sticker?cat=suggest_sticker_packs&lg=` | String evidence in `classes4.dex`; model evidence in `C112594vE`. | GET with `cat=suggest_sticker_packs`, `lg=<locale>`. | Same sticker pack model as above. | Suggested sticker-pack catalog. |
| `https://static.whatsapp.net/sticker?id=` | String evidence in `classes4.dex`; model evidence in `C112594vE` / `C114864z2`. | GET with `id=<sticker_or_pack_id>`. | Sticker pack or sticker metadata JSON; exact branch not resolved. | Fetch sticker/pack by ID. |
| `https://static.whatsapp.net/sticker?img=` | String evidence in `classes4.dex`; model evidence in `C114864z2`. | GET with `img=<image_id_or_token>`. | Image/sticker bytes or metadata; exact parser not resolved. | Sticker image fetch. |
| `https://api.giphy.com/v1/gifs/search` | DEX xref: `LX/40t;->A0V([Ljava/lang/Object;)Ljava/lang/Object;`, `classes4.dex`; `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\dex_string_xrefs.txt:74-77`. | Giphy API GET. Expected params are Giphy-standard `api_key`, `q`, `limit`, `offset`, `rating`, locale/lang; exact local query builder not mapped to Java file. | JSON Giphy response normalized into WhatsApp sticker/GIF models. Local provider evidence: `C114864z2.A03()` checks metadata publisher `Giphy` at `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\p000X\C114864z2.java:122-125`. | GIF search integration. |
| `https://api.giphy.com/v1/gifs/trending` | DEX xref: `LX/415;->A0V([Ljava/lang/Object;)Ljava/lang/Object;`, `classes4.dex`; `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\dex_string_xrefs.txt:78-81`. | Giphy API GET. Expected params include `api_key`, `limit`, `offset`, `rating`, locale/lang. | JSON Giphy response normalized into WhatsApp media/sticker models. | Trending GIF integration. |
| `https://api.giphy.com/v1/stickers/search` | DEX xref: `LX/4sd;->A02(Ljava/lang/CharSequence;Ljava/lang/String;)Ljava/lang/String;`, `classes4.dex`; `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\dex_string_xrefs.txt:82-85`. | Giphy sticker search GET. Method signature indicates inputs include a `CharSequence` search term and `String` context/value. | JSON Giphy response normalized into sticker model. | Animated sticker search via Giphy. |
| `https://api.giphy.com/v1/stickers/trending` | DEX xref: `LX/4sd;->A03(Ljava/lang/String;)Ljava/lang/String;`, `classes4.dex`; `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\dex_string_xrefs.txt:86-89`. | Giphy sticker trending GET. Method signature accepts one `String`. | JSON Giphy response normalized into sticker model. | Trending Giphy stickers. |
| `https://api.whatsapp.net/calendar/auth/approve/` | DEX xref: `LX/ASm;->invokeSuspend(Ljava/lang/Object;)Ljava/lang/Object;`, `classes6.dex`; `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\dex_string_xrefs.txt:115-118`. | Server-doc context: POST approve flow. Exact APK body not recovered from Java. | Creates/returns calendar auth approval state; docs indicate creation of `WAApiSession`, `CALENDAR_PLUGIN` feature ent, and ephemeral device-code record. | Calendar integration approval. |
| `https://api.whatsapp.net/calendar/integrations/` | DEX xref: `LX/5Tr;->invokeSuspend(Ljava/lang/Object;)Ljava/lang/Object;`, `classes4.dex`; `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\dex_string_xrefs.txt:90-93`. | Server-doc context: GET integrations list. Exact APK body not recovered. | JSON shape from server docs: `{ "integrations": [{ "app_id": "...", "app_name": "...", "connected_at": "..." }] }`; empty state `{ "integrations": [] }`. | Calendar integrations list. |
| `https://api.whatsapp.net/calendar/integrations/revocations/` | DEX xref: `LX/5Rw;->invokeSuspend(Ljava/lang/Object;)Ljava/lang/Object;`, `classes4.dex`; `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\dex_string_xrefs.txt:94-97`. | Server-doc context: POST revocation/disconnect. Exact APK body not recovered. | Status/empty result or error; docs mention `NOT_CONNECTED`/404-style condition when app is not connected. | Revoke a calendar integration. |
| `https://api.whatsapp.net/support/add_bug_attachment` | DEX xref: `Lcom/whatsapp/inappbugreporting/network/PostBugAttachmentUploader;->A00(LX/Hag;Ljava/lang/String;LX/0nc;)Ljava/lang/Object;`, `classes9.dex`; `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\dex_string_xrefs.txt:160-163`. Worker evidence: `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\com\whatsapp\inappbugreporting\worker\AsyncBugReportPostCreationAttachmentWorker.java:11-25`. | Upload method takes attachment object `LX/Hag`, a `String` likely bug/report id or auth/context, and coroutine continuation. Body likely multipart file upload, but exact fields were not recovered from Java. | Coroutine result object; likely attachment upload result/ID. Exact response model not recovered. | Adds attachment to an in-app bug report. |
| `https://flows.whatsapp.net/flows/cache_management/` | Config default: key `7125` in `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\p000X\C047207o.java:19844`; consumer: `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\p000X\C9P5.java:12-22`. | Config URL read via `A0b(7125)`, converted to `new URL(...)`. Paired config key `7126` is an integer enable/interval value. | Result is a `C211919Qh` cache-cleaner object using either no-op `C22936A1f` or `FGO(url)`. HTTP body/response parser hidden behind unresolved `FGO` implementation. | Flows web cache management/cleanup endpoint. |
| `https://flows.whatsapp.net/flows` | Config defaults: keys `7153` and `6060` in `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\p000X\C047207o.java:19845-19846`; consumers include `C31948Dw1`, `DR0` per reverse-slice report. | Config-backed URL string; exact HTTP request body not recovered. | Flow runtime responses; exact schema not recovered. | WhatsApp Flows runtime service. |
| `https://flows.whatsapp.net/flows-app/catalog` | Config default: key `16723` in `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\p000X\C047207o.java:19736`. | Config-backed URL string; exact HTTP request body not recovered. | Catalog/list of flows/apps; exact schema not recovered. | WhatsApp Flows app catalog. |
| `https://graph.whatsapp.com/graphql` | DEX xrefs: `LX/1c1;->invoke()` in `classes.dex` and `LX/IWe;->invoke()` in `classes9.dex`; `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\dex_string_xrefs.txt:16-18`, `168-170`. | GraphQL POST. Request envelope from related WAMO/ACS docs: JSON with `doc_id`, `variables` as JSON-encoded string, `access_token`, `credential`, `user_id`, `app_id`. Exact APK operation set not recovered. | JSON GraphQL response, schema determined by persisted `doc_id`/operation. | Main WhatsApp GraphQL endpoint. |
| `https://acs.whatsapp.com/graphql` | DEX xref: `Lcom/whatsapp/infra/acsohai/AcsOhaiFetcher;->A00(Lcom/whatsapp/infra/acsohai/AcsOhaiFetcher;Ljava/lang/String;LX/0nc;)Ljava/lang/Object;`, `classes9.dex`; `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\dex_string_xrefs.txt:172-175`. | ACS/OHAI GraphQL fetch. Method accepts `String` plus coroutine continuation; exact JSON body not recovered, likely GraphQL/OHAI envelope. | Coroutine result; likely JSON/typed ACS fetch result. | ACS GraphQL endpoint. |
| `https://acs.whatsapp.com/music/reporting` | DEX xref: `Lcom/whatsapp/snapl/client/SnaplOhaiHttpClient;->A00(Ljava/util/List;LX/0nc;)Ljava/lang/Object;`, `classes9.dex`; `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\dex_string_xrefs.txt:164-167`. | Exact high-level body from docs and xref: method takes `List` of events; upload is multipart POST with gzipped events file, one JSON object per line, max file size about 100 KiB. Visible fields include `media_id`, `country`, `is_copyright_muted`, `persistent_id`, and event types `requested_playing`, `started_playing`, `paused`, `heartbeat`. | Status/ack; server emits one `MediaPlaybackCompoundFalcoEvent` per uploaded JSON line. | Music consumption/reporting upload via SNAPL/OHAI. |
| `https://graph.whatsapp.net/wa_qpl_data` | DEX xref: `Lcom/whatsapp/infra/qpl/quicklog/QplUploadScheduler$QPLUploadWorker;->A0A()LX/H4u;`, `classes8.dex`; `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\dex_string_xrefs.txt:155-158`. | Multipart/upload request. Documented fields: `app_id`, `upload_time`, `batches[]`, `batch_info`, `access_token`, `userid`. Batch files contain JSON QPL entries; `batch_info` contains device/carrier/country/OS metadata. | Upload worker result `LX/H4u`; server ack/status. | QPL/performance telemetry upload. |
| `https://crashlogs.whatsapp.net/wa_fls_upload_check` | DEX xref: `LX/0A8;->A0D(Ljava/lang/String;Ljava/lang/String;Z)Ljava/lang/String;`, `classes.dex`; `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\dex_string_xrefs.txt:7-10`. | Exact method signature exposes inputs: two `String`s and a boolean. Documentation maps request params to `agent`, `from_jid`, `type`, `support_exception_only_upload`. | String response from method signature; likely upload-check decision/status payload. | Preflight check for crash/fastlog upload, including sampling/rate limiting. |
| `https://crashlogs.whatsapp.net/wa_clb_data` | DEX xrefs: `LX/0A8;->A0B(...,Ljava/util/Map;IZZZ)Z`, `LX/1EK;->A01(...,Ljava/io/File;Ljava/lang/Integer;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZZ)Ljava/lang/String;`, plus `ExceptionsUploadService` and voice call upload xrefs; `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\dex_string_xrefs.txt:11-14`, `119-124`. | Upload body includes a `File`, metadata strings, integer/type, `Map` metadata, and boolean flags per method signatures. Crash minidump packaging itself goes through Breakpad/native/shared code. | Boolean success in `LX/0A8.A0B`; string result in `LX/1EK.A01`; service result hidden by upload service. | Crash/log batch upload. |
| `https://crashlogs.whatsapp.net/wa_profilo_data` | DEX xref: `Lcom/whatsapp/infra/perf/profilo/ProfiloUploadService;->A0C(Landroid/content/Intent;)V`, `classes6.dex`; `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\dex_string_xrefs.txt:125-128`. | Android service receives `Intent`; payload is Profilo trace file/native trace data. Exact trace serialization hidden behind Profilo native/shared writer classes. | Service-side upload status; exact response not recovered. | Profilo performance trace upload. |
| `https://crashlogs.whatsapp.net/whatson_logs_upload` | DEX xref: `LX/9lI;->A05(...,Lcom/whatsapp/fieldstats/events/WamCall;Ljava/io/File;...;Ljava/lang/String;)Z`, `classes6.dex`; `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\dex_string_xrefs.txt:129-132`. | Upload method takes `WamCall`, `File`, boolean flags, and a `String`; docs/logs show zipped VoIP time-series log upload. | Boolean success. | WhatsOn/VoIP time-series log upload. |
| `https://mmg.whatsapp.net/proxygen/health` | Config default at `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\p000X\C047207o.java:19654`; switch accessor at `C049808q.java:3954`; DEX xrefs in `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\dex_string_xrefs.txt:20-23`. | Config URL string; likely GET/no body health probe. | Health/status response; exact body not recovered. | Proxygen/media health-check endpoint. |
| `https://mmg.whatsapp.net` | Config/default and media model usage. Business catalog logo code reads key `22436` in `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\com\whatsapp\catalog\product\biz\webview\CatalogWebMetaDataRepository.java:101-139`. | Media/CDN requests; exact paths vary. For catalog metadata, used as a base to build media URL from business logo direct path. | Media bytes or transformed media URL. | WhatsApp media/CDN host. |

## Exact Local JSON / Model Shapes Recovered

### Catalog Web Metadata JSON

`C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\com\whatsapp\catalog\product\biz\webview\CatalogWebMetaDataRepository.java:344-396` constructs a JSON-like object with these keys:

```json
{
  "biz_jid": "<raw user jid>",
  "wam_message_id": "<message id>",
  "qpl_message_id": "<qpl message id>",
  "wam_session_id": "<session id>",
  "qpl_session_id": "<wae-prefixed session id>",
  "business_name": "<business display name>",
  "biz_logo": "<base64 or media URL, optional>",
  "is_template": true,
  "hsm_tag": "<tag>",
  "biz_platform": 0,
  "entry_point_conversion_source": "<source>",
  "entry_point_conversion_app": "<app>",
  "entry_point_conversation_initiated": 0,
  "catalog_product_ids": ["<product id>"],
  "catalog_id": "<business user id>",
  "catalog_sections": ["<section objects>"],
  "catalog_session_id": "<session id>",
  "order_id": "<order id>",
  "catalog_entry_point": 0,
  "catalog_params": { }
}
```

Inputs are read from a `Bundle` at `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\com\whatsapp\catalog\product\biz\webview\CatalogWebMetaDataRepository.java:188-223`: `extra_message_id`, `extra_session_id`, `extra_order_id`, `extra_order_token`, `extra_product_ids`, and `extra_product_list_info`.

### Sticker Search Result / Sticker Model

Local model `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\p000X\C114864z2.java:314-340` exposes the normalized sticker fields:

```text
url
mimeType
height
width
metadata
saltedFileHash
fileSize
isLottie
premium
fileStorageLocation
```

Sticker metadata serialization in `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\p000X\C111014sR.java:93-178` emits:

```json
{
  "sticker-pack-id": "...",
  "sticker-pack-name": "...",
  "sticker-pack-publisher": "...",
  "accessibility-text": "...",
  "android-app-store-link": "...",
  "ios-app-store-link": "...",
  "emojis": ["..."],
  "is-first-party-sticker": 1,
  "is-from-sticker-maker": 1,
  "is-avatar-sticker": 1,
  "avatar-sticker-template-id": "...",
  "is-ai-sticker": 1,
  "premium": 1,
  "is-avatar-country-sticker": 1,
  "is-avatar-instant-sticker": 1,
  "sticker-maker-source-type": 1,
  "is-avatar-social-sticker": 1,
  "avatar-sticker-style": "...",
  "avatar-sticker-revision-id": "...",
  "is-from-user-created-pack": 1,
  "origin-pack-id": "...",
  "is-text-sticker": 1
}
```

Provider checks for `Giphy`, `Klipy`, and `Tenor` are in `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\p000X\C114864z2.java:122-144`.

### Sticker Pack Model

`C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\p000X\C112594vE.java:142-212` exposes pack fields:

```text
id
name
publisher
description
size
isDownloading
trayImageId
trayImagePreviewId
previewImageIds
stickers
order
isThirdParty
imageDataHash
downloadedSize
downloadedImageDataHash
downloadedTrayImageId
downloadedTrayImagePreviewId
isUnseen
isNew
avoidCaching
playLink
iOSLink
animatedPack
downloadedAnimatedPack
isAvatarStickerPack
trayIconAvatarStickerTemplateId
emptyFavoritesAvatarStickerTemplateId
emptyRecentsAvatarStickerTemplateId
avatarStickerPackDynamicIcon
lottieStickerPack
downloadedLottieStickerPack
isInInstalledStickerPacksDB
isStickerPackMessage
isCreatedByMe
```

### India Recharge Static Model

`C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\p000X\C33483ElW.java:32-38` names the wrapper as:

```text
RechargesInfo(operatorInfoList=<list>, circleInfoList=<list>)
```

`C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\p000X\C33639Eo3.java:36-48` names operator fields:

```text
IndiaBillPaymentsRechargeOperatorInfo(
  operatorId,
  operatorName,
  operatorImageUrl,
  mappedBillerId,
  rank
)
```

Downstream biller-account code references `MobileNumber`, `CircleRefID`, and `OperatorCode` in the static info flow.

## Public Web / Deep Links

These are observed URL templates, but they are user-facing web/deep-link surfaces rather than private backend APIs.

| Endpoint | Input format | Output format | Purpose |
|---|---|---|---|
| `https://api.whatsapp.com/send/?phone=` | GET/deep link with `phone`. | Web redirect/app intent or HTML. | Open chat with phone. |
| `http://api.whatsapp.com/send?phone=%s&text=%s` | GET/deep link with `phone`, `text`. | Web redirect/app intent or HTML. | Open chat with prefilled text. |
| `https://api.whatsapp.com/create/group` | GET/deep link. | Web redirect/app intent or HTML. | Group creation flow. |
| `https://api.whatsapp.com/message_yourself` | GET/deep link. | Web redirect/app intent or HTML. | Message-yourself flow. |
| `https://web.whatsapp.com` | Web app request. | HTML/JavaScript. | WhatsApp Web. |
| `https://chat.whatsapp.com/` | Invite URL, token appended at runtime. | Web redirect/app intent or HTML. | Group/community invite links. |
| `https://call.whatsapp.com/*` | URL pattern/allowlist. | Web redirect/app intent or HTML. | Call links. |
| `https://b.whatsapp.com/bizai/gdrive-picker` | Browser/OAuth-like flow. | HTML/JavaScript or redirect. | Business AI Google Drive picker. |

## Registration And Account Verification

Registration is centered on `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\com\whatsapp\registration\core\http\KotlinRegistrationBridge.java:76`, with the retrying HTTP boundary in `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\com\whatsapp\registration\core\http\retry\RetryingHttpClient.java:77`. The raw fallback HTTP client is `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-fallback-out\sources\p000X\C34064Ev8.java:125`, and registration request maps are built with `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\p000X\C34244EyE.java:12`.

Endpoint constants are decoded from obfuscated strings in `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\p000X\AbstractC33930Esr.java:26-32` and exposed by `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\p000X\AbstractC32697ETn.java:5-22`. The recovered registration endpoints include `/v2/code`, `/v2/register`, `/v2/security`, `/v2/exist`, `/v2/consent`, `/v2/challenge`, `/v2/autoconf`, `/v2/autoconf_verifier`, `/v2/device_logout_fetch`, `/v2/device_logout_send`, `/v2/client_log`, `/v2/pre_pn_client_log`, `/v2/passkey_auth`, `/v2/wfs`, `/v2/acverify`, `/v2/acverify_request`, and `/v2/reg_onboard_abprop`.

Common request fields:

| Helper | Fields |
|---|---|
| `KotlinRegistrationBridge.A01()` at `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\com\whatsapp\registration\core\http\KotlinRegistrationBridge.java:213-217` | `cc`, `in` |
| `KotlinRegistrationBridge.A0Q()` at `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\com\whatsapp\registration\core\http\KotlinRegistrationBridge.java:2786-2790` | `lg`, `lc`, `fdid`, `expid` |
| `KotlinRegistrationBridge.A0R()` at `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\com\whatsapp\registration\core\http\KotlinRegistrationBridge.java:2793-2799` | `access_session_id`, `id`, `backup_token` |
| `KotlinRegistrationBridge.A0P()` at `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\com\whatsapp\registration\core\http\KotlinRegistrationBridge.java:2778-2783` | `advertising_id`, `login`, `type` |

Major registration flows:

| Flow | Request fields | Response fields / status |
|---|---|---|
| Request code, `A06()` / `/v2/code`, `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\com\whatsapp\registration\core\http\KotlinRegistrationBridge.java:703-772` | `cc`, `in`, common fields, `token`, `method`, `context`, `clicked_education_link`, `manage_call_permission`, `call_log_permission`, optional `client_start_message` | statuses/reasons include `sent`, `ok`, `attached`, `too_many`, `too_recent`, `blocked`, `security_code`, `next_method`, `waiting_for_sms`, `too_many_all_methods`, `bad_token`, `invalid_skey`; parsed fields include `login`, `type`, `retry_after`, `length`, `code`, `new_jid`, `wipe_wait`, `email_otp_wait`, `fallback_methods`, `second_factor_methods`, `is_device_trusted` |
| Register/verify phone, `A05()` / `/v2/register`, `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\com\whatsapp\registration\core\http\KotlinRegistrationBridge.java:441-502` | `cc`, `in`, common fields, `code`, optional `auth_response`, `context`, `method`, `advertising_id`, `login`, `type`, E2E key bundle | statuses include `ok`, `sent`, `sms_required`, `format_wrong`, `incorrect`, `mismatch`, `security_code`, `second_code`, `missing`, `challenge`, `reset_too_soon`; parsed fields include `login`, `new_jid`, `server_time`, `secure_verifier`, `wa_ac_machine_id`, `passkey_credential`, `coex_products`, `ent_access_token` |
| Security code / 2FA, `A0B()` / `/v2/security`, `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\com\whatsapp\registration\core\http\KotlinRegistrationBridge.java:1576-1746` | `cc`, `in`, common fields, `code`, optional `reset`, optional `wipe_token`, `advertising_id`, E2E key bundle | `login`, `new_jid`, `guess_wait`, `server_time`, `reset_method`, `wipe_token`, `wipe_wait`, `security_code_set`, `pending`, `idv_token`, `wa_ac_machine_id`, `passkey_credential`, `lid` |
| Same-device / old-device check, `A07()` / `/v2/exist`, `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\com\whatsapp\registration\core\http\KotlinRegistrationBridge.java:1055-1229` | common fields, optional `foa_backup_token`, optional `client_capabilities`, ad/login/type fields, E2E keys | `sms_wait`, `voice_wait`, `wa_old_wait`, `email_otp_wait`, `silent_auth_wait`, `sms_length`, `voice_length`, `wa_old_length`, `passkey_auth_challenge`, `server_start_message`, `wa_old_eligible`, `wa_old_device_name`, `verify_pn_device` |
| Account-defence old-device fetch/send, `/v2/device_logout_fetch` and `/v2/device_logout_send`, `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\com\whatsapp\registration\core\http\KotlinRegistrationBridge.java:1788-1918` | `token`, `advertising_id` | device logout/account-defence state; UI reads `server_token` at `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\com\whatsapp\registration\app\accountdefence\p143ui\OldDeviceMoveAccountNoticeActivity.java:67-69` |
| Passkey, `A0K()` / `/v2/passkey_auth`, `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\com\whatsapp\registration\core\http\KotlinRegistrationBridge.java:2661-2721` | common fields, optional `access_session_id`, `context` | `credential_create`, `login`, `cred_token`, `reason`; Android passkey API is used by `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\com\whatsapp\registration\verification\passkey\PasskeyVerifier.java:71-76` and `:126-135` |

Registration crypto/key upload is attached through `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-fallback-out\sources\p000X\C34040Euf.java:25-75`. The recovered key-bundle fields are `authkey`, `e_ident`, `e_keytype`, `e_regid`, `e_skey_id`, `e_skey_val`, and `e_skey_sig`. Those values are pulled from `C1D8` / `C1DB`; `C1DB` persists crypto material in `SharedPreferences` named `keystore` and interacts with AndroidKeyStore migration/verification state at `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-fallback-out\sources\p000X\C1DB.java:73-147`.

The registration HTTP body is `application/x-www-form-urlencoded`, built from `C34244EyE.A00` entries, and sent in the `registration` category. Fallback `RetryingHttpClient` also shows encrypted body/query handling with AES/GCM-like helpers, an `ENC` parameter, and an `H`/HMAC-like parameter around `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-fallback-out\sources\com\whatsapp\registration\core\http\retry\RetryingHttpClient.java:236-407`.

## Message Send And Receive

The local Java representation of XMPP/protocol stanzas is `C0ZU` / `C0ZS`:

| Class | Evidence | Role |
|---|---|---|
| `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\p000X\C0ZU.java:18-44` | fields `A00`, `A01`, `A02`, `A03` | Protocol-tree node with tag, raw data bytes, child nodes, and attributes. It enforces either raw `data` or children, not both. |
| `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\p000X\C0ZS.java:9-57` | fields `A00`, `A01`, `A02`, `A03` | Attribute/key-value object, optionally JID-backed. |
| `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\com\whatsapp\infra\protocol\ProtocolJniHelper.java:13-113` | `createKeyValue`, `createNewJid`, `createProtocolTreeNode`, getters | JNI/native bridge for constructing and reading protocol tree nodes. |

Low-level send pipeline:

```text
Message model / encrypted protobuf bytes
  -> C0ZU protocol tree node
  -> Android Message wrapper
  -> C10830Yd message client
  -> InterfaceC29761Gp.CEG(...) active XMPP connection
  -> pre-ack / callback / retry tracking
```

Evidence for this boundary is `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\p000X\C10830Yd.java`. Key methods:

| Method | Role |
|---|---|
| `C10830Yd.A01(...)`, `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\p000X\C10830Yd.java:69-90` | Wraps `C0ZU` into Android `Message`, with type codes for IQ/callback/drop-if-offline behavior. |
| `C10830Yd.A05(...)`, `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\p000X\C10830Yd.java:157-217` | Core `sendXmpp` path; checks readiness, wakes/uses connection manager, sends through `InterfaceC29761Gp.CEG(...)`, and records processed/pre-ack state. |
| `C10830Yd.A0B(...)`, `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\p000X\C10830Yd.java:320-333` | Sends an ackable `ProtocolTreeNode` and records a future against the stanza key. |
| `C10830Yd.A0D(...)`, `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\p000X\C10830Yd.java:345-363` | Suspending IQ send wrapper with pending/in-flight queue handling. |
| `C10830Yd.A0O(...)`, `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\p000X\C10830Yd.java:504-585` | Ack/receipt logic for `receipt`, `notification`, `message`, `call`, and `status`. |

The receive path is now traced through the APK-visible obfuscated classes rather than just reconstructed from source-context names. The core inbound chain is:

```text
C17U.A04(Message, recvType)
  -> C17Y.handleMessage(...)
  -> recvType 0 / MESSAGE_FOR_ME
  -> C50456Mqy.A0W provider 6323
  -> C1Zv allocates X.1Fw
  -> C29581Fw.B7t(Message, 0)
  -> AL2 case 29 / MessageForMeXmppHandler/onMessageForMe
  -> C215869d0.A00(...)
  -> RunnableC23480AMk case 7
  -> C194848in.A08(...)
  -> AbstractC220419l8 / DecryptMessageRunnable
  -> C9RC.A00(...) decrypt dispatcher
  -> A4F / DecryptionCallbackV2
  -> C216729eT.A01(...) / SharedMessageProcessor
  -> C1G9 / C1Qu local message construction
  -> AbstractC169987f9.A0u(...)
  -> C39111ip.A01/A00(...)
  -> C279219e.A08/A09/A0D(...) / CoreMessageStore write coordinator
  -> C18G.A07(C1G9) / CoreMessageStore insertMessage helper
  -> C165527Tn / X.7Tn case 39
  -> C1AC.A0A(C1G9) MainMessageStore insert layer
```

Important case-collision detail: provider `6323` allocates obfuscated `X.1Fw`, whose real fallback JADX file is `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-fallback-out\sources\p000X\C29581Fw.java`, not unrelated uppercase `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-fallback-out\sources\p000X\C1Fw.java`. `C29581Fw.B7t(...)` unwraps `C210359Jk` into parsed-message metadata `C8YL` and stanza wrapper `C1YF`, then invokes `AL2` case `29`.

Concrete decryption dispatch is visible in `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-fallback-out\sources\p000X\C9RC.java`. Ciphertext type `0` calls `C12200bV.A0L(...)` and `C220209ki.A05(...)` for individual session decrypt; type `1` calls `C12200bV.A0M(...)` and `C220209ki.A04(...)` for prekey decrypt; type `2` calls `AbstractC218429hS.A00(...)` for group sender-key decrypt; type `4` is a bot/message-secret path. `C220209ki` contains SessionCipher-style ratchet, HMAC-SHA256 MAC verification, and AES/CBC or AES/CTR decrypt logic. `AbstractC218429hS` parses sender-key messages, checks counters/signing keys, decrypts with `AES/CBC/PKCS5Padding`, calls `InterfaceC23823AbO.B7c(byte[])`, and persists sender-key state. `C216259dd` is the decrypt result object with `A00` status and `A01` plaintext.

The storage boundary is also concrete. `AbstractC169987f9.A0u(...)` is a shim into `C39111ip.A01(C1G9, ADY, C8YL)`. `C39111ip.A00(...)` logs `IncomingMessageManager/notifyBeforeIncomingMessageStored`, runs `C1A5.ABg(...)` listener gates, processes decrypted-message processor set `7327` through `C41451mf`, calls into `C279219e` as the `CoreMessageStore/writeMessageToDatabase` coordinator, then notifies `IncomingMessageManager/notifyAfterIncomingMessageStored`. The concrete insert edge is now bytecode-backed: `C18G.A07(C1G9)` opens a DB transaction, constructs `C165527Tn` / `X.7Tn` with discriminator `39`, and `X.7Tn` case `39` gets `C1AC` from provider `C18J.A02` and calls `C1AC.A0A(C1G9)`. `C1AC.A0A(C1G9)` logs `MainMessageStore/insertMainMessage`, builds `ContentValues`, writes the `message` table through `INSERT_MESSAGE_MAIN_SQL` or `INSERT_MESSAGE_MAIN_WITH_ROW_ID_SQL`, and stores row/sort ids. `C1AC.A0B(C1G9, int, boolean)` remains the concrete main-message update path. Correction to an earlier caveat: `RunnableC23493AMx` / `X.AMx` case `48` is not the insert callback; it logs `CoreMessageStore/addmsg/outer transaction rollback`, cleans `C279219e.A0U`, and belongs to rollback/error cleanup after `C279219e.A0D(...)`.

Presence, chatstate, and receipts use adjacent but distinct paths:

| Feature | Concrete owner | Format / behavior |
|---|---|---|
| Peer message receipt recvType `221` | `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-fallback-out\sources\p000X\C1DY.java`, base `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-fallback-out\sources\p000X\C1DX.java` | `C1DY` registers `[221]`; `C1DX.B7t(...)` parses receipt stanza attrs through `AbstractC170307ff.A03(...)`, runs `AL0` case `13`, then `C1DY.A05(...)` schedules `RunnableC23488AMs` case `17`. That case logs `PeerMessageReceiptHandler/handleDeliveryReceipt`, resolves peer `DeviceJid` and message id, looks up the message through `C16220iv.A04(...)`, skips history-sync notification messages, updates placeholder retry timestamps when applicable, and marks the row via `C16220iv.A06(rowId)`. |
| Message-state receipt recvType `1` | Uppercase DEX class `LX/1EI;`; source file remains case-collided | `C17Y` labels recvType `1` as `MESSAGE_STATE_UPDATE_RECEIPT`. Bytecode shows `LX/1EI;.<init>()` registers `[1]` through `C1DX`, sets superclass receipt mode `2`, uses providers `2696`, `5854`, and `2704`, and maintains an in-flight `Set` of stanza keys. `A04(C170367fl)` logs `MessageStatusUpdateReceiptHandler/onMessageStatusUpdate receipt in queue; skipping stanzaKey:` and acks skipped duplicate queued receipts. `A05(C0ZU, C170367fl)` creates receipt metrics via `C0ZM.A00(..., 1, loggableStanzaId)`, parses receipt status/update payload through `C219789jx.A03(...)`, then schedules it via `C219099ie.A03(..., 5000)` and removes the stanza key from the in-flight set. Do not confuse uppercase `X.1EI` with lowercase `X.1Ei` / `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-fallback-out\sources\p000X\C29191Ei.java`, which is a different retry-receipt coordinator. |
| Inbound presence | `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-fallback-out\sources\p000X\C36721et.java` under `AbstractC36671eo` | `AbstractC36671eo.A03()` advertises tag `presence`. `A02(...)` parses `from`, group `count`, `type`, `name`, `presence`, and last-seen timestamp, then calls `C33861Yl.A13(...)`, `A12(...)`, `A0y(...)`, or `A0x(...)`. |
| Inbound chatstate | `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-fallback-out\sources\p000X\C36681ep.java` under `AbstractC36671eo` | `AbstractC36671eo.A03()` advertises tag `chatstate`. `A02(...)` parses `from`, `participant`, child `composing` or `paused`, and optional `media`, then calls `C33861Yl.A0w(...)` or `C33861Yl.A0v(...)`. |
| Connection-thread presence/chatstate sink | `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-fallback-out\sources\p000X\C33861Yl.java` | `A0v` emits paused compose message code `21`; `A0w` emits composing message code `20` with `media`; `A0x` emits available presence code `5`; `A0y` emits unavailable presence code `64` with `lastSeen` and `presence`. |
| Outbound composing | `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-fallback-out\sources\p000X\C175167nb.java:92-180` | Builds `chatstate` to `to`, child `composing`, optional `media="audio"`, sometimes nested `bot jid`, sends through `C10830Yd.A0S(C0ZU, 4)`, and logs `HandleMeComposing/sendComposing; toJid=`. |

## Encryption And Key Management

The APK contains Signal/libsignal surfaces, but many primary interfaces decompile as stubs. Exact interface files include:

| File | Role |
|---|---|
| `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\org\signal\libsignal\protocol\state\IdentityKeyStore.java:1-5` | Identity-key store API surface. |
| `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\org\signal\libsignal\protocol\state\PreKeyStore.java:1-5` | Pre-key store API surface. |
| `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\org\signal\libsignal\protocol\state\SessionStore.java:1-5` | Session store API surface. |
| `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\org\signal\libsignal\protocol\state\SignedPreKeyStore.java:1-5` | Signed pre-key store API surface. |
| `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\org\signal\libsignal\protocol\groups\state\SenderKeyStore.java:1-5` | Sender-key/group encryption store surface. |

Registration attaches an E2E key bundle using `C34040Euf.A00()` as described above. Separately, XMPP prekey upload begins in `C1D8`, which builds `iq` nodes containing `registration`, `identity`, prekey `list`, signed prekey `skey`, PQ prekeys, and type `{5}` at `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\p000X\C1D8.java:171-232`.

Other recovered crypto surfaces:

| Area | Evidence | Meaning |
|---|---|---|
| Curve25519 / KEM | `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\org\whispersystems\curve25519\NativeCurve25519Provider.java`, `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\org\whispersystems\libsignal\kem\KEMPublicKey.java` | APK includes Curve25519 and KEM surfaces, but concrete serialization/algorithm flow was not fully readable. |
| Companion pairing | `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-fallback-out\sources\com\whatsapp\companiondevice\devices\crsc\crscv3\CompanionRegOverSideChannelV3Manager.java:267-300` | Encrypted pairing request is decrypted by `p000X.C9AC.A00(...)`, parsed as protobuf `C8CH`, and used to generate companion pairing data via `p000X.C9AB.A00(...)`. |
| Linked-device inbox key exchange | `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-fallback-out\sources\com\whatsapp\companiondevice\tethered\crypto\TetheredInboxKeyExchanger.java:4-64` and `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-fallback-out\sources\com\whatsapp\companiondevice\tethered\crypto\TetheredInboxKeyRelayImpl.java:17-60` | Uses `SecureRandom`, relays four byte arrays, and delegates exact crypto/wire details to obfuscated helpers. |
| AndroidKeyStore auth-key path | `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\protocol_string_xrefs.txt:370-375` | String xrefs show AndroidKeyStore verifying-stage reads and wrong-key/read-failed handling. |
| Signed prekey lifecycle | `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-fallback-out\sources\com\whatsapp\messaging\signal\jobqueue\job\RotateSignedPreKeyJob.java` | Entry point exists; generation/upload behavior needs deeper tracing. |
| Media encryption | `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-fallback-out\sources\com\whatsapp\media\newdownload\engine\EncryptedDownloadEngine.java` | Strong next target; exact media key/HKDF/AES flow was not fully traced here. |

## Voice Calls And Video Calls

Calling has a clearer named surface than messaging, but the media engine itself is native. The main Java/native boundary is `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\p000X\C0WH.java:24-251`; it exposes accept/reject/end calls, group join/invite, call links, signaling HTTP ingest, callback registration, data channel, video capture/render, camera, proxy/network updates, and rekey. Native VoIP constants/callback registration are visible in `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-fallback-out\sources\com\whatsapp\calling\voipcalling\Voip.java:4-153`, with JNI helper dependencies in `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-fallback-out\sources\com\whatsapp\calling\voipcalling\JNIUtils.java:7-30`.

Signaling splits into XMPP call stanzas and HTTP/TEE signaling:

| Path | Evidence | Format |
|---|---|---|
| XMPP call signaling | `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\com\whatsapp\calling\infra\voipcalling\SignalingXmppCallback.java:7-9` | `sendCallStanza(Jid, String, VoipStanzaChildNode)` |
| HTTP/TEE signaling | `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\com\whatsapp\calling\infra\voipcalling\SignalingHttpCallback.java:6-11` | `sendMsg(String, byte[], int)`, where request type `0` is voice session and `1` is codec avatar |
| VoIP child-node model | `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\com\whatsapp\infra\protocol\VoipStanzaChildNode.java:14-263` | call-stanza node with `tag`, `attributes`, `children`, `data`, convertible to/from protocol tree nodes |

Outbound call-stanza encryption is visible in fallback `OutgoingSignalingHandler`: it rewrites `enc` nodes, rewrites `destination` children, detects encrypted `pkmsg` payloads, creates per-destination encrypted stanza children, and bulk-encrypts E2E keys for destination devices in `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-fallback-out\sources\com\whatsapp\calling\service\OutgoingSignalingHandler.java:79-254`.

Call state models:

| File | Recovered role |
|---|---|
| `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\com\whatsapp\calling\infra\voipcalling\CallInfo.java:27-144` | central call state: call ID, relay UUID, call-link fields, creator/peer/group JIDs, group-call flags, video flags, byte counts, EC mode, E2EE flag, phash, screen sharer info |
| `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\com\whatsapp\calling\infra\voipcalling\CallOfferInfo.java:8-61` | incoming offer fields: call ID, sender, group, video flag, joinable call, call-link token, audio chat, group phash, participant hash |
| `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\com\whatsapp\calling\infra\voipcalling\CallLogInfo.java:9-39` | call-log record: initial peer, result type, tx/rx bytes, device-switch termination, group-call logs |

Voice vs video differences are visible at the Java boundary. Voice uses the audio device factory at `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\com\whatsapp\calling\audio\VoipSystemAudioDeviceFactory.java:4-10` and audio sampling helpers in `JNIUtils`. Video adds `VideoPort`, camera, codec, foreground service type, and render/capture callbacks: `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\com\whatsapp\calling\infra\videoport\VideoPort.java:14-100`, `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\com\whatsapp\calling\camera\VoipPhysicalCamera.java:78-177`, and H.264/H.265/WebRTC feature gates in `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-fallback-out\sources\com\whatsapp\calling\voipcalling\JNIUtils.java:211-280`, `:335-340`, `:476-481`, `:1114-1117`, and `:1332-1340`.

Call crypto surfaces:

| Evidence | Meaning |
|---|---|
| `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\com\whatsapp\calling\infra\crypto\CryptoCallback.java:4-14` | native VoIP expects callbacks for E2E key generation, random bytes, secure SSRC, HKDF-SHA256, and HMAC-SHA256. |
| `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-fallback-out\sources\com\whatsapp\calling\voipcalling\Voip.java:8-15` | reject reason `enc`; rekey message ID `call_rekey`. |
| `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-out-smallheap\sources\p000X\C0WH.java:153-171` | registers crypto/data-channel/HTTP/XMPP callbacks; exposes rekey and resend-offer-on-decryption-failure boundaries. |
| `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\jadx-fallback-out\sources\com\whatsapp\calling\voipcalling\VoipEventCallback.java:149` | `rejectedDecryptionFailure(DeviceJid, String, byte[], int)` callback. |

Relay/proxy/TURN/STUN note: exact TURN/STUN hostnames were not found in the Java files read. Java exposes relay/proxy/network controls and callbacks: `relayCallUuid` in `CallInfo`, privacy relay checks in `JNIUtils`, proxy helpers, relay bind failure callback, multipath/network updates, and alternative socket/network methods. Actual RTP/WebRTC/TURN/STUN selection appears to be native or config-driven.

## Remaining Boundaries

- Exact GraphQL operation schemas require persisted query IDs / generated GraphQL models. The APK reveals endpoint owners but not a complete operation catalog in a clean form.
- Crash minidump, Profilo trace, and some telemetry payload packaging enter native/shared upload code. Java exposes service/method boundaries and some method signatures, but not full binary body schemas.
- Message receive-side mapping is mostly traced through obfuscated APK classes, including decrypt dispatch, plaintext protobuf parsing, local message construction, and the main-message database layer. The former case-collision seams for uppercase `X.1EI` and lowercase `X.AMx` are now bytecode-resolved; remaining gaps are provider-array decoding for sets `7247`, `7278`, and `7327`, plus deeper semantic mapping of helper providers such as `2696`, `5854`, and `2704` behind message-state receipts.
- Signal/session/prekey/sender-key storage interfaces are present, but concrete store schemas are obfuscated or native/shared.
- Call media transport and encryption internals are mostly behind `Voip`/JNI/native media engine boundaries; Java shows callbacks, key derivation hooks, and signaling wrappers, not the full RTP/SRTP implementation.
- Some DEX xref class names collide with JADX deobfuscated filesystem names. For those, the DEX owner signature is more reliable than the Java filename.
- The generated 805-candidate inventory at `C:\Users\Vayun\Documents\code\Modern-Apps\apk-analysis\whatsapp\full_endpoint_inventory.csv` includes many SDK docs/store URLs and allowlist hosts; this document focuses on network-relevant endpoints and protocol flows with reverse-slice evidence.

## Registration Attestation — RECOVERED (token / ENC / H, pure Java)

Follow-up deep-dive (for the `communicate` primary-client effort) established that the `/v2/*`
registration attestation is **not native** for this APK — it is reproducible from Java alone.

### `token` param (`/v2/code`) — refutes "token is in libwhatsapp.so"

Call chain:
`com/whatsapp/registration/p144ui/task/RequestCodeRepository$requestCode$2.java:99-105`
computes `token = ES2.A00.A01(application, phoneNumber)` where `ES2.A00` is a `p000X.C34029EuU`
singleton; it is forwarded verbatim through
`KotlinRegistrationBridge$generateAuthCodeBlocking$1` into
`KotlinRegistrationBridge.A06(...)` (`.../core/http/KotlinRegistrationBridge.java:703`, fallback
`:1102`) where `c34244EyE.A01("token", str8)` (line 730) adds it to the form body.

Generator `p000X.C34029EuU.A01(Context, String phoneNumber)`
(`jadx-fallback-out/sources/p000X/C34029EuU.java:50-200`) — pure `javax.crypto`, **no `native`,
no `System.loadLibrary`, no JniBridge**:
```
password = packageName.getBytes(UTF_8) || bytes(res/drawable-hdpi/about_logo.png)
salt     = Base64.decode(ES3.A00)
dk       = SecretKeyFactory("PBKDF2WithHmacSHA1And8BIT").deriveKey(password, salt, iters=128, keyLenBits=512)
mac      = Mac("HmacSHA1"); mac.init(dk)
mac.update(signatureBytes)     // PackageManager GET_SIGNATURES of com.whatsapp
mac.update(MD5(classes.dex))   // MessageDigest MD5 over ZipFile(packageCodePath)."classes.dex"
mac.update(phoneNumber.getBytes(UTF_8))
token    = Base64UrlSafeNoWrap(mac.doFinal())
```

### `ENC` param (encrypted query string) — Curve25519 ECDH → AES-256-GCM

`jadx-fallback-out/.../core/http/retry/RetryingHttpClient.java:236-281`
(`RegistrationEncryption/encryptQueryString`):
```
eph    = X25519 ephemeral keypair (AbstractC172807jm.A01())
shared = X25519(eph.priv, ES4.A00)          // ES4.A00 = 32-byte server static pub, keytype 5
key    = shared as AES-256 key
iv     = 12 bytes
ct     = AES/GCM/NoPadding(key, iv, plaintextQueryString)   // 128-bit tag
ENC    = eph.pub(32) || iv(12) || ct||tag   (base64)
```

### `H` param — key-attestation HMAC

`RetryingHttpClient.java:297-315` (`RegistrationBodyBuilder/signWithKeyAttestation`):
`H = C1DC.A07(bodyBytes, key = C1DB.A0I())` — HMAC using a locally generated key persisted by
`C1DB` (SharedPreferences "keystore" / AndroidKeyStore).

### Constants
- **ES3 salt (Base64)** — `p000X/ES3.java:7`:
  `PkTwKSZqUfAUyR0rPQ8hYJ0wNsQQ3dW1+3SCnyTXIfEAxxS75FwkDf47wNv/c8pP3p0GXKR6OOQmhyERwx74fw1RYSU10I4r1gyBVDbRJ40pidjM41G1I1oN`
- **ES4 server X25519 public key (hex)** — `p000X/ES4.java:7-11`:
  `8e8c0f74c3ebc5d7a6865c6c3c843856b06121cce8ea774d22fb6f122512302d`
- App-pinned token inputs to extract from `apk-analysis/whatsapp/apks`: `com.whatsapp` packageName,
  `res/drawable-hdpi/about_logo.png` bytes, WhatsApp signing-cert bytes, `MD5(classes.dex)`, exact
  app version. All four must come from the **same** APK build that `WA_VERSION` claims.

## Registration `bad_param: platform` — SOLVED via un-superpacking the native lib

**Update (resolved):** The earlier hypothesis that `platform` is an opaque native param was WRONG.
`split_config.arm64_v8a.apk/lib/arm64-v8a/libs.so` is a thin ELF whose `.data` is a **superpack
archive** (exports `_superpack_archive_start/_end/_size`) of ~140 **XZ-compressed** `.so` files. The
real native lib is `libwhatsappmerged.so` (XZ stream #0, ~6.9 MB decompressed). Un-superpacking it
(scan for XZ magic `FD 37 7A 58 5A 00`, `lzma.LZMADecompressor(FORMAT_XZ)` per stream — see
`apk-analysis/whatsapp/unpack_superpack.py`) exposes plaintext strings.

Dumping the decompressed lib's registration param-key cluster (around the `backup_token` string)
gives WhatsApp's **complete** `/v2/*` param key set:
```
advertising_id, method, context, clicked_education_link, manage_call_permission,
call_log_permission, token, client_start_message, code, auth_response,
encrypted_verifier_data, email, reset, wipe, wipe_token, consent, current_screen,
previous_screen, action_taken, device_exp_id, fdid, expid, access_session_id,
backup_token, authkey, e_keytype, e_regid, e_ident, e_skey_id, e_skey_val, e_skey_sig
```
and the response-reason strings (`sent`, `fail`, `bad_token`, `bad_param`, `missing_param`,
`old_platform`, `too_many`, ...).

**`platform` is NOT a form parameter anywhere in WhatsApp's registration.** Therefore the server
error `{"param":"platform","reason":"bad_param"}` is the server failing to **derive the client
platform from the `User-Agent`** — our bug was mangling the UA with `.replace(' ', '_')` on the whole
string (destroying the `WhatsApp/<v> Android/<os> Device/<dev>` separators). Fix: correct the UA
(sanitize only the device token) and do NOT send a `platform` form param. Confirmed the real reg
host is `v.whatsapp.net` (also `vx.whatsapp.net`), plain HTTPS.

Tooling used: radare2 6.2.0 (`r2blob.static.exe`) confirmed `libs.so` exports the superpack symbols
and has no plaintext JNI strings (packed); Python `lzma` un-superpacked it; the decompressed
`libwhatsappmerged.so` has the plaintext param table above.

## Verification pass (cross-checked communicate impl vs. APK) — fixes applied

After un-superpacking, the reproduced client was cross-checked against the APK + the companion
reference. Results:

- **token attestation** — VALIDATED live (server accepted it; no `bad_token`). Algorithm/constants
  correct.
- **`id` / `expid` / `backup_token` / e2e-bundle encoding** — match `C34244EyE`
  (`A05`=percent-encode, `A03`=UUID→16B→urlsafe-b64, `A04`=urlsafe-b64) and the companion's
  `e_regid`(4B) / `e_keytype`(0x05) / `e_skey_id`(3B via `copyOfRange(1,4)`).
- **`platform`** — CONFIRMED not a form param anywhere in WhatsApp; server derives it from the
  User-Agent. Removed the bogus `platform=android`; fixed UA mangling.
- **`registrationId`** — FIXED: was `1..0xFFFFFE`; the reference uses `random.nextInt(0x3FFF)+1`
  (14-bit Signal id) and `signedPreKeyId = 1`. Now matches.
- **crypt15 backup** — key derivation was a guess; made **self-verifying**: the decryptor tries the
  candidate HKDF/expand-only/raw derivations and lets AES-256-GCM's auth tag select the correct one
  (see `backup/Crypt15Decryptor.kt`).
- **endpoints** — `v.whatsapp.net` (registration REST) and `g.whatsapp.net` (mobile Noise) both
  confirmed present in the DEX (`e1..e16.whatsapp.net` are edge fallbacks). Noise port 443 is the
  standard default (not a distinct literal); revisit in the Phase-4 login spike.
- **method** values `sms`/`voice`(/`flash`) confirmed in `RequestCodeRepository`.

Still pending LIVE validation (only reachable once registration returns `sent`): the primary Noise
`ClientPayload` (`passive`/`pull`), the raw-socket endpoint/port, and the call-signaling stanza
shape (Phase 10 spike).

---

# communicate — WhatsApp Primary Client Implementation Guide (WORKING)

This section documents the *working* reimplementation of WhatsApp as a **primary client**
(own phone-number registration, like a fresh install — NOT a QR/companion) inside the
`communicate` app. Everything below was verified live on a real number (US, +1 650‑398‑8058,
device density xxhdpi, WhatsApp v2.26.29.73 / versionCode 262907320).

## 0. Status — what works live

- ✅ **Registration** (`/v2/code` → SMS → `/v2/register` → `status:ok`). Takes over the number
  (deregisters the real WhatsApp app on the other device — expected for a primary client).
- ✅ **Noise login** to `g.whatsapp.net:443` (XX handshake, `<success>`, prekey upload, offline
  sync, keepalives).
- ✅ **1:1 message send** (usync device list → Signal fan‑out → `pkmsg`/`msg` → server `<ack>`),
  with local outgoing echo.
- ✅ **1:1 message receive** (inbound decrypt → event → Room → conversation view).
- ✅ **Read receipts** (outgoing: sending `read` when the conversation is opened).
- ✅ **Contact picker + as‑you‑type / E.164 normalization** for choosing recipients.
- ⚠️ Not yet exercised live: media send/receive, groups, reactions/polls/edits/revokes UI round‑trip,
  inbound receipt→tick UI (sent/delivered/read state on our own messages), calls (Phase‑10 spike only).

## 1. Architecture / file map (all under `communicate/src/main/`)

- `data/whatsapp/registration/`
  - `WhatsAppRegistrationConstants.kt` — pinned constants (package, salt, dex md5, server X25519
    pubkey, asset paths, host `v.whatsapp.net`).
  - `RegistrationAttestation.kt` — `computeToken` (PBKDF2‑HMAC‑SHA1 + HMAC‑SHA1), `encryptQueryString`
    (ENC), `signWithAttestation` (H).
  - `RegEncoding.kt` — the exact `C34244EyE` encoders: `b64Url` (flag 11), `uuidToBytes`,
    `percentEncode` (EPJ.A00).
  - `WhatsAppDeviceFingerprint.kt` — persisted fdid (UUID), expid (UUID), recovery `id` (16B),
    `backup_token` (20B), attestation key (32B).
  - `RegistrationKeys.kt` — identity/noise/signed‑prekey generation + the `/v2/*` E2E key bundle.
  - `RegistrationHttpClient.kt` — the `/v2/code|register|security|exist` client (param builder +
    query encoder + User‑Agent + response parsing).
- `data/whatsapp/transport/`
  - `WhatsAppSocket.kt` — raw TCP + Noise_XX_25519_AESGCM_SHA256 to `g.whatsapp.net:443`,
    length‑framed, `send()` is `synchronized`.
  - `PrimaryClientPayload.kt` — primary (non‑companion) `ClientPayload`.
- `data/whatsapp/WhatsAppClient.kt` — the ported message engine (stanza dispatch, login,
  encrypt/decrypt, usync fan‑out, receipts, history sync). Pairing/QR/ADV removed.
- `data/whatsapp/WhatsAppLineSession.kt` — DataStore‑backed line session (signed‑in flag, phone).
- `data/whatsapp/WhatsAppDatabase.kt` — Room DB `communicate_whatsapp.db` (E2E stores + message/
  reaction/conversation cache).
- `data/whatsapp/WhatsAppEventProcessor.kt` — drains `WhatsAppClient.events` into Room.
- `data/whatsapp/WhatsAppServiceData.kt` — JSON blob for rich features on `SmsMessage.serviceData`.
- `data/whatsapp/e2e/` + `src/main/rust/` (crate `communicate_signal`) — Signal primitives (JNI).
- `data/CommunicateRepository.kt` — merges WhatsApp into the SIM/GV inbox model; send/receive/
  read/normalization glue.
- `ui/whatsapp/WhatsAppRegistrationScreen.kt`, `ui/MessagesScreen.kt` (contact picker),
  `ui/ConversationScreen.kt`, `telephony/WhatsAppSyncService.kt` (FGS).

## 2. Registration — the full recovered contract

**Endpoints** (plain HTTPS, host `v.whatsapp.net`; `g.whatsapp.net`/`e1..e16.whatsapp.net` are the
Noise hosts): `POST /v2/code`, `/v2/register`, `/v2/security`, `/v2/exist`. Body is
`application/x-www-form-urlencoded`. The official client tries an `ENC`/`H` wrapper and falls back to
plain — plain is accepted, so we default to plain.

**Param encoders** (mirror `C34244EyE`; see `RegEncoding.kt`):
- `A00(k,int)` → literal `"true"`/`"false"` (only 0/1).
- `A01(k,String)` → plain.
- `A02(k,String)` → plain if non‑null.
- `A03(k,uuid)` → `b64Url(uuidToBytes(uuid))` (URL‑safe base64, no pad; flag 11).
- `A04(k,bytes)` → `b64Url(bytes)`.
- `A05(k,bytes)` → `percentEncode(bytes)` (RFC‑3986, keeps `-._~`), stored **pre‑encoded** (never
  re‑URL‑encoded at query build).
- `A06(map)` → per‑entry `percentEncode(byte[])`.

**`/v2/code` param set** (what we send): `cc`, `in`, `lg`, `lc`, `fdid`(A01 UUID string),
`expid`(A03), `id`(A05), `backup_token`(A05), `token`(A01), `method`(sms/voice), the three
`*_permission`/`clicked_education_link` booleans (A00), the 7‑field E2E bundle
`authkey/e_ident/e_keytype/e_regid/e_skey_id/e_skey_val/e_skey_sig` (A04 url‑safe base64), plus the
device fields `mcc/mnc/sim_mcc/sim_mnc/network_radio_type/simnum/hasinrc/pid/rc`.
**`platform` is NOT a form param** — the server derives it from the **User‑Agent**.

**User‑Agent (critical):** `WhatsApp/2.26.29.73 Android/<osrel> Device/<mfr>-<model>` — the three
space separators MUST be preserved (only the device token is sanitized). A wrong UA →
`{"param":"platform","reason":"bad_param"}`.

**`token` (the attestation gate).** Recovered as pure Java (`C34029EuU.A01`):
```
token = Base64_STANDARD(               // AbstractC169977f8.A0n = Base64 flag 2 (NOT url-safe!)
          HMAC-SHA1(
            key = PBKDF2WithHmacSHA1And8BIT(
                    password = "com.whatsapp".bytes || about_logo.png bytes,
                    salt = base64.decode(ES3),  iterations = 128,  keyLen = 512 bits),
            msg = signingCertDER || MD5(classes.dex) || phoneNationalDigits))
```
Key details that each caused a real failure until fixed:
- Base64 variant is **standard w/ padding** (flag 2, `A0n`), not url‑safe/no‑pad (flag 11, `A0o`,
  which is only for `expid` + the E2E bundle). Wrong → `param:token, bad_format`.
- The phone fed to the token is the **NATIONAL number only** (`ES2.A00.A01(app, $phoneNumber)`;
  `$countryCode` is separate). Wrong (cc+national) → `bad_token`.
- `PBKDF2WithHmacSHA1And8BIT` ≡ PBKDF2‑HMAC‑SHA1 with the password bytes used directly (the "8BIT"
  conversion is `char & 0xFF`), so a manual PBKDF2 over the raw bytes is byte‑exact.
- `about_logo.png` is **density‑specific** (lives in `split_config.<density>.apk`); the token uses
  the device's density variant. We bundle the xxhdpi one; on a different‑density device you must
  bundle that density's logo (or read it from the installed split).
- `registrationId` = `random(0x3FFF)+1` (14‑bit Signal id), `signedPreKeyId = 1`.

**`/v2/register` success may omit `new_jid`** (for `type:"existing"` re‑registration it returns
`login` + `lid` instead). Treat `status:ok` as success and derive the JID from `login`
(`<login>@s.whatsapp.net`); capture `lid`.

**Live journey (each was a distinct fix):** `bad_param:platform` (UA) → `bad_param:id`
(double‑encoding) → `bad_format:token` (base64 variant) → `bad_token` (national number) →
`register:ok`.

## 3. Un‑superpacking the native lib (how the constants were confirmed)

`split_config.arm64_v8a.apk/lib/arm64-v8a/libs.so` is a thin ELF whose `.data` is a **superpack
archive** (exports `_superpack_archive_start/_end/_size`) of ~140 **XZ‑compressed** `.so` files
(one is `libwhatsappmerged.so`). To read plaintext strings/param tables:
```
# scan for XZ magic FD 37 7A 58 5A 00; decompress each stream with Python stdlib lzma
python apk-analysis/whatsapp/unpack_superpack.py   # -> unpacked/stream_0_*.so (libwhatsappmerged)
```
radare2 6.2.0 (`r2blob.static.exe`) confirms `libs.so` has only the 3 superpack exports; the real
symbols/strings live in the decompressed stream. This is how we confirmed the full param‑key table
and that `platform` is not a form param.

## 4. Noise transport & login (`WhatsAppSocket` + `WhatsAppClient`)

- Raw TCP to **`g.whatsapp.net:443`**, then Noise_XX_25519_AESGCM_SHA256: `ClientHello` →
  `ServerHello` (verify server static cert) → `ClientFinish` (carrying the encrypted
  `ClientPayload`). Frames are 3‑byte big‑endian length prefixed.
- `PrimaryClientPayload`: platform ANDROID, `passive=false`, `pull=false`, app version
  `2.26.29.73`. On success the server returns `<success ... lid=... >`; we then upload prekeys,
  run offline sync, and send keepalives.
- `WhatsAppSocket.send()` is `synchronized` (per‑frame AES‑GCM counter must not interleave).

## 5. Message send

Path: `ConversationScreen → CommunicateRepository.sendMessage(LineChoice.WhatsApp) →
WhatsAppClient.sendMessage(jid, body)`.
- **Threading (critical):** all WhatsApp socket work MUST run on `Dispatchers.IO`. The UI uses
  Compose's Main‑dispatcher `rememberCoroutineScope()`, so the repository wraps every WhatsApp call
  in `withContext(Dispatchers.IO)`. A main‑thread `ws.send` throws `NetworkOnMainThreadException`
  mid‑frame → desyncs the Noise counter → `<stream:error>{bad-mac}` → disconnect.
- **Recipient JID normalization (critical):** the typed number must be full **E.164 (with country
  code, no `+`)** or `usync` returns 0 devices and the message goes nowhere. `toWhatsAppJid` uses
  libphonenumber with the SIM/locale region (`2134774209` → `12134774209@s.whatsapp.net`).
- Flow: `usync` device list → Signal fan‑out to recipient devices + our own other devices (skipping
  our primary) → `pkmsg` (new session, includes identity) or `msg` → server `<ack class=message>`.
- **Outgoing echo:** a primary‑only line gets no server echo of its own sends, so on success we cache
  the outgoing message locally (`cacheOutgoingWhatsApp`) so it shows in our thread.

## 6. Message receive & the event pipeline

Inbound stanza → `WhatsAppClient` decrypt → `WhatsAppEvent` (SharedFlow) → `WhatsAppEventProcessor`
→ Room → UI (2s foreground poll of the local cache while a conversation is open; 15s poll of the
thread list).
- **JID normalization (critical):** the client tags inbound `conversationId = "wa:<jid>"` (a
  messages‑module convention). The processor's `chatJid()` strips the `wa:` prefix **and** any
  `:device` suffix (`user:5@server → user@server`) so inbound + outgoing land in the same
  conversation. Without this, `jidLocalPart("wa:…")` renders as the literal **"wa"** and creates a
  phantom thread.
- **Unread de‑dupe:** unread is incremented only for a genuinely new `messageId` (the same stanza
  can arrive live + via offline replay), and cleared to 0 when the conversation is opened.
- Timestamps are milliseconds everywhere (inbound `t*1000`, outgoing `currentTimeMillis`).

## 7. Read receipts

`CommunicateRepository.markWhatsAppRead` (called from `ConversationScreen` on open + each poll,
guarded by the last‑read message id): clears the local unread badge and sends a `read` receipt for
the latest inbound message via `WhatsAppClient.sendReadReceipt(jid, msgId, t/1000, participant)`
(participant only for groups). Requires `readReceipts=on` (server reported it on).

## 8. E2E crypto

Signal primitives (X25519, Ed25519/XEdDSA sign, HKDF, AES‑GCM) live in the Rust crate
`communicate_signal` (`src/main/rust/`, JNI symbols under
`Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_`), copied from `messages`
(NOT depended on — repo rule). Session/identity/prekey/sender‑key records persist in Room
(`whatsapp_e2e_*` tables), format‑versioned to the Rust record.

## 9. Bugs found & fixed (chronological)

| Symptom | Root cause | Fix |
|---|---|---|
| crash on open | `WhatsAppClient.stop()` used `appContext` before `init()`, and wiped auth | init‑guard; `stop()` no longer clears creds |
| crash on open (2) | FGS `ACTION_STOP` didn't call `startForeground()` | call `startForeground` first in `onStartCommand` (WA + GV services) |
| `bad_param:platform` | mangled User‑Agent (`.replace(' ','_')` on whole UA) | keep UA separators; `platform` is UA‑derived, not a form param |
| `bad_param:id` | `id`/`backup_token` double‑encoded | percent‑encode once, mark pre‑encoded (A05) |
| `bad_format:token` | url‑safe base64 for token | standard base64 (flag 2, `A0n`) |
| `bad_token` | token used cc+national | token uses **national** number only |
| still "not registered" | register `ok` had no `new_jid` | treat `ok` as success; derive JID from `login`/`lid` |
| send failed / `bad-mac` | socket write on main thread | run all WA calls on `Dispatchers.IO` |
| "sent to nobody" | recipient JID missing country code | libphonenumber E.164 normalization |
| sent msg not shown | new convo `remoteId=null`; no outgoing echo | loader derives JID from address; cache outgoing |
| inbound in "wa" thread | `conversationId="wa:<jid>"` prefix | `chatJid()` strips `wa:`+device suffix |
| unread over‑count (2 for 1) | unread bumped per event incl. replays | bump only for new `messageId`; clear on open |

## 10. Rebuild / install / debug

```
./gradlew.bat :communicate:assembleDebug
adb install -r communicate/build/outputs/apk/debug/communicate-debug.apk
adb logcat -d | findstr "WARegHttp WhatsAppClient WhatsAppSocket WAEventProcessor"
```
Registration debug: `checkExist` ("Check number (no SMS)") logs the computed token and probes
`/v2/exist` without sending an SMS — use it to validate params before spending an SMS.

## 11. Remaining work / caveats

- Media (send/receive), groups, and the rich‑feature round‑trips (reactions/polls/edits/revokes)
  compile but are not yet live‑verified.
- Inbound receipts → sent/delivered/read tick UI on our own messages is not wired.
- `about_logo.png` is pinned to xxhdpi + WhatsApp v2.26.29.73; a WhatsApp version bump or a
  different‑density device requires re‑pinning `CLASSES_DEX_MD5_HEX`, `WA_VERSION`, and the logo.
- Calls are a signaling‑only spike (Phase 10a); no media engine.
- This is an unofficial primary‑client reimplementation: registering deregisters WhatsApp on the
  number's real phone and carries real ToS/ban risk — use a test number.
