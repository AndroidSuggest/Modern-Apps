# Android Auto / Android Automotive — App-to-Car Interface Spec

> **Library:** `androidx.car.app:app-*` **`1.9.0-alpha02`** (9 Sept 2026)
> **Car App API Level:** **9** (`androidx.car.app.versioning.CarAppApiLevels.LEVEL_9`)
> **Release page:** `https://developer.android.com/jetpack/androidx/releases/car-app#1.9.0-alpha02`
> **Sources used:** `androidx/car/app/{app,app-automotive,app-projected,app-testing}/1.9.0-alpha02/*-sources.jar`
> from `https://dl.google.com/dl/android/maven2/androidx/car/app/`
> (325 `.java` files; this file is generated purely from those sources, not from any repo checkout)
>
> **Artifacts:**
>
> | Artifact | Gradle coordinate | Contains |
> |---|---|---|
> | `app` | `androidx.car.app:app:1.9.0-alpha02` | ALL core framework: service, session, screen, context, templates, models, managers |
> | `app-projected` | `androidx.car.app:app-projected:1.9.0-alpha02` | Android Auto / CarPlay projection: `ProjectedCarHardwareManager`, `ProjectedCarAudioRecord` |
> | `app-automotive` | `androidx.car.app:app-automotive:1.9.0-alpha02` | Android Automotive OS: `CarAppActivity` stack, `AutomotiveCarHardwareManager`, `AutomotiveCarAudioRecord` |
> | `app-testing` | `androidx.car.app:app-testing:1.9.0-alpha02` | `TestCarContext`, `TestScreenManager`, `FakeHost`, etc. |
>
> Dependencies:
>
> ```groovy
> implementation "androidx.car.app:app:1.9.0-alpha02"             // always
> implementation "androidx.car.app:app-projected:1.9.0-alpha02"  // Android Auto specific
> implementation "androidx.car.app:app-automotive:1.9.0-alpha02" // Automotive OS specific
> testImplementation "androidx.car.app:app-testing:1.9.0-alpha02"
> ```

---

## Table of contents

- [0. How an app talks to the car (mental model)](#0-how-an-app-talks-to-the-car-mental-model)
- [1. Manifest, service entry, host trust, versioning](#1-manifest-service-entry-host-trust-versioning)
- [2. Core runtime: Session / Screen / CarContext / managers](#2-core-runtime-session--screen--carcontext--managers)
- [3. Shared UI vocabulary (all app types)](#3-shared-ui-vocabulary-all-app-types)
- [4. Template × app-type matrix](#4-template--app-type-matrix)
- [5. Navigation apps](#5-navigation-apps-navigation)
- [6. POI / Parking / Charging apps](#6-poi--parking--charging-apps-poi)
- [7. Media apps](#7-media-apps-media-experimental-stable-hybrid)
- [8. Messaging apps](#8-messaging-apps-messaging)
- [9. Calling / Dialer apps](#9-calling--dialer-apps-calling)
- [10. Weather apps](#10-weather-apps-weather)
- [11. IoT apps](#11-iot-apps-iot)
- [12. Settings / sign-in / search / suggestions / notifications](#12-settings--sign-in--search--suggestions--notifications)
- [13. Vehicle hardware (all app types, API-gated)](#13-vehicle-hardware-all-app-types-api-gated)
- [14. Automotive-only vs Projected-only](#14-automotive-only-vs-projected-only)
- [15. Testing surface](#15-testing-surface)
- [16. Permissions, constraints, quotas](#16-permissions-constraints-quotas)
- [Appendix A: 1.9.0-alpha02 + alpha01 changelog](#appendix-a-190-alpha02--alpha01-changelog)
- [Appendix B: Full class index](#appendix-b-full-class-index)

---

## 0. How an app talks to the car (mental model)

```
Phone / head-unit app process              Car host process (Auto / AAOS / CP)
─────────────────────────────              ────────────────────────────────────
CarAppService (manifest-declared)
  └─ Session (per display: MAIN + CLUSTER)
       └─ Screen stack (ScreenManager push/pop)
            └─ Screen.onGetTemplate() ──Binder IPC──▶ host renders template
CarContext.getCarService(XxxManager) ◀──Binder IPC── host events (nav stop,
  AppManager / NavigationManager /            pan, alert cancel, search text,
  ConstraintManager / CarHardwareManager /    voice reply, keypad, etc.)
  SuggestionManager / MediaPlaybackManager
SurfaceCallback ──Surface──▶ app draws map itself (nav/POI only)
Notifications (CarAppExtender) ──▶ host HUN / rail / TBT
MediaSession token ──▶ host media UI (out-of-band via MediaCompat/Media3)
CarHardwareManager ──▶ vehicle properties (AAOS: VehiclePropertyIds; projected: host dispatch)
```

Rules that apply to **every** app type:

1. You never draw car UI directly (except a map `Surface`). You return **templates**
   (`androidx.car.app.model.*`, `navigation.model.*`, `media.model.*`, `dialer.*`,
   `model.signin.*`) from `Screen.onGetTemplate()` and the host renders them.
2. You never call the host directly. You call **managers** obtained from
   `CarContext.getCarService(...)` and receive host callbacks through delegates.
3. Everything is **API-level gated**. `CarContext.getCarAppApiLevel()` returns the
   negotiated level (1–9). Guard every `@RequiresCarApi(n)` call.
4. Every template/model validates client-side (`validateOrThrow`) — illegal combos throw
   before IPC. Host limits (list counts) come from `ConstraintManager.getContentLimit()`.
5. The task stack quota: max **5 templates per task**, last must be
   `NavigationTemplate | PaneTemplate | MessageTemplate`. Refreshes
   (same type + same main content) don't count; pops restore quota;
   `NavigationTemplate` resets quota.

---

## 1. Manifest, service entry, host trust, versioning

### 1.1 `androidx.car.app.CarAppService` (abstract, `app`)

The single entry point. The host binds to it.

```xml
<service android:name=".YourAppService" android:exported="true">
  <intent-filter>
    <action android:name="androidx.car.app.CarAppService" />
    <!-- exactly one (or more) of the categories below -->
    <category android:name="androidx.car.app.category.NAVIGATION" />
  </intent-filter>
</service>
<!-- required: declares oldest API your app understands -->
<meta-data android:name="androidx.car.app.minCarApiLevel" android:value="1" />
<!-- optional: theme for permission UI + brand opt-in (API 9) -->
<meta-data android:name="androidx.car.app.theme" android:resource="@style/CarAppTheme" />
```

Constants on `CarAppService`:

| Constant | Value | Since |
|---|---|---|
| `SERVICE_INTERFACE` | `androidx.car.app.CarAppService` | 1 |
| `CATEGORY_NAVIGATION_APP` | `androidx.car.app.category.NAVIGATION` | 1 |
| `CATEGORY_POI_APP` | `androidx.car.app.category.POI` | 1 (replaces parking/charging) |
| `CATEGORY_PARKING_APP` | `androidx.car.app.category.PARKING` | **@Deprecated** → use `POI` |
| `CATEGORY_CHARGING_APP` | `androidx.car.app.category.CHARGING` | **@Deprecated** → use `POI` |
| `CATEGORY_IOT_APP` | `androidx.car.app.category.IOT` | API 6 |
| `CATEGORY_SETTINGS_APP` | `androidx.car.app.category.SETTINGS` | API 6 |
| `CATEGORY_FEATURE_CLUSTER` | `androidx.car.app.category.FEATURE_CLUSTER` | API 6 |
| `CATEGORY_MESSAGING_APP` | `androidx.car.app.category.MESSAGING` | `@ExperimentalCarApi` |
| `CATEGORY_CALLING_APP` | `androidx.car.app.category.CALLING` | `@ExperimentalCarApi` |
| `CATEGORY_WEATHER_APP` | `androidx.car.app.category.WEATHER` | API 7 |
| `CATEGORY_MEDIA_APP` | `androidx.car.app.category.MEDIA` | API 8 |

Methods to override / final:

```java
public abstract HostValidator createHostValidator();
public Session onCreateSession();                                        // legacy
public Session onCreateSession(SessionInfo sessionInfo);                // API 6, per-display
@RequiresCarApi(9) @ExperimentalCarApi
public @ThemeSource int getCarAppThemeSource(); // default THEME_SOURCE_SYSTEM
// @IntDef: THEME_SOURCE_SYSTEM = 0, THEME_SOURCE_APP = 1
public final IBinder onBind(Intent intent);      // do NOT override
public final boolean onUnbind(Intent intent);    // returns true (asks onRebind)
public void onDestroy();                         // @CallSuper
public final HostInfo getHostInfo();
public final Session getSession(SessionInfo sessionInfo);
@Deprecated public final Session getCurrentSession();
```

 host `dump(AUTO_DRIVE)` → `CarAppBinder.onAutoDriveEnabled()` (AutoDrive test harness).

### 1.2 `androidx.car.app.validation.HostValidator` (`app`)

```java
TEMPLATE_RENDERER_PERMISSION = "android.car.permission.TEMPLATE_RENDERER";
ALLOW_ALL_HOSTS_VALIDATOR; // debug only!
boolean isValidHost(HostInfo host);
Map<String,List<String>> getAllowedHosts();
new HostValidator.Builder(context)
    .addAllowedHost("com.google.android.projection.gearhead", "<sha256>")
    .addAllowedHosts(R.array.hosts_allowlist_sample) // "digest,package" lines
    .build();
```

Semantics: same-UID → allow; allowlisted (package + SHA-256 DER lowercase hex) → allow;
`SYSTEM_UID` → allow; holds `TEMPLATE_RENDERER` (automotive API 31+) → allow.

### 1.3 `androidx.car.app.AppInfo`, `SessionInfo`, `HostInfo`, `HandshakeInfo`

```java
// AppInfo (@CarProtocol @KeepFields)
MIN_API_LEVEL_METADATA_KEY = "androidx.car.app.minCarApiLevel";
static AppInfo create(Context ctx); // throws if minCarApiLevel missing
int getMinCarAppApiLevel(); int getLatestCarAppApiLevel(); String getLibraryDisplayVersion();

// SessionInfo (@RequiresCarApi(6))
DISPLAY_TYPE_MAIN = 0; DISPLAY_TYPE_CLUSTER = 1; // cluster: NavigationTemplate only
SessionInfo(@DisplayType int displayType, String sessionId);
String getSessionId(); int getDisplayType();
List<Class<? extends Template>> getSupportedTemplates(@CarAppApiLevel int apiLevel);
DEFAULT_SESSION_INFO;

// HostInfo — HostInfo(String pkg, int uid); getPackageName(); getUid()
// HandshakeInfo — getHostCarAppApiLevel()
// SessionInfoIntentEncoder — containsSessionInfo(Intent); decode(Intent)
```

### 1.4 `androidx.car.app.versioning.CarAppApiLevels` / `CarAppApiLevel`

`CarAppApiLevel` is an `@IntDef{UNKNOWN, LEVEL_1..LEVEL_9}`.
`CarAppApiLevels`: `LEVEL_1=1 … LEVEL_9=9`, `UNKNOWN=0`,
`static int getLatest()`, `getOldest()=1`, `isValid(int)`.

| Level | What it unlocked |
|---|---|
| 1 | core services + parking/charging/nav templates |
| 2 | `SignInTemplate`, `LongMessageTemplate`, multi-variant `CarText`, `ConstraintManager`, `setCarAppResult`, map `ActionStrip`, pan/zoom |
| 3 | `CarHardwareManager` (`HARDWARE_SERVICE`) |
| 4 | AAOS, QR sign-in, `PlaceListMapTemplate.setCurrentLocationEnabled`, contrast check |
| 5 | voice/mic, `Alert`/`showAlert`, map-pane detail, responsive turn cards, map tap, nav-list non-places, POI zoom/pan, `OnContentRefreshListener` |
| 6 | cluster `SessionInfo`, `TabTemplate`, list FAB `addAction`, `Row.addAction/numericDecoration`, app-driven refresh flag |
| 7 | `Header`, `MapWithContentTemplate` (replaces `MapTemplate`/`PlaceListNavigationTemplate`/`RoutePreviewNavigationTemplate`), `GridTemplate.addAction/itemSize`, `COMPOSE_MESSAGE` |
| 8 | `SectionedItemTemplate` (+ Row/Grid/Chip/Condensed/Spotlight/Banner sections), `MediaPlaybackTemplate`, `MediaPlaybackManager.registerMediaPlaybackToken`, `TYPE_MEDIA_PLAYBACK`, `GridItem.Badge`, `Row.endImage`, indexable rows |
| **9** (this release, mostly `@ExperimentalCarApi`) | `THEME_SOURCE_SYSTEM/APP` + `getCarAppThemeSource()`; `Header.subtitle/background/startHeaderImage`; `SearchHeader` on `SectionedItemTemplate`; `TabStyle` + `TabTemplate.setStyle/setEndAction`; `Banner/BannerStyle/BannerElement/BannerSection`; `Background/Shape`; `CarIconStyle.setShape`; `MediaPlaybackStyle` + `MediaPlaybackTemplate.setBanner/setStyle`; `PaneTemplate.setBanner`; `CarProgressBar` on `Row/GridItem`; `NavigationManager.canSetVoiceAssistantCapabilities/setVoiceAssistantCapabilities` |

Annotations: `annotations.RequiresCarApi`, `annotations.ExperimentalCarApi`,
`annotations.CarProtocol`, `annotations.KeepFields`.

### 1.5 `androidx.car.app.features.CarFeatures` + `connection.CarConnection`

```java
// CarFeatures — PackageManager.hasSystemFeature wrapper
FEATURE_BACKGROUND_AUDIO_WHILE_DRIVING = "com.android.car.background_audio_while_driving";
FEATURE_CAR_APP_LIBRARY_MEDIA = "android.software.car.templates_host.media";
static boolean isFeatureEnabled(Context ctx, String feature);
// NOTE: no FEATURE_PARKING/CHARGING/IOT/WEATHER exists — gating is via categories.

// CarConnection — Auto vs AAOS detection
CAR_CONNECTION_STATE = "CarConnectionState";
ACTION_CAR_CONNECTION_UPDATED = "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED";
CONNECTION_TYPE_NOT_CONNECTED = 0; CONNECTION_TYPE_NATIVE = 1; // AAOS
CONNECTION_TYPE_PROJECTION = 2;                                // AA / CP
new CarConnection(ctx).getType() : LiveData<Integer>
```

---

## 2. Core runtime: Session / Screen / CarContext / managers

### 2.1 `androidx.car.app.Session implements LifecycleOwner` (`app`)

```java
public Session();
public abstract Screen onCreateScreen(Intent intent); // first screen; may pre-push
public void onNewIntent(Intent intent);               // default no-op
public void onCarConfigurationChanged(Configuration cfg);
public Lifecycle getLifecycle(); // ON_CREATE→onCreateScreen; START=visible; RESUME=interactive
public final CarContext getCarContext(); // valid iff State >= CREATED
```

### 2.2 `androidx.car.app.Screen implements LifecycleOwner` (`app`)

```java
protected Screen(CarContext carContext);
public abstract Template onGetTemplate();
public final void invalidate(); // → AppManager.invalidate(); no-op if State < STARTED
public final void finish();     // → ScreenManager.remove(this); no-op if root
public void setResult(Object result);
public String getMarker(); public void setMarker(String marker); // popTo(marker)
public final CarContext getCarContext();
public final ScreenManager getScreenManager();
public final Lifecycle getLifecycle();
```

`onGetTemplate()` quota (enforced host-side, documented on `Screen`):
max 5 templates/task; last must be `NavigationTemplate|PaneTemplate|MessageTemplate`;
refresh = same type + same main content (not counted); pop restores quota;
`NavigationTemplate` resets quota; notification/launcher intent resets quota;
host throttles rapid `invalidate()`.

### 2.3 `androidx.car.app.ScreenManager implements Manager` (`app`)

`CarContext.SCREEN_SERVICE = "screen"`.

```java
Screen getTop(); int getStackSize(); Collection<Screen> getScreenStack();
void push(Screen screen); // move-to-top if present; single-use (DESTROYED throws)
void pushForResult(Screen screen, OnScreenResultListener listener);
void pop();               // no-op if size<=1; new top reuses last template id
void popTo(String marker);
void popToRoot();
void remove(Screen screen); // direct remove → immediate ON_DESTROY
```

### 2.4 `androidx.car.app.CarContext extends ContextWrapper` (`app`)

```java
// service names
APP_SERVICE = "app";                 // AppManager
NAVIGATION_SERVICE = "navigation";   // NavigationManager
SCREEN_SERVICE = "screen";           // ScreenManager
CONSTRAINT_SERVICE = "constraints";  // ConstraintManager (API 2)
CAR_SERVICE = "car";                 // internal
HARDWARE_SERVICE = "hardware";       // CarHardwareManager (API 3)
SUGGESTION_SERVICE = "suggestion";   // SuggestionManager (API 5)
MEDIA_PLAYBACK_SERVICE = "media_playback"; // MediaPlaybackManager (API 8)

Object getCarService(String name);
<T> T getCarService(Class<T> clz);
String getCarServiceName(Class<?> clz);

void startCarApp(Intent intent); // ACTION_NAVIGATE geo:…, ACTION_DIAL/ACTION_CALL tel:…, own CarAppService
void finishCarApp();
static void startCarApp(Intent notifIntent, Intent appIntent); // @Deprecated → CarPendingIntent
@RequiresCarApi(2) void setCarAppResult(int resultCode, Intent data); // AAOS only (Auto: no-op)
@RequiresCarApi(2) ComponentName getCallingComponent();               // AAOS only (Auto: null)
boolean isDarkMode(); // nav apps MUST redraw map; change via Session.onCarConfigurationChanged
OnBackPressedDispatcher getOnBackPressedDispatcher(); // default: pop
int getCarAppApiLevel(); // throws if handshake incomplete
HostInfo getHostInfo();
void requestPermissions(List<String> perms, OnRequestPermissionsListener l);
void requestPermissions(List<String> perms, Executor ex, OnRequestPermissionsListener l);
// branding: androidx.car.app.theme → carPermissionActivityLayout
EXTRA_START_CAR_APP_BINDER_KEY;
ACTION_NAVIGATE = "androidx.car.app.action.NAVIGATE"; // geo:lat,lng · geo:0,0?q=addr
```

### 2.5 `androidx.car.app.AppManager` (`APP_SERVICE`, `app`)

```java
void setSurfaceCallback(SurfaceCallback cb); // needs ACCESS_SURFACE; main thread; null to clear
void invalidate();                            // re-query onGetTemplate()
void showToast(CharSequence text, @CarToast.Duration int duration);
@RequiresCarApi(5) void showAlert(Alert alert);   // nav templates only
@RequiresCarApi(5) void dismissAlert(int alertId);
// @RestrictTo(LIBRARY) openMicrophone(OpenMicrophoneRequest) — voice entry
```

`androidx.car.app.SurfaceCallback`:

```java
void onSurfaceAvailable(SurfaceContainer sc); // MUST Surface#release() when done
void onVisibleAreaChanged(Rect r); void onStableAreaChanged(Rect r);
void onSurfaceDestroyed(SurfaceContainer sc);
@RequiresCarApi(2) void onScroll(float dx, float dy);
@RequiresCarApi(2) void onFling(float vx, float vy);
@RequiresCarApi(2) void onScale(float fx, float fy, float scale);
@RequiresCarApi(5) void onClick(float x, float y); // touch not universal — always offer zoom strip
```

`androidx.car.app.SurfaceContainer`: `getSurface()`, `getWidth/Height/Dpi()`.

`androidx.car.app.CarToast`: `LENGTH_SHORT/LONG`,
`makeText(CarContext, CharSequence|@StringRes, int).setText().setDuration().show()`.

---

## 3. Shared UI vocabulary (all app types)

### 3.1 `androidx.car.app.model.Action` (`@CarProtocol`, `app`)

Types: `TYPE_CUSTOM=1`, `TYPE_APP_ICON`, `TYPE_BACK`, `TYPE_PAN`,
`TYPE_COMPOSE_MESSAGE` (API 7), `TYPE_MEDIA_PLAYBACK` (API 8; **API 9: row-only, FAB ignored**),
`TYPE_IN_CALL_HEADER` (dialer). Flags: `FLAG_PRIMARY` (API 4),
`FLAG_IS_PERSISTENT` / `FLAG_DEFAULT` (API 5).
Singletons: `APP_ICON / BACK / PAN / COMPOSE_MESSAGE / MEDIA_PLAYBACK`.

```java
CarText getTitle(); CarIcon getIcon(); CarColor getBackgroundColor();
int getType(); int getFlags(); boolean isStandard();
@RequiresCarApi(5) boolean isEnabled();
OnClickDelegate getOnClickDelegate();
new Action.Builder()
  .setTitle(CharSequence|CarText).setIcon(CarIcon)
  .setOnClickListener(OnClickListener) // or ParkedOnlyOnClickListener
  .setBackgroundColor(CarColor).setEnabled(boolean).setFlags(int).build();
```

`ActionStrip`: `getActions()`, `getFirstActionOfType(int)`;
`Builder.addAction(Action)` rejects duplicate non-custom types.

### 3.2 `androidx.car.app.model.Header` (API 5) + `SearchHeader` (API 9)

```java
// Header (@RequiresCarApi(5))
CarText getTitle(); Action getStartHeaderAction(); // APP_ICON|BACK only
List<Action> getEndHeaderActions();
@RequiresCarApi(9) CarText getSubtitle(); Background getBackground(); CarIcon getStartHeaderImage();
new Header.Builder()
  .setTitle(CharSequence|CarText).setStartHeaderAction(Action)
  .addEndHeaderAction(Action) // icon-only inside map content
  .setSubtitle(...)                                    // API 9 experimental
  .setBackground(Background)                           // API 9 experimental
  .setStartHeaderImage(CarIcon custom)                 // API 9 experimental
  .build(); // requires title OR start action

// SearchHeader (@RequiresCarApi(9) @ExperimentalCarApi) — SectionedItemTemplate only
new SearchHeader.Builder(SearchCallback cb)
  .setInitialSearchText(String).setSearchHint(String)
  .setShowKeyboardByDefault(boolean) // default true
  .setStartHeaderAction(Action).setEndHeaderActions(List<Action>).build();
```

`SearchCallback` (shared): `default onSearchTextChanged(String)` (coalesced),
`default onSearchSubmitted(String)`.

### 3.3 Rows / lists / grids / text / icons

```java
// Row implements Item
IMAGE_TYPE_SMALL / IMAGE_TYPE_LARGE; IMAGE_TYPE_EXTRA_SMALL / IMAGE_TYPE_MEDIUM (API 8);
@Deprecated IMAGE_TYPE_ICON → use SMALL/MEDIUM (untinted by default);
NO_DECORATION = -1;
getTitle()/getTexts()/getImage()/getRowImageType();
getEndImage()/getRowEndImageType(); // API 8
getActions(); // API 6, max 2   getNumericDecoration(); // API 6
getToggle(); getOnClickDelegate(); getMetadata(); isBrowsable(); isEnabled(); // API 5
isIndexable(); // API 8
getProgressBar(); // API 9 CarProgressBar
new Row.Builder()
  .setTitle(...).addText(...).setImage(CarIcon[,type]).setEndImage(...)
  .setBrowsable(...).setToggle(Toggle).setOnClickListener(...)
  .setMetadata(Metadata|Place).setEnabled(...).setIndexable(...)
  .setProgressBar(CarProgressBar).addAction(Action).setNumericDecoration(int).build();

// GridItem implements Item
getTitle()/getText()/getImage()/getBadge(); // Badge API 8
isIndexable(); // API 8   getProgressBar(); // API 9 (mutually exclusive with text)
isLoading(); getOnClickDelegate();
new GridItem.Builder().setTitle(...).setText(...).setImage(CarIcon[,Badge])
  .setOnClickListener(...).setIndexable(...).setProgressBar(...).setLoading(...).build();

// Pane — getRows()/getActions()/getImage() (API 4)/isLoading()
// ItemList — getItems()/getSelectedIndex()/getOnSelectedDelegate()/
//   getOnItemVisibilityChangedDelegate()/getNoItemsMessage()
//   OnSelectedListener.onSelected(int); OnItemVisibilityChangedListener.onItemVisibilityChanged(int,int)
// Item (marker); SectionedItemList.create(ItemList, CharSequence header);
// Section<?> + RowSection/GridSection/ChipSection/CondensedSection/SpotlightSection/BannerSection
// CondensedItem (dense 1-line), Chip (+ChipStyle), Toggle, Metadata,
// Place/PlaceMarker/CarLocation, Alert/AlertCallback,
// CarText/CarSpan/DistanceSpan/DurationSpan/ForegroundCarColorSpan/CarIconSpan/ClickableSpan/TimerSpan,
// Distance.create(double,@Unit int) [UNIT_METERS/KILOMETERS/KILOMETERS_P1/MILES/MILES_P1/FEET/YARDS],
// DateTimeWithZone.create(long,int,String|TimeZone|ZonedDateTime),
// CarColor (STANDARD_ONLY vs UNCONSTRAINED), CarIcon (see below)

// CarIcon
Types: CUSTOM/BACK/ALERT/APP_ICON/ERROR/PAN(API2)/COMPOSE_MESSAGE(API7)/MEDIA_PLAYBACK(API8);
Singletons: APP_ICON/BACK/ALERT/ERROR/PAN/COMPOSE_MESSAGE/MEDIA_PLAYBACK;
createTintedIcon(IconCompat) / createOriginalIcon(IconCompat);
new CarIcon.Builder(IconCompat[,CarIconStyle]|CarIcon).setTint[deprecated].setStyle(CarIconStyle).build();

// CarIconStyle — TINTED (default tint) / ORIGINAL (no tint: avatars/art)
//   getTint(); getShape(); // API 9  new Builder(base).setTint(CarColor).setShape(Shape).build()
// Background (API 9 exp) — TRANSPARENT; getColor()/getImage() (exactly one);
//   Builder.setColor(CarColor)/setImage(CarIcon TYPE_CUSTOM).build() (COLOR_ONLY)
// Shape (API 9 exp) — NONE/CORNER_EXTRA_SMALL/SMALL/MEDIUM/LARGE/EXTRA_LARGE/FULL
// CarProgressBar (API 9 exp) — for Row/GridItem; + CarProgressBarStyle/StrokeCap
```

### 3.4 `androidx.car.app.model.Tab / TabContents / TabStyle` (API 6 / style API 9)

```java
// TabContents implements Content — CONTENT_ID="TAB_CONTENTS_CONTENT_ID"
new TabContents.Builder(Template t).build();
// API 6: List|Pane|Grid|Message|Search; API 7: +Navigation; API 8: +SectionedItem
// Tab implements Content — getTitle()/getIcon()/getContentId()/getStyle() (API 9)
//   Builder.setTitle/setIcon/setContentId/setStyle(TabStyle)
// TabStyle (API 9 exp) — getShape()/getSelectedBackgroundColor()/getTextColor()
//   Builder.setShape(Shape).setSelectedBackgroundColor(CarColor).setTextColor(CarColor).build() (≥1)
```

---

## 4. Template × app-type matrix

Base: `androidx.car.app.model.Template` (marker interface).

| Template | Min API | Used by app types |
|---|---|---|
| `model.PaneTemplate` (+`Pane`) | 1 (banner API 9) | NAV, POI, WEATHER, IOT, SETTINGS |
| `model.ListTemplate` (single `ItemList` or `SectionedItemList`s, ≤100 items) | 1 (FAB API 6, Header API 7) | NAV, POI, WEATHER, IOT, MEDIA (browse lists), MESSAGING (conversations) |
| `model.SectionedItemTemplate` (+ Row/Grid/Chip/Condensed/Spotlight/Banner sections) | 8 (`SearchHeader`/styles API 9) | POI, WEATHER, IOT, MEDIA, MESSAGING |
| `model.GridTemplate` (`GridItem`-only) | 1 (actions API 7, size/shape API 8) | POI, WEATHER, IOT, MEDIA |
| `model.MessageTemplate` | 1 | ALL (task end / empty / error) |
| `model.LongMessageTemplate` (parked-only) | 2 | SETTINGS, POI (terms, details) |
| `model.SearchTemplate` | 1 | NAV, POI, MEDIA, MESSAGING |
| `model.PlaceListMapTemplate` (host map + list) | 1 (refresh API 5) | NAV, POI |
| `navigation.model.NavigationTemplate` (active nav) | 1 | NAV only |
| `navigation.model.MapWithContentTemplate` (app map + overlay) | 7 (SectionedItem content API 8) | NAV (+ POI map via `MAP_TEMPLATES`) |
| `navigation.model.MapTemplate` | 5, **@Deprecated** → `MapWithContentTemplate` | NAV |
| `navigation.model.PlaceListNavigationTemplate` | 1, **@Deprecated** | NAV |
| `navigation.model.RoutePreviewNavigationTemplate` | 1, **@Deprecated** | NAV |
| `model.TabTemplate` (2–4 tabs) | 6 (style/endAction API 9) | POI, WEATHER, IOT, MEDIA, NAV |
| `media.model.MediaPlaybackTemplate` | 8 (banner/style API 9) | MEDIA |
| `model.signin.SignInTemplate` (parked-only) | 2 (QR API 4) | ALL (login) |
| `dialer.InCallTemplate` | `@ExperimentalCarApi` | CALLING |
| `dialer.TelephoneKeypadTemplate` | `@ExperimentalCarApi` | CALLING |

`model.TemplateInfo(Class<? extends Template>, String)` / `TemplateWrapper` are host-only.

---

## 5. Navigation apps (`NAVIGATION`)

Manifest: `androidx.car.app.category.NAVIGATION`.
Permissions: `androidx.car.app.NAVIGATION_TEMPLATES` (nav templates),
`androidx.car.app.MAP_TEMPLATES` (`PlaceListMapTemplate` / map side of `MapWithContentTemplate`;
self-drawn nav maps exempt), `androidx.car.app.ACCESS_SURFACE` (custom map surface).

### 5.1 `androidx.car.app.navigation.NavigationManager` (`NAVIGATION_SERVICE`)

```java
NavigationManager mgr = carContext.getCarService(NavigationManager.class);
mgr.setNavigationManagerCallback(cb);              // or (Executor, cb)
mgr.clearNavigationManagerCallback();              // throws if navigating
mgr.navigationStarted();  // idempotent; needs callback; claims single-active-nav
mgr.updateTrip(Trip trip); // only after started; dropped after ended/onStopNavigation
mgr.navigationEnded();    // idempotent
@RequiresCarApi(9) @ExperimentalCarApi
boolean canSetVoiceAssistantCapabilities(); // api>=9 && FEATURE_AUTOMOTIVE
void setVoiceAssistantCapabilities(NavigationVoiceAssistantCapabilities caps); // AAOS only

interface NavigationManagerCallback {
  default void onStopNavigation();   // stop routing + voice + notifications + updateTrip
  default void onAutoDriveEnabled(); // AutoDrive test: simulate until finishCarApp()
}
```

There is **no** `startNavigation/stopNavigation`, **no** `SurfaceRenderer`, **no** `CameraMode`,
**no** `TripPreview`, **no** `AlertManager` in 1.9.0-alpha02 — use the names above.

### 5.2 Trip graph (`navigation.model.*`)

```java
// Trip — getDestinations()/getSteps()/getDestinationTravelEstimates()/
//   getStepTravelEstimates()/getCurrentRoad()/isLoading()
new Trip.Builder()
  .addDestination(Destination, TravelEstimate).addStep(Step, TravelEstimate) // in order
  .setCurrentRoad(CharSequence) // spans ignored
  .setLoading(boolean)          // true + steps → IllegalArgumentException
  .build(); // size-match checks; floating bar = Step[0].cue + Estimate[0].distance + Maneuver.icon

// Step — getManeuver()/getLanes()/getLanesImage()/getCue()/getRoad()
new Step.Builder() | Builder(CharSequence|CarText cue)
  .setManeuver(Maneuver).addLane(Lane) // left→right; cluster/HUD primary
  .setLanesImage(CarIcon)              // 500×74dp bbox; needs lanes data
  .setCue(CharSequence)                // CarIconSpan|DistanceSpan|DurationSpan
  .setRoad(CharSequence)               // TEXT_ONLY
  .build();

// TravelEstimate — REMAINING_TIME_UNKNOWN = -1
getRemainingDistance()/getRemainingTimeSeconds()/getArrivalTimeAtDestination()/
// getRemainingTimeColor()/getRemainingDistanceColor() (STANDARD_ONLY)/
// getTripText()/getTripIcon() (API 5)
new TravelEstimate.Builder(Distance, DateTimeWithZone|ZonedDateTime)
  .setRemainingTimeSeconds(long).setRemainingTime(Duration) // API 26
  .setRemainingTimeColor(CarColor).setRemainingDistanceColor(CarColor)
  .setTripText(CarText).setTripIcon(CarIcon).build();

// Destination — getName()/getAddress()/getImage() (128×128dp)
new Destination.Builder().setName(...).setAddress(...).setImage(CarIcon).build();
//   (name or address non-empty required)

// RoutingInfo implements NavigationTemplate.NavigationInfo
getCurrentStep()/getCurrentDistance()/getNextStep()/getJunctionImage()/isLoading();
new RoutingInfo.Builder()
  .setCurrentStep(Step, Distance) // cue images 216×72dp
  .setNextStep(Step).setJunctionImage(CarIcon) // 500×312dp, aspect ≥ 1.6
  .setLoading(boolean).build();

// MessageInfo implements NavigationTemplate.NavigationInfo — off-route / guidance text
new MessageInfo.Builder(CharSequence|CarText title).setTitle(...).setText(...).setImage(...).build();
```

### 5.3 Voice assistant (API 9, AAOS only)

```java
// navigation.model.NavigationVoiceAssistantCapabilities (@ExperimentalCarApi @RequiresCarApi(9))
boolean isVoiceAssistantConsentGranted();
Set<Integer> getSupportedActions(); Set<Integer> getSupportedDisruptions();
new Builder()
  .setVoiceAssistantConsentGranted(boolean) // default false
  .addSupportedAction(@VoiceAssistantAction int)
  .addSupportedDisruption(@VoiceAssistantDisruption int).build();
// Actions: ACTION_UNDEFINED=0, ALLOW_AND_AVOID_FERRIES=1, ALLOW_AND_AVOID_HIGHWAYS=2,
//   ALLOW_AND_AVOID_TOLLS=3, CLEAR_SEARCH_RESULTS=4, EXIT_NAVIGATION=5, FOLLOW_MODE=6,
//   MUTE_AND_UNMUTE=7, ROUTE_OVERVIEW=8, SHOW_ALTERNATES=9, SHOW_DIRECTIONS_LIST=10,
//   SHOW_SATELLITE=11, SHOW_TRAFFIC=12
// Disruptions: DISRUPTION_REPORT_UNDEFINED=0, CONSTRUCTION=1, CRASH=2, FLOODING=3, FOG=4,
//   OBJECT_ON_ROAD=5, POLICE=6, POTHOLE=7, ROAD_CLOSURE=8, SNOW=9, TRAFFIC=10, VEHICLE=11
```

### 5.4 Map rendering

```java
// navigation.model.NavigationTemplate implements Template — active guidance
// requires NAVIGATION_TEMPLATES; any-content refresh (quota-exempt + quota-reset);
// NO own surface — draw via AppManager.setSurfaceCallback
interface NavigationInfo {} // impls: RoutingInfo, MessageInfo
getNavigationInfo()/getDestinationTravelEstimate()/getBackgroundColor()/
getActionStrip() // required, NAVIGATION max 4
getMapActionStrip()/getPanModeDelegate(); // API 2
new NavigationTemplate.Builder()
  .setNavigationInfo(NavigationInfo).setDestinationTravelEstimate(TravelEstimate)
  .setBackgroundColor(CarColor) // UNCONSTRAINED
  .setActionStrip(ActionStrip).setMapActionStrip(ActionStrip) // MAP max 4 icon-only
  .setPanModeListener(PanModeListener).build();

// navigation.model.MapController (@RequiresCarApi(5))
getMapActionStrip()/getPanModeDelegate();
new MapController.Builder().setMapActionStrip(ActionStrip).setPanModeListener(...).build();
// without Action.PAN: no SurfaceCallback pan gestures, host exits pan

// navigation.model.MapWithContentTemplate (@RequiresCarApi(7)) — REPLACES all below
// requires NAVIGATION_TEMPLATES *or* MAP_TEMPLATES
getContentTemplate()/getMapController()/getActionStrip();
new Builder().setContentTemplate(Template)
  // API 7: List|Pane|Grid|Message; API 8+: +SectionedItemTemplate
  .setMapController(MapController).setActionStrip(ActionStrip).build();

// @Deprecated (use MapWithContentTemplate):
//   navigation.model.MapTemplate (API 5) — getMapController/getPane/getItemList/getHeader/getActionStrip
//   navigation.model.PlaceListNavigationTemplate — rows: small images only, no image+marker,
//     non-browsable require DistanceSpan
//   navigation.model.RoutePreviewNavigationTemplate — needs OnSelectedListener,
//     every row DistanceSpan|DurationSpan; do NOT continuously refresh while moving
// Generic (non-NAV permission): model.PlaceListMapTemplate — see §6.

// navigation.model.Maneuver — getType()/getRoundaboutExitNumber()/getRoundaboutExitAngle()/getIcon()
new Maneuver.Builder(@Type int).setIcon(CarIcon 128×128dp)
  .setRoundaboutExitNumber(1..).setRoundaboutExitAngle(1..360).build();
// Types 0..50 incl. UNKNOWN/DEPART/NAME_CHANGE/KEEP_LEFT/RIGHT/TURN_*_LEFT/RIGHT/
//   U_TURN_*/ON_RAMP_*/OFF_RAMP_*/FORK_*/MERGE_*/ROUNDABOUT_*_CW/CCW/STRAIGHT/
//   FERRY_BOAT/TRAIN/DESTINATION* — full list in sources (Maneuver.java).

// navigation.model.Lane — getDirections(); Builder.addDirection(LaneDirection).build()
// navigation.model.LaneDirection — create(@Shape int, boolean recommended)
//   SHAPE_UNKNOWN=1, STRAIGHT=2, SLIGHT_LEFT=3, SLIGHT_RIGHT=4, NORMAL_LEFT=5,
//   NORMAL_RIGHT=6, SHARP_LEFT=7, SHARP_RIGHT=8, U_TURN_LEFT=9, U_TURN_RIGHT=10
// PanModeListener.onPanModeChanged(boolean);
// PanModeDelegate.sendPanModeChanged(boolean, OnDoneCallback) (API 2)

// Map anchors/markers:
// model.Place — new Place.Builder(CarLocation).setMarker(PlaceMarker).build()
// model.PlaceMarker — TYPE_ICON=0 (64×64dp, tintable) / TYPE_IMAGE=1 (72×72dp, no color)
//   setIcon(CarIcon,@MarkerIconType).setLabel(CharSequence ≤3).setColor(CarColor).build()
// model.CarLocation — create(lat,lng) / create(Location)
```

### 5.5 Alerts (nav only — no `AlertManager`; via `AppManager`)

```java
// AppManager.showAlert(Alert) / dismissAlert(int) (@RequiresCarApi(5))
// only navigation templates; unsupported template → REASON_NOT_SUPPORTED; same id replaces.
// model.Alert (@RequiresCarApi(5)) — DURATION_SHOW_INDEFINITELY = MAX_VALUE; MAX_ACTION_COUNT = 2
getId()/getTitle()/getSubtitle()/getIcon() (88×88dp)/getActions()/getDurationMillis()/getCallbackDelegate();
new Alert.Builder(int alertId, CarText title, long durationMillis)
  .setSubtitle(CarText).setIcon(CarIcon).addAction(Action max 2).setCallback(AlertCallback).build();
// model.AlertCallback — REASON_TIMEOUT=1/REASON_USER_ACTION=2/REASON_NOT_SUPPORTED=3
//   onCancel(@Reason int); onDismiss()
// Frequent driving alerts discouraged — prefer notification channel + CarAppExtender (see §12).
```

---

## 6. POI / Parking / Charging apps (`POI`)

Manifest: `androidx.car.app.category.POI`
(current name for deprecated `PARKING` / `CHARGING`).
Permissions: `MAP_TEMPLATES` (for `PlaceListMapTemplate`), `ACCESS_SURFACE` (custom map),
location (`ACCESS_FINE/COARSE_LOCATION`) for `setCurrentLocationEnabled`.

Primary templates: `SectionedItemTemplate` (API 8),
`TabTemplate` (API 6), `GridTemplate`, `ListTemplate`, `PaneTemplate`,
`PlaceListMapTemplate`, `MapWithContentTemplate` (map side via `MAP_TEMPLATES`),
`SearchTemplate`, `SignInTemplate`, `MessageTemplate`.

```java
// model.PlaceListMapTemplate — host-drawn map + ItemList
// refresh iff: prior loading, or title + row count/titles unchanged, or OnContentRefreshListener (API 5, quota-exempt)
isCurrentLocationEnabled()/getTitle()/getHeaderAction()/getActionStrip()/
isLoading()/getItemList()/getAnchor()/getOnContentRefreshDelegate(); // API 5
new PlaceListMapTemplate.Builder()
  .setCurrentLocationEnabled(boolean) // needs location perm; host may pull location
  .setLoading(boolean).setHeaderAction(APP_ICON|BACK).setTitle(CharSequence|CarText TEXT_ONLY)
  .setItemList(ItemList) // ROW_LIST_CONSTRAINTS_SIMPLE + non-browsable need DistanceSpan
                         // + small images only + no image+marker; limit CONTENT_LIMIT_TYPE_PLACE_LIST
  .setActionStrip(ActionStrip) // NAVIGATION max 4
  .setAnchor(Place)            // viewport reference (e.g. search centre)
  .setOnContentRefreshListener(OnContentRefreshListener) // API 5
  .build();

// model.SectionedItemTemplate (@RequiresCarApi(8)) — the POI workhorse
getSections()/getActions() (FAB)/getHeader()/getSearchHeader() (API 9)/
isLoading()/getAlphabeticalIndexingStrategy()/getScrollStatePersistenceStrategy();
new SectionedItemTemplate.Builder()
  .setSections(List)/addSection(Section)/clearSections()
    // only ChipSection (first, max 1) | RowSection | GridSection |
    //        CondensedSection | SpotlightSection | BannerSection
  .setActions/addAction (FAB: max 2 CUSTOM/COMPOSE_MESSAGE/MEDIA_PLAYBACK, icon+bg)
  .setHeader(Header) / .setSearchHeader(SearchHeader) // mutually exclusive
  .setLoading(boolean)
  .setAlphabeticalIndexingStrategy(DISABLED/TITLE_AS_IS/TITLE_IGNORE_ARTICLES_AND_SYMBOLS)
  .setScrollStatePersistenceStrategy(RESET_TO_TOP/PRESERVE_INDEX).build();

// Sections (model.*):
// GridSection (API 8): ITEM_SIZE_SMALL/MEDIUM/LARGE/EXTRA_LARGE,
//   ITEM_IMAGE_SHAPE_UNSET/CIRCLE, getIncompleteLastRowStrategy() (API 9)
// ChipSection (API 9 exp): ≥1 Chip; getStyle():ChipStyle
// CondensedSection (API 9 exp): CondensedItem dense rows; INCOMPLETE_LAST_ROW_AS_IS/TRUNCATE
// SpotlightSection (API 8/9): hero cards (hero image + condensed items)
// BannerSection (API 9 exp): exactly 1 Banner; Banner/BannerElement/BannerStyle
//   BannerStyle: getShape()/getBackground(); Builder.setShape(Shape).setBackground(Background)
// RowSection, Section<T>, SectionHeader, SectionedItemList
```

---

## 7. Media apps (`MEDIA`, experimental-stable hybrid)

Manifest: `androidx.car.app.category.MEDIA` (API 8).
Permissions: `androidx.car.app.MEDIA_TEMPLATES`.
Feature: `CarFeatures.FEATURE_CAR_APP_LIBRARY_MEDIA`.
**Browse is delegated** — `MediaBrowserServiceCompat` / Media3 `MediaLibraryService`.
The car-app lib only transports hints, extras, token, and the playback template.
`PlaybackStateCompat` is never referenced directly — the host reads the session out-of-band.

```java
// media.model.MediaPlaybackTemplate (@RequiresCarApi(8) @CarProtocol)
// PREREQUISITE: MediaPlaybackManager.registerMediaPlaybackToken()
// API 9+: host renders persistent entrypoint; app MUST handle
//   MediaConstants.ACTION_SHOW_MEDIA_PLAYBACK in Session.onNewIntent
getHeader()/getBanner() (API 9 exp)/getStyle() (API 9 exp);
new MediaPlaybackTemplate.Builder()
  .setHeader(Header).setBanner(Banner).setStyle(MediaPlaybackStyle).build();
// constraint: header/banner/style only; media apps max 1 FAB on API 9+ (host drops extras)

// media.model.MediaPlaybackStyle (@RequiresCarApi(9) @ExperimentalCarApi)
getMediaAccentColor()/getProgressBarStrokeCap();
new MediaPlaybackStyle.Builder()
  .setMediaAccentColor(CarColor|null) // UNCONSTRAINED
  .setProgressBarStrokeCap(@StrokeCap int) // default StrokeCap.DEFAULT
  .build(); // progress mirrors active MediaSession/MediaSessionCompat

// media.MediaPlaybackManager (@RequiresCarApi(8)) — CarContext.MEDIA_PLAYBACK_SERVICE
MediaPlaybackManager m = carContext.getCarService(MediaPlaybackManager.class);
m.registerMediaPlaybackToken(MediaSessionCompat.Token token); // @MainThread

// media.MediaConstants (Kotlin object)
ACTION_SHOW_MEDIA_PLAYBACK = "androidx.car.app.media.action.SHOW_MEDIA_PLAYBACK";

// Voice/mic shared with messaging + nav search:
// media.CarAudioRecord (@RequiresCarApi(5) abstract) — CarContext.create →
//   AutomotiveCarAudioRecord (automotive) | ProjectedCarAudioRecord (projected)
AUDIO_CONTENT_SAMPLING_RATE=16000; AUDIO_CONTENT_BUFFER_SIZE=512; AUDIO_CONTENT_MIME="audio/l16";
startRecording(); stopRecording(); read(byte[],off,size):int; // -1 = dismissed
// + CarAudioCallback.onStopRecording(), OpenMicrophoneRequest(CarAudioCallback),
//   OpenMicrophoneResponse(InputStream + PFD), delegates

// mediaextensions.* — hints/extras only, no UI:
// MediaBrowserExtras: KEY_ROOT_HINT_MEDIA_HOST_VERSION,
//   KEY_ROOT_HINT_MEDIA_SESSION_API (1/2/3), KEY_ROOT_HINT_MAX_QUEUE_ITEMS_WHILE_RESTRICTED,
//   KEY_HINT_VIEW_MAX_ITEMS_WHILE_RESTRICTED, ..._PER_ROW / ..._CATEGORY_... variants
// MediaIntentExtras: ACTION_MEDIA_TEMPLATE_V2, EXTRA_KEY_MEDIA_COMPONENT/_MEDIA_ID/
//   EXTRA_KEY_SEARCH_QUERY/_SEARCH_ACTION (0=NONE, 1=PLAY_FIRST_ITEM_FROM_SEARCH)
// MetadataExtras (MediaMetadataCompat / media3 MediaMetadata extras):
//   KEY_SUBTITLE_LINK_MEDIA_ID, KEY_DESCRIPTION_LINK_MEDIA_ID,
//   KEY_IMMERSIVE_AUDIO, KEY_EXCLUDE_MEDIA_ITEM_FROM_MIXED_APP_LIST,
//   KEY_CONTENT_FORMAT_TINTABLE_LARGE_ICON_URI/SMALL_ICON_URI,
//   KEY_TINTABLE_INDICATOR_ICON_URI_LIST
// mediaextensions.analytics.*: BrowseChangeEvent/ErrorEvent/MediaClickedEvent/
//   ViewChangeEvent/VisibleItemsEvent + client.AnalyticsCallback/Parser/RootHintsPopulator
```

---

## 8. Messaging apps (`MESSAGING`)

Manifest: `androidx.car.app.category.MESSAGING` (`@ExperimentalCarApi`).
Messaging support also declared via an `IntentService` intent-filter:

```xml
<service android:name=".MessagingService">
  <intent-filter>
    <action android:name="androidx.car.app.messaging.action.HANDLE_CAR_MESSAGING" />
  </intent-filter>
</service>
```

There is **no** `MessagingManager`, **no** `MessagingTemplate`, **no** `RemoteInput` wrapper.
App implements `ConversationCallback`; host invokes via `ConversationCallbackDelegate`.
Lists use `ConversationItem` inside `ListTemplate` / `SectionedItemTemplate`.
Notifications use `CarNotificationManager` + `CarAppExtender` (see §12).

```java
// messaging.MessagingServiceConstants (@RequiresCarApi(8))
ACTION_HANDLE_CAR_MESSAGING = "androidx.car.app.messaging.action.HANDLE_CAR_MESSAGING";

// messaging.model.ConversationItem (@RequiresCarApi(7) implements Item)
// allowed by RowListConstraints alongside Row/Banner
getId()/getTitle()/getSelf() (name+key required)/getIcon()/isGroupConversation() (3+ vs 1:1)/
// getMessages() (oldest→newest, non-empty)/getConversationCallbackDelegate()/
// getActions()/isIndexable() (API 8);
new ConversationItem.Builder(String id, CarText title, Person self,
                             List<CarMessage> messages, ConversationCallback cb)
  .setId/setTitle/setIcon/setSelf/setGroupConversation/setMessages/setConversationCallback
  .addAction(Action) // max 1, ACTIONS_CONSTRAINTS_CONVERSATION_ITEM (TYPE_CUSTOM icon-required)
  .setIndexable(boolean).build();

// messaging.model.CarMessage (@RequiresCarApi(7))
getSender() (null|self = self-sent; else name+key)/getBody()/
// getMultimediaMimeType()+getMultimediaUri() (both or neither)/
// getReceivedTimeEpochMillis()/isRead();
new CarMessage.Builder()
  .setSender(Person|null).setBody(CarText|null)
  .setMultimediaMimeType(String).setMultimediaUri(Uri)
  .setReceivedTimeEpochMillis(long).setRead(boolean).build();

// messaging.model.ConversationCallback — host→client
void onMarkAsRead(); void onTextReply(String replyText);
// messaging.model.ConversationCallbackDelegate (@RequiresCarApi(7))
//   sendMarkAsRead(OnDoneCallback); sendTextReply(String, OnDoneCallback)
// (+ ConversationCallbackDelegateImpl, PersonsEqualityHelper.kt)
```

---

## 9. Calling / Dialer apps (`CALLING`)

Manifest: `androidx.car.app.category.CALLING` (`@ExperimentalCarApi`).
There is **no** `DialerManager` / `CallingManager` — only templates + keypad callback.

```java
// dialer.InCallTemplate implements Template (@ExperimentalCarApi)
getHeader()/getIcon()/getTitle()/getTexts()/getActions()/isLoading();
new InCallTemplate.Builder()
  .setTitle(CarText|CharSequence TEXT_ONLY).setIcon(CarIcon DEFAULT)
  .setHeader(Header) // start → ACTION_CONSTRAINTS_IN_CALL_HEADER (max 1 APP_ICON/BACK/CUSTOM icon, no click)
  .addText(CarText|CharSequence) // max MAX_TEXTS_SIZE=2, TEXT_AND_ICON
  .addAction(Action)             // max 5, IN_CALL_CONTENT (TYPE_CUSTOM icon 0-titles, max 1 FLAG_PRIMARY)
  .setLoading(boolean).build();  // content xor loading

// dialer.TelephoneKeypadTemplate implements Template (@ExperimentalCarApi)
// Keys KEY_ZERO..KEY_NINE=0..9, KEY_STAR=10, KEY_POUND=11
getHeader()/getPhoneNumber() (editable, reset on refresh)/getPrimaryAction() (call/hangup)/
// getTelephoneKeypadCallbackDelegate()/getPhoneNumberChangedDelegate()/getKeySecondaryTexts();
new TelephoneKeypadTemplate.Builder(Action primaryAction, PhoneNumberChangeListener l)
  // primary: TELEPHONE_KEYPAD_PRIMARY (max 1 TYPE_CUSTOM icon 0-titles)
  .setHeader(Header) // TELEPHONE_KEYPAD_HEADER (max 1 APP_ICON/BACK)
  .setPhoneNumber(String|null).setTelephoneKeypadCallback(TelephoneKeypadCallback|null)
  .addKeySecondaryText(@KeypadKey int, CarText TEXT_AND_ICON).setKeySecondaryTexts(Map).build();

// dialer.TelephoneKeypadCallback (@ExperimentalCarApi)
void onKeyDown(@KeypadKey int key); void onKeyUp(@KeypadKey int key); // once per gesture, no repeat
void onKeyLongPress(@KeypadKey int key); // down → longPress → up
// dialer.TelephoneKeypadCallbackDelegate/Impl
// PhoneNumberChangeListener.onPhoneNumberChanged(String) — backspace updates without keypad event
```

---

## 10. Weather apps (`WEATHER`)

Manifest: `androidx.car.app.category.WEATHER` (API 7).
No dedicated weather manager/template — compose from shared templates:

- `ListTemplate` / `SectionedItemTemplate` (forecast rows: `Row` + `CarIcon` + `DistanceSpan`/`DurationSpan`)
- `GridTemplate` (`GridItem` per day/hour, `setItemSize/setItemImageShape` API 8)
- `TabTemplate` (Current | Hourly | Daily; `TabStyle` API 9)
- `PaneTemplate` (alert detail), `MessageTemplate` (no data), `PlaceListMapTemplate`
  (radar city list; `MAP_TEMPLATES`)
- `Header` + `CarText` variants; `CarProgressBar` (precipitation chance, API 9)

---

## 11. IoT apps (`IOT`)

Manifest: `androidx.car.app.category.IOT` (API 6).
No `IoTManager` — manifest-only category. Compose from:

- `GridTemplate` (device grid), `ListTemplate` (`Row` + `Toggle` for switches),
  `SectionedItemTemplate` (`RowSection` per room + `ChipSection` filters, API 9),
  `PaneTemplate` (device detail), `MessageTemplate` (offline),
  `SignInTemplate` (account link), `SearchTemplate` (device search)
- `Row.Builder.setToggle(Toggle)`; `Action` with `ParkedOnlyOnClickListener` where required

---

## 12. Settings / sign-in / search / suggestions / notifications

### 12.1 Settings

Manifest: `androidx.car.app.category.SETTINGS` (API 6).
Templates: `ListTemplate`, `PaneTemplate`, `MessageTemplate`, `SignInTemplate`,
`LongMessageTemplate` (parked-only ToS).

### 12.2 `model.signin.SignInTemplate` (parked-only, API 2)

```java
isLoading()/getTitle()/getHeaderAction()/getInstructions()/getAdditionalText()/
getActionStrip()/getActions()/getSignInMethod():SignInMethod (marker);
new SignInTemplate.Builder(SignInMethod m)
  .setLoading(...).setHeaderAction(APP_ICON|BACK).setActionStrip(SIMPLE max 2/1-titled)
  .addAction(Action) // max 2 BODY_WITH_PRIMARY_ACTION, MUST ParkedOnlyOnClickListener
  .setTitle(CharSequence TEXT_ONLY).setInstructions(CharSequence TEXT_WITH_COLORS)
  .setAdditionalText(CharSequence CLICKABLE_TEXT_ONLY).build();

// ProviderSignInMethod(Action action TYPE_CUSTOM + ParkedOnlyOnClickListener)
// PinSignInMethod(CharSequence pin 1..MAX_PIN_LENGTH=12)
// QRCodeSignInMethod(Uri uri) (API 4)
// InputSignInMethod: INPUT_TYPE_DEFAULT=1/PASSWORD=2; KEYBOARD_DEFAULT=1/EMAIL=2/PHONE=3/NUMBER=4
//   getHint()/getDefaultValue()/getErrorMessage()/getInputType()/getKeyboardType()/
//   getInputCallbackDelegate()/isShowKeyboardByDefault();
//   new InputSignInMethod.Builder(InputCallback cb).setHint/setDefaultValue/
//     setInputType/setErrorMessage(≤2 lines)/setKeyboardType/setShowKeyboardByDefault.build()
// InputCallback: onInputSubmitted/onInputTextChanged (both default no-op, API 2)
```

### 12.3 Search (POI / media / messaging shared)

`model.SearchTemplate`: refresh-friendly (typing changes not counted).
`Builder(SearchCallback)` + `setHeaderAction(APP_ICON|BACK)` +
`setActionStrip(SIMPLE max 2/1-titled)` + `setInitialSearchText` +
`setSearchHint` (hidden while initial text) + `setLoading` +
`setItemList(ItemList ROW_LIST_CONSTRAINTS_SIMPLE: CONTENT_LIMIT_TYPE_LIST, not selectable,
ROW_CONSTRAINTS_SIMPLE max 2 texts, image+toggle allowed, no Toggle)` +
`setShowKeyboardByDefault` (default true).

`model.SearchHeader` (API 9 exp): same but for `SectionedItemTemplate`
(mutually exclusive with `Header`).

### 12.4 `suggestion.SuggestionManager` (API 5, `SUGGESTION_SERVICE`)

```java
SuggestionManager s = carContext.getCarService(SuggestionManager.class);
s.updateSuggestions(List<Suggestion> list); // @MainThread; HostException if not nav-manifest
// suggestion.model.Suggestion (@CarProtocol)
getIdentifier()/getTitle()/getSubtitle()/getIcon() (128×128dp)/getAction():PendingIntent;
new Suggestion.Builder().setIdentifier/setTitle/setSubtitle/setAction(required)/setIcon/build();
```

### 12.5 `notification.*` (all app types)

```java
// notification.CarNotificationManager — wrapper over NotificationManagerCompat
static CarNotificationManager from(Context ctx);
notify(int, NotificationCompat.Builder) / notify(String,int,Builder)
//   auto-extends with CarAppExtender if missing; automotive unwraps extender
cancel(int)/cancel(String,int)/cancelAll();
areNotificationsEnabled(); getImportance();
createNotificationChannel(ChannelCompat)/createNotificationChannelGroup/
createNotificationChannels/createNotificationChannelGroups/
deleteNotificationChannel/deleteNotificationChannelGroup/deleteUnlistedNotificationChannels/
getNotificationChannel(String[,conversationId])/getNotificationChannelGroup/
getNotificationChannels()/getNotificationChannelGroups();
static getEnabledListenerPackages(Context);

// notification.CarAppExtender implements NotificationCompat.Extender — MUST extend or not shown on car
// HUN if IMPORTANCE_HIGH/PRIORITY_HIGH+; badge if DEFAULT+; TBT nav if ongoing+CATEGORY_NAVIGATION
// (suppressed if not active nav or already in nav template; setOnlyAlertOnce(true) recommended)
extend(NotificationCompat.Builder|Notification.Builder);
static boolean isExtended(Notification n);
getContentTitle/Text(); getSmallIcon():@DrawableRes; getLargeIcon():Bitmap|null;
getContentIntent()/getDeleteIntent():PendingIntent|null; getActions(); getImportance();
getColor():CarColor|null (nav HUN only); getChannelId():String|null (AAOS only);
new CarAppExtender.Builder()
  .setContentTitle/setContentText/setSmallIcon(int)/setLargeIcon(Bitmap)/
  setContentIntent/setDeleteIntent/addAction(int,title,PendingIntent)|addAction(Action) (max 2)/
  setImportance(int AA only)/setColor(CarColor nav only)/setChannelId(String AAOS only).build();

// notification.CarPendingIntent + CarAppNotificationBroadcastReceiver
static PendingIntent getCarApp(Context ctx, int reqCode, Intent intent, int flags);
// validates: ACTION_NAVIGATE geo: | ACTION_DIAL/ACTION_CALL tel: | own CarAppService;
// strips IMMUTABLE→mutable (+ALLOW_UNSAFE_IMPLICIT_INTENT on U+);
// automotive → getActivity(CarAppActivity); projected → getBroadcast(CarAppNotificationBroadcastReceiver)
```

### 12.6 Cluster (`FEATURE_CLUSTER`, API 6)

Manifest: `androidx.car.app.category.FEATURE_CLUSTER` with
`SessionInfo.DISPLAY_TYPE_CLUSTER`. Only `NavigationTemplate` is allowed;
feed it via the same `NavigationManager.updateTrip(Trip)` cluster path.

### 12.7 Theming (API 9, all app types)

```java
// opt-in: override CarAppService.getCarAppThemeSource()
//   THEME_SOURCE_SYSTEM (default, OEM styling) vs THEME_SOURCE_APP (brand styling, CAL defaults)
// manifest: <meta-data android:name="androidx.car.app.theme" .../> (also permission-UI drawables)
// styles: TabStyle / BannerStyle / MediaPlaybackStyle / CarIconStyle.setShape(Shape) /
//         Background / Shape / CarProgressBar — see §3.3; host may enforce contrast + fallback
```

---

## 13. Vehicle hardware (all app types, API-gated)

### 13.1 `hardware.CarHardwareManager` (API 3, `HARDWARE_SERVICE`)

```java
CarHardwareManager hw = carContext.getCarService(CarHardwareManager.class);
CarInfo getCarInfo(); CarSensors getCarSensors();
@ExperimentalCarApi CarClimate getCarClimate();
// static create(CarContext, HostDispatcher) resolves impl via
// CarAppMetadataHolderService.CAR_HARDWARE_MANAGER metadata;
// throws if host < LEVEL_3 or missing app-automotive|app-projected dep.
```

### 13.2 `hardware.common.*`

```java
// CarValue<T> (API 3) — STATUS_UNKNOWN=0/SUCCESS=1/UNIMPLEMENTED=2/UNAVAILABLE=3
T getValue(); long getTimestampMillis(); // elapsedRealtime base
int getStatus(); List<CarZone> getCarZones();
// sentinels: UNKNOWN_INTEGER/BOOLEAN/FLOAT/STRING/LIST/ARRAY, UNIMPLEMENTED_INTEGER/FLOAT_LIST
// CarUnit — CarDistanceUnit{MILLIMETER=1,METER=2,KILOMETER=3,MILE=4},
//   CarSpeedUnit{METERS_PER_SEC=101,KILOMETERS_PER_HOUR=102,MILES_PER_HOUR=103},
//   CarVolumeUnit{MILLILITER=201,LITER=202,US_GALLON=203,IMPERIAL_GALLON=204} (exp)
// CarZone (API 5) — rows ROW_ALL/FIRST/SECOND/THIRD/EXCLUDE_FIRST,
//   cols COLUMN_ALL/LEFT/CENTER/RIGHT/DRIVER/PASSENGER, CAR_ZONE_GLOBAL
//   getRow/getColumn; Builder.setRow/setColumn.build()
// OnCarDataAvailableListener<T>.onCarDataAvailable(T)
// CarSetOperationStatusCallback (API 5) — OPERATION_STATUS_SUCCESS/
//   FEATURE_UNIMPLEMENTED/UNSUPPORTED/TEMPORARILY_UNAVAILABLE/SETTING_NOT_ALLOWED/
//   UNSUPPORTED_VALUE/INSUFFICIENT_PERMISSION/ILLEGAL_CAR_HARDWARE_STATE/UPDATE_TIMEOUT
//   + onSetCarClimateState{...}(int) per climate feature
```

### 13.3 `hardware.info.*`

```java
// CarInfo (API 3)
fetchModel/fetchEnergyProfile/fetchExteriorDimensions(Executor, OnCarDataAvailableListener) // dims API 7
add/removeTollListener; add/removeEnergyLevelListener; add/removeSpeedListener;
add/removeMileageListener; add/removeEvStatusListener (exp);
// CarSensors (API 3) — UPDATE_RATE_NORMAL=1/UI=2/FASTEST=3
add/removeAccelerometerListener; add/removeGyroscopeListener; add/removeCompassListener;
add/removeCarHardwareLocationListener(int rate, Executor, OnCarDataAvailableListener);
// Model — getName/getYear/getManufacturer():CarValue
// EnergyProfile — EvConnectorType{UNKNOWN/J1772/MENNEKES/CHADEMO/COMBO_1/COMBO_2/
//   TESLA_ROADSTER/HPWC/SUPERCHARGER/GBT/GBT_DC/SCAME/OTHER=101},
//   FuelType{UNKNOWN/UNLEADED/LEADED/DIESEL_1/DIESEL_2/BIODIESEL/E85/LPG/CNG/LNG/ELECTRIC/HYDROGEN/OTHER}
// EnergyLevel — getBatteryPercent/getFuelPercent/getEnergyIsLow/getRangeRemainingMeters,
//   getDistanceDisplayUnit(), getFuelVolumeDisplayUnit() (exp)
// Speed — getRawSpeedMetersPerSecond/getDisplaySpeedMetersPerSecond/getSpeedDisplayUnit()
// Mileage — getOdometerInKilometers() (+@Deprecated getOdometerMeters alias)
// EvStatus (exp) — getEvChargePortOpen/getEvChargePortConnected
// TollCard.getCardState(); ExteriorDimensions (API 7, needs android.car.permission.CAR_INFO)
// Accelerometer/Gyroscope/Compass (float-list vectors); CarHardwareLocation (UNIMPLEMENTED_LOCATION)
```

### 13.4 `hardware.climate.*` (API 5, experimental)

```java
// CarClimate
registerClimateStateCallback(Executor, RegisterClimateStateRequest, CarClimateStateCallback);
unregisterClimateStateCallback(CarClimateStateCallback);
fetchClimateProfile(Executor, ClimateProfileRequest, CarClimateProfileCallback);
setClimateState<E>(Executor, ClimateStateRequest<E>, CarSetOperationStatusCallback);
// ClimateProfileRequest / RegisterClimateStateRequest features:
//   FEATURE_HVAC_POWER=1/AC=2/MAX_AC=3/CABIN_TEMPERATURE=4/FAN_SPEED=5/FAN_DIRECTION=6/
//   SEAT_TEMPERATURE_LEVEL=7/SEAT_VENTILATION_LEVEL=8/STEERING_WHEEL_HEAT=9/
//   RECIRCULATION=10/AUTO_RECIRCULATION=11/AUTO_MODE=12/DUAL_MODE=13/
//   DEFROSTER=14/MAX_DEFROSTER=15/ELECTRIC_DEFROSTER=16/CAR_ZONE_MAPPING=17
// CarClimateFeature/Builder(feature).addCarZones(CarZone...)
// ClimateStateRequest<T>: getRequestedFeature/getCarZones/getRequestedValue
// CarClimateStateCallback + CarClimateProfileCallback — onHvacPower/Ac/MaxAcMode/
//   CabinTemperature/FanSpeedLevel/FanDirection/SeatTemperatureLevel/SeatVentilationLevel/
//   SteeringWheelHeat/HvacRecirculation/HvacAutoRecirculation/HvacAutoMode/HvacDualMode/
//   Defroster/MaxDefroster/ElectricDefroster[StateAvailable](CarValue<...>) (all default {})
// Profiles: HvacPower/Ac/MaxAc/Recirculation/AutoRecirculation/AutoMode/DualMode/
//   Defroster/MaxDefroster/ElectricDefroster/CabinTemperature (ranges+increments+zone maps)/
//   FanSpeedLevel/FanDirection/SeatTemperature/SeatVentilation/SteeringWheelHeat/CarZoneMappingInfo
```

---

## 14. Automotive-only vs Projected-only

| Capability | `app-automotive` | `app-projected` |
|---|---|---|
| `CarInfo/Sensors` | `AutomotiveCarInfo/Sensors` via `android.car` + `PropertyManager` | `ProjectedCarInfo/Sensors` via `CarHardwareHostDispatcher` → host |
| `CarClimate` | `AutomotiveCarClimate` supported | unsupported (throws) |
| UI host | `CarAppActivity/BaseCarAppActivity/LauncherActivity + TemplateSurfaceView` natively | phone renders via projected host (AA/CP); no activity in lib |
| Connection | `AutomotiveCarConnectionTypeLiveData = NATIVE` | `CarConnectionTypeLiveData` provider + broadcast (`PROJECTION`) |
| Result | `ResultManagerAutomotive` (real `setActivityResult`) | base `ResultManager` (AA: no-op / null caller) |
| Audio | `AutomotiveCarAudioRecord` | `ProjectedCarAudioRecord` |
| Wiring | `CAR_HARDWARE_MANAGER=AutomotiveCarHardwareManager` | `CAR_HARDWARE_MANAGER=ProjectedCarHardwareManager` |

### 14.1 Automotive activities (`app-automotive/activity.*`)

```java
// activity.CarAppActivity extends BaseCarAppActivity — automotive-only entry
//   ACTION_RENDER="android.car.template.host.RendererService"
//   onCreate → bindToViewModel(new SessionInfo(DISPLAY_TYPE_MAIN, intent.identifier))
// activity.BaseCarAppActivity extends FragmentActivity
//   bindToViewModel(SessionInfo); getServiceComponentName()/retrieveServiceComponentName()
//   (queries SERVICE_INTERFACE in own package; exactly 1 required)
//   ICarAppActivity.Stub{setSurfacePackage/registerRendererCallback/setInsetsListener/
//     setSurfaceListener/onStartInput/onStopInput/startCarApp/finishCarApp/onUpdateSelection/showAssist}
//   insets fan-out; takeSurfaceSnapshot() via PixelCopy;
//   state IDLE/CONNECTING/CONNECTED/ERROR (TemplateSurfaceView/ErrorMessageView/LoadingView)
// activity.LauncherActivity (@ExperimentalCarApi) — FEATURE_AUTOMOTIVE ? CarAppActivity : MAIN/DEFAULT
// activity.ActivityLifecycleDelegate — registerRendererCallback(IRendererCallback) →
//   ServiceDispatcher.dispatch(ON_CREATE/START/RESUME/PAUSE/STOP/DESTROY)
// activity.ServiceDispatcher — dispatch(String,OneWayCall)/fetch(...)
//   DeadObject→HOST_CONNECTION_LOST, Remote→HOST_ERROR, Bundler→CLIENT_SIDE_ERROR
// activity.HostUpdateReceiver — PACKAGE_REPLACED → CarAppViewModel.onHostUpdated()
// activity.ResultManagerAutomotive — setCarAppResult→ViewModel; getCallingComponent (AA: null)
// + CarAppViewModel/Factory/ServiceConnectionManager/ErrorHandler/LogTags,
//   ui.ErrorMessageView/LoadingView,
//   renderer.surface.TemplateSurfaceView/SurfaceWrapper/Provider/HolderListener/
//     ControlCallback/LegacySurfacePackage/OnBackPressed/OnCreateInputConnection/RemoteProxyInputConnection
//   renderer.ICarAppActivity/IRendererCallback/IRendererService/ISurfaceListener/IInsetsListener
// hardware.AutomotiveCarHardwareManager + climate/info/common (PropertyManager,
//   CarPropertyProfile<T> (HvacFanDirection…), CarPropertyResponse, GetPropertyRequest,
//   PropertyIdAreaId, PropertyRequestProcessor, PropertyResponseCache, PropertyUtils,
//   CarValueUtils, OnCarPropertyResponseListener, CarInternalError, CarZone*Converter/Utils)
// media.AutomotiveCarAudioRecord
// Automotive manifest:
//   <activity android:name="androidx.car.app.activity.CarAppActivity" android:exported="true"
//     android:launchMode="singleTask" android:label="...">
//     <intent-filter><action android:name="android.intent.action.MAIN" />
//     <category android:name="android.intent.category.LAUNCHER" /></intent-filter>
//     <meta-data android:name="distractionOptimized" android:value="true" />
//   </activity>
```

### 14.2 Projected (`app-projected/*`)

```java
// hardware.ProjectedCarHardwareManager implements CarHardwareManager (@RestrictTo(LIBRARY) @CarProtocol)
//   getCarInfo():ProjectedCarInfo; getCarSensors():ProjectedCarSensors;
//   NO getCarClimate() (UnsupportedOperationException); ctor (CarContext, HostDispatcher)
// hardware.common.CarHardwareHostDispatcher (+CarResultStub/Map) — host-IPC fan-out
// hardware.info.ProjectedCarInfo / ProjectedCarSensors — host-dispatched
// media.ProjectedCarAudioRecord
// No activities, no renderer/, no PropertyManager/CarPropertyProfile, no AutomotiveCar*
```

---

## 15. Testing surface

`app-testing` (`androidx.car.app.testing.*`, `testing.navigation.*`).
**No `CarAppTestRule`** in this drop — compose the fakes directly:

```java
TestCarContext.create(); // fake CarContext + service overrides
TestScreenManager / ScreenController  // drive Screen.onGetTemplate/invalidate + lifecycle
SessionController                     // drive Session.onCreateScreen/onNewIntent/lifecycle
TestAppManager / navigation.TestNavigationManager // record invalidate/navigationStarted/…
FakeHost // stub ICarHost
TestOnDoneCallbackStub, TestLifecycleOwner, TestDelegateInvoker.kt
```

---

## 16. Permissions, constraints, quotas

### Permissions (`androidx.car.app.CarAppPermission`)

| Permission | Needed for |
|---|---|
| `androidx.car.app.ACCESS_SURFACE` | `AppManager.setSurfaceCallback` / custom map |
| `androidx.car.app.NAVIGATION_TEMPLATES` | `NavigationTemplate`, legacy nav templates, nav side of `MapWithContentTemplate` |
| `androidx.car.app.MAP_TEMPLATES` | `PlaceListMapTemplate`, map side of `MapWithContentTemplate` (self-drawn nav maps exempt) |
| `androidx.car.app.MEDIA_TEMPLATES` | media templates (triggers API 9 1-FAB limit) |

```java
static boolean checkHasPermission(Context ctx, String perm);
static boolean checkHasLibraryPermission(Context ctx, @LibraryPermission String perm);
```

Platform-adjacent: `android.car.permission.CAR_INFO` (exterior dims etc.);
vehicle-property read perms in `PropertyManager.checkPermissions()`
(`CAR_INFO / CAR_EXTERIOR_ENVIRONMENT / CAR_ENERGY / CAR_SPEED / READ_*`);
host-side `android.car.permission.TEMPLATE_RENDERER` (auto API 31+);
`RECORD_AUDIO` for `CarAudioRecord`; location for current-location.

### Constraints (`model.constraints.*`, all `@RestrictTo(LIBRARY)`)

- `ActionsConstraints`: `HEADER`(1 icon, no click) / `MULTI_HEADER`(2) / `BODY`(2/2-titled
  `COLOR_ONLY`) / `BODY_WITH_PRIMARY_ACTION`(1 primary) / `SIMPLE`(strip 2/1-titled
  `TEXT_ONLY`) / `NAVIGATION`(map strips 4/4-titled/1-primary `TEXT_AND_ICON`) /
  `MAP`(4/1-primary icon-only) / `ROW`(2 `CUSTOM/MEDIA_PLAYBACK`, 1 primary) /
  `CONVERSATION_ITEM`(1 `CUSTOM` icon) / `FAB`(2 `CUSTOM/COMPOSE_MESSAGE/MEDIA_PLAYBACK`
  icon+bg; media 1 on API 9+) / `TABS`(needs `APP_ICON`) / `TAB_ACTIONS`(1 custom icon) /
  `IN_CALL_HEADER`(1 `APP_ICON/BACK/CUSTOM`) / `IN_CALL_CONTENT`(5 custom 0-titled 1-primary) /
  `TELEPHONE_KEYPAD_HEADER`(1 `APP_ICON/BACK`) / `TELEPHONE_KEYPAD_PRIMARY`(1 custom icon) /
  `BANNER_TRAILING`(2) / `BANNER_BELOW`(3/2-titled) — via `validateOrThrow(List<Action>)`.
- `RowListConstraints`: `CONSERVATIVE` / `PANE` / `SIMPLE` / `MAP_ROW_LIST_CONSTRAINTS_ALLOW_SELECTABLE`
  (+`ROUTE_PREVIEW` alias) / `FULL_LIST`; allows `Row|ConversationItem|Banner`.
- `RowConstraints`: `UNCONSTRAINED / CONSERVATIVE / PANE / SIMPLE / FULL_LIST`.
- `CarTextConstraints`: `CONSERVATIVE`(none) / `UNCONSTRAINED` /
  `CLICKABLE_TEXT_ONLY` / `COLOR_ONLY`(FgColor) / `TEXT_ONLY`(Distance/Duration/Timer) /
  `TEXT_AND_ICON` / `TEXT_WITH_COLORS` / `TEXT_WITH_COLORS_AND_ICON`.
- `CarIconConstraints`: `UNCONSTRAINED`(bitmap/resource/uri-content) /
  `DEFAULT`(bitmap/resource only; URI must be `content:`).
- `ModelUtils`: `validateAllNonBrowsableRowsHaveDistance` (PlaceListMap),
  `validateAllRowsHaveDistanceOrDuration` (route preview),
  `validateAllRowsHaveOnlySmallImages` (no LARGE), `validateNoRowsHaveBothMarkersAndImages`.
- `navigation.model.constraints.ContentTemplateConstraints`:
  `MAP_WITH_CONTENT_TEMPLATE_CONSTRAINTS` (API 7 Grid/Message/List/Pane),
  `..._API_8` (+SectionedItem).
- `constraints.ConstraintManager` (API 2): `CONTENT_LIMIT_TYPE_LIST=0/GRID=1/
  PLACE_LIST=2/ROUTE_LIST=3/PANE=4`; `getContentLimit(type)` (host or
  `R.integer.content_limit_*` fallback); `isAppDrivenRefreshEnabled()` (API 6).
- Template flow (`Screen`): 5-task quota; refresh vs push rules in §2.2;
  `ListTemplate` truncates to 100; `MAX_MESSAGES_PER_CONVERSATION=10`.

---

## Appendix A: 1.9.0-alpha02 + alpha01 changelog

**`1.9.0-alpha02` (Sept 2026)** — theming + search + styling release:

- App theming: `CarAppThemeSource` + `CarAppService.onGetAppTheme` (+ IPC) — apps opt into
  brand theming, hosts query preferred config.
- Search header: `SearchHeader` for `SectionedItemTemplate` (API 9 search replacement).
- Component styling: `TabStyle` (bg/content/corner radii), `BannerStyle` (shape/corners),
  `MediaPlaybackStyle` + `setMediaAccentColor` + standalone `StrokeCap`, `CarProgressBarStyle`
  (nullable colours; deprecated colour methods removed from `CarProgressBar`).
- Tabs: custom bg/content/text colours + corner radii.
- Icons/images: `IMAGE_TYPE_ICON` deprecated across `Row/GridItem/Banner/CondensedItem`
  → `IMAGE_TYPE_SMALL/MEDIUM` (untinted default); `CarIconStyle` rework + custom `Shape` on `CarIcon`.
- Banners: `BannerStyle` shapes/corners, `IMAGE_TYPE_SMALL/LARGE` variants, deprecated
  icon/bg APIs removed → `setLeadingImage/addTrailingImage`.
- Headers/layout: `SearchHeader` (API 9), `startHeaderImage` on `Header`,
  `incompleteLastRowStrategy` for grid/condensed/spotlight partial rows.
- Bug fix: `Action.TYPE_MEDIA_PLAYBACK` ignored as template FAB on Car API 9+ hosts.

**`1.9.0-alpha01` (May 2026)** — content-flexibility release:

- `Banner` + `BannerSection` for `SectionedItemTemplate`.
- `NavigationManager.setVoiceAssistantCapabilities` + `NavigationVoiceAssistantCapabilities`
  (voice actions/disruptions/consent → host).
- `CarAppApiLevel` 9.
- `Chip`/`ChipSection` shape via `ChipStyle`.
- `CondensedItem`/`CondensedSection` high-density layouts.
- Experimental `Header` subtitles + `Background` model.
- Spotlight section (hero image + condensed items), minimized control panel (NPV),
  section headers (titles/overlines/brand icons/trailing actions), expanded header layout
  (collapsing background image + secondary actions).

---

## Appendix B: Full class index

All FQCNs are `androidx.car.app.*` unless noted; `(P)` = `app-projected`,
`(A)` = `app-automotive`, `(T)` = `app-testing`.

**Core:** `CarAppService`, `CarAppBinder`, `Session`, `Screen`, `ScreenManager`,
`CarContext`, `AppManager`, `SurfaceCallback`, `SurfaceContainer`, `CarToast`,
`CarAppPermission`, `HostInfo`, `HandshakeInfo`, `AppInfo`, `SessionInfo`,
`SessionInfoIntentEncoder`, `CarAppMetadataHolderService`, `MainThread`/`Manager`/
`ManagerFactory`/`ManagerCache` (`managers.*`), `ResultManager` (`managers.*`),
`validation.HostValidator`, `versioning.CarAppApiLevel(s)`,
`annotations.{RequiresCarApi,ExperimentalCarApi,CarProtocol,KeepFields}`,
`features.CarFeatures`, `connection.{CarConnection,CarConnectionTypeLiveData,
AutomotiveCarConnectionTypeLiveData}`, `constraints.ConstraintManager`,
`suggestion.SuggestionManager`, `suggestion.model.Suggestion`,
`serialization.*`, `utils.*`.

**Model (`model.*`):** `Template`, `TemplateInfo`, `TemplateWrapper`, `Action`,
`ActionStrip`, `Header`, `SearchHeader` (9), `Tab`, `TabContents`, `TabStyle` (9),
`Pane`, `PaneTemplate`, `ListTemplate`, `SectionedItemTemplate` (8),
`GridTemplate`, `MessageTemplate`, `LongMessageTemplate` (2), `SearchTemplate`,
`SearchCallback`, `PlaceListMapTemplate`, `TabTemplate` (6),
`Item`, `ItemList`, `SectionedItemList`, `Section`, `SectionHeader`,
`RowSection`, `GridSection` (8), `ChipSection` (9), `CondensedSection` (9),
`SpotlightSection` (8/9), `BannerSection` (9), `Row`, `GridItem`,
`CondensedItem`, `Chip`, `ChipStyle`, `Banner`, `BannerElement`, `BannerStyle` (9),
`Toggle`, `Metadata`, `Place`, `PlaceMarker`, `CarLocation`, `Alert` (5),
`AlertCallback` (5), `AlertCallbackDelegate` (5), `CarText`, `CarSpan`,
`DistanceSpan`, `DurationSpan`, `ForegroundCarColorSpan`, `CarIconSpan`,
`ClickableSpan`, `TimerSpan`, `Distance`, `DateTimeWithZone`, `CarColor`,
`CarIcon`, `CarIconStyle`, `Background` (9), `Shape` (9), `CarProgressBar` (9),
`CarProgressBarStyle`, `StrokeCap`, `OnClickListener`,
`ParkedOnlyOnClickListener`, `InputCallback`, `OnContentRefreshListener`,
`constraints.{ActionsConstraints,RowConstraints,RowListConstraints,
CarTextConstraints,CarIconConstraints,TabsConstraints,TabContentsConstraints}`,
`ModelUtils`, `model.signin.{SignInTemplate,SignInMethod,ProviderSignInMethod,
PinSignInMethod,QRCodeSignInMethod,InputSignInMethod}`.

**Navigation (`navigation.*`):** `NavigationManager`, `NavigationManagerCallback`,
`navigation.model.{NavigationTemplate,MapWithContentTemplate (7),MapTemplate (5,dep),
PlaceListNavigationTemplate (dep),RoutePreviewNavigationTemplate (dep),Trip,Step,
TravelEstimate,Destination,RoutingInfo,MessageInfo,Maneuver,Lane,LaneDirection,
MapController (5),PanModeListener,PanModeDelegate,PanModeDelegateImpl,
NavigationVoiceAssistantCapabilities (9),
constraints.ContentTemplateConstraints}`.

**Media (`media.*`, `media.model.*`, `mediaextensions.*`):**
`MediaPlaybackManager` (8), `MediaPlaybackTemplate` (8, `media.model`),
`MediaPlaybackStyle` (9), `MediaConstants`, `CarAudioRecord` (5),
`CarAudioCallback`, `OpenMicrophoneRequest/Response`, `CarAudioCallbackDelegate`,
`AutomotiveCarAudioRecord` (A), `ProjectedCarAudioRecord` (P),
`MediaBrowserExtras`, `MediaIntentExtras`, `MetadataExtras`,
`mediaextensions.analytics.{Constants,ThreadUtils,client.*,event.*}`.

**Messaging / notifications:** `messaging.MessagingServiceConstants` (8),
`messaging.model.{ConversationItem (7),CarMessage (7),ConversationCallback,
ConversationCallbackDelegate}`, `notification.{CarNotificationManager,
CarAppExtender,CarPendingIntent,CarAppNotificationBroadcastReceiver}`.

**Dialer (`dialer.*`):** `InCallTemplate`, `TelephoneKeypadTemplate`,
`TelephoneKeypadCallback`, `TelephoneKeypadCallbackDelegate` (all experimental).

**Hardware (`hardware.*`):** `CarHardwareManager`,
`common.{CarValue,CarUnit,CarZone (5),OnCarDataAvailableListener,
CarSetOperationStatusCallback (5)}`,
`info.{CarInfo,CarSensors,Model,EnergyProfile,EnergyLevel,Speed,Mileage,
EvStatus,TollCard,ExteriorDimensions (7),Accelerometer,Gyroscope,Compass,
CarHardwareLocation}`, `climate.{CarClimate,ClimateProfileRequest,
CarClimateFeature,RegisterClimateStateRequest,ClimateStateRequest,
CarClimateStateCallback,CarClimateProfileCallback,Hvac*Profile,
CabinTemperatureProfile,FanSpeedLevelProfile,FanDirectionProfile,
SeatTemperatureProfile,SeatVentilationProfile,SteeringWheelHeatProfile,
CarZoneMappingInfoProfile}` (all 5, experimental),
`AutomotiveCarHardwareManager` (A), `automotive info/climate/common`
(`AutomotiveCarInfo/Sensors/Climate`, `CarPropertyProfile`, `PropertyManager`,
`CarPropertyResponse`, `GetPropertyRequest`, `PropertyIdAreaId`,
`PropertyRequestProcessor`, `PropertyResponseCache`, `PropertyUtils`,
`CarValueUtils`, `OnCarPropertyResponseListener`, `CarInternalError`,
`CarZone*Converter/Utils`) (A),
`ProjectedCarHardwareManager`, `common.CarHardwareHostDispatcher`,
`info.ProjectedCarInfo/Sensors` (P).

**Automotive activity (A):** `activity.{CarAppActivity,BaseCarAppActivity,
LauncherActivity,ActivityLifecycleDelegate,ServiceDispatcher,HostUpdateReceiver,
ResultManagerAutomotive,CarAppViewModel,CarAppViewModelFactory,
ServiceConnectionManager,ErrorHandler,LogTags,ui.ErrorMessageView/LoadingView,
renderer.surface.* (TemplateSurfaceView/SurfaceWrapper/Provider/HolderListener/
ControlCallback/LegacySurfacePackage/OnBackPressed/OnCreateInputConnection/
RemoteProxyInputConnection), renderer.ICarAppActivity/IRendererCallback/
IRendererService/ISurfaceListener/IInsetsListener}`.

**Testing (T):** `testing.{TestCarContext,TestAppManager,TestScreenManager,
ScreenController,SessionController,FakeHost,TestOnDoneCallbackStub,
TestLifecycleOwner,TestDelegateInvoker.kt,navigation.TestNavigationManager}`.
