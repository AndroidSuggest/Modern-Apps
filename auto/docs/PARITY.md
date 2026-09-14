# MA Auto parity (look + function) with gearhead 17.5.663214

> Tracked companion to `auto/docs/FINDINGS.md` (teardown) and `auto/docs/HANDOFF.md`
> (bring-up status). Research-only: every item cites both sides. No estimates —
> where a value was not read it says `NOT READ`, where a file was absent it says
> `MISSING`, where a layout token was not found it says `NOT FOUND`.
> Verdicts: `SUPPORTED` (MA equivalent with file:line), `PARTIAL` (ID/slot without
> full semantics), `MISSING` (no MA counterpart). §§2–10 cover the car-projected
> look; §§11–15 cover full-stack function (control plane, channels, transports,
> services, components, permissions, protobuf coverage).

**Ground truth:** gearhead 17.5.663214 (`versionCode 175663214`, `minSdk 32`,
`targetSdk 37`), Pixel 7 Pro (`cheetah`) pull: `analysis/maauto/apk/base.apk` +
splits, dex in `analysis/maauto/dex/`, jadx in `analysis/maauto/jadx-out/sources`,
layouts/values in `analysis/maauto/manifest-out/resources/res/`, manifest in
`analysis/maauto/manifest-out/resources/AndroidManifest.xml`.

**MA under test:** `auto/src/main/java/com/vayunmathur/auto/platform/CarDisplay.kt`
(`CarPresentation` L390–L1075), plus `VideoSinkChannel.kt`, `VideoEncoder.kt`,
`InputChannel.kt`, card-content providers, `auto/src/main/AndroidManifest.xml`,
`auto/src/main/res/values/strings.xml`.

**Views-not-Compose note (do not "fix"):** `CarDisplay.kt` builds the car surface
with framework `View`s because a `Presentation` on a private virtual display has
no lifecycle owner. A later implementer must not Compose-ify it.

---

## 1. Scope (user-decided)

**In scope — car projected look (pixel-for-pixel on the head unit), §§2–10:**

- Coolwalk rail / facet bar, status bar fragment, hotseat dock, ongoing widget,
  assistant slot, resize control, voice plate slot.
- Dash media card + button row, nav card (map surface + banner), app drawer +
  launcher grid, empty/loading/lockout states, assistant scrim.
- Touch targets, focus rings, dpad/rotary behavior, night mode, densities that
  change HU pixels.
- Behavior that gates pixels: drawer open/close, tap routing, scroll, keys,
  invalidation cadence, theme switch, rotation/config, display lifecycle.
- System requirements gating car pixels: `SYSTEM_AUTOMOTIVE_PROJECTION` role pin,
  privapp allowlist, RROs (requirements, not implementation).
- GAL channels only where they paint HU pixels: video config, focus highlight,
  ch8 tap mapping, nav banner text, media metadata/art.

**In scope — full-stack function (§§11–15, added for functional parity):**

- GAL control plane: every control message ID (1–26/255/65535), frame layout,
  fragmentation, TLS posture, crypto/KDF posture, version negotiation, discovery
  content, channel-open order, ping/byebye/focus handling.
- Every service-channel family: media sink `jdk`, video `jem`, mic `jdi`,
  input `jar`, sensor `rvb` (all 26 `xny` types), plus BT/radio/nav/phone/
  browser/vendor/wifi/car-control/local-media/buffered/INST/diag/latency/sync.
- Transports: USB AOAP, wireless (BT/RFCOMM/CDM/`ebe` flags), TCP loopback,
  selector priority, framing/queue/ack/backoff infra.
- App services/components/permissions/role grants vs gearhead manifest truth.
- Protobuf coverage: MA `gal/*.proto` vs gearhead `x*` families + decompiler
  traps.

**Out of scope (one line each, no deep dive):**

- Phone screens `ui/AutoScreen.kt`, `ui/PairingScreen.kt`, `ui/SessionCard.kt`,
  `ui/AudioRow.kt`, gate cards — except §7 gating notes.
- Any implementation, asset creation, RRO/permission/role edits.

---

## 2. Gearhead projected-surface inventory

### 2.1 Layout files (all under `analysis/maauto/manifest-out/resources/res/layout/`)

12 HU-relevant layouts read fully. Base `layout/` has **751 files** (most are
`abc_*`, phone setup, carsetup, settings — explicitly out of scope and not
enumerated here; see §9 for the coverage obligation).

| Layout file | Root / owner | HU surface |
|---|---|---|
| `gh_coolwalk_facet_bar.xml` | `FrameLayout` → `ConstraintLayout @id/rail` → `RailStatusBarFragment`, `RailHotseatItemView`, `RailWidgetView`, `ResizeButton` | Coolwalk rail |
| `sys_ui_rail_hotseat.xml` | `LinearLayout @id/rail_hotseat_container` → 4× `RailHotseatItemView` | Rail dock |
| `frag_dash_media.xml` | `DashboardCardView @id/dash_media_card` → `MediaPlaybackView`, `TappableRegion`, `PlayPauseStopCoolwalkButton` | Dash media card |
| `frag_dash_media_button_row.xml` | `LinearLayout @id/media_button_row` → `CoolwalkButton`, `PlayPauseStopCoolwalkButton` | Media controls |
| `drawer_layout.xml` | `CarDrawerLayout @id/drawer_container` → stubs `drawer_contents`, `alpha_jump_layout` | Drawer container |
| `drawer_contents.xml` | `FrameLayout` → `UnlimitedBrowsePagedListView` (class MISSING; `sdk/ui/PagedListView.java` is truth) | Drawer body (SDK template path) |
| `adu_drawer.xml` | `merge` → `PagedListView @id/menu_list`, `AlphaJumpFab`, `AlphaJumpKeyboard` | Drawer body (AppDecor path) |
| `adu_status_bar_view.xml` | `merge` → header icon/title, search box, drawer/mic/search buttons | AppDecor header |
| `app_bar.xml` | `TouchStealingFrameLayout @id/app_bar_container` → title, tabs, aux buttons | App bar |
| `alpha_jump_layout.xml` | `FrameLayout` → `AlphaJumpFab`, `AlphaJumpKeyboard` | Alpha jump |
| `app_launcher_item.xml` | `ConstraintLayout` → `CardView @id/icon_circular_crop_container`, badges, `@id/label` | Launcher cell |
| `assistant_scrim.xml` | `FrameLayout @id/assistant_scrim` | Assistant overlay |

Probed but **NOT FOUND** (no such file in `res/layout/`): `car_drawer.xml`,
`status_bar.xml`, `rail_status_bar.xml`, `sys_ui_status_bar.xml`,
`car_status_bar.xml`, `appbar.xml`, `car_app_bar.xml`, `app_decor.xml`,
`facet_bar.xml`, `coolwalk_facet_bar.xml`, `app_drawer.xml`, `media_card.xml`,
`media_playback.xml`, `now_playing.xml`, `now_playing_card.xml`,
`frag_dash_navigation.xml`, `frag_dash_nav.xml`, `frag_dash_map.xml`,
`nav_card.xml`, `navigation_card.xml`, `launcher_item.xml`, `launcher_row.xml`,
`launcher_grid.xml`, `car_launcher.xml`, `bottom_sheet.xml`. Rail status-bar
*content* layouts (`rail_statusbar`, `vertical_rail_statusbar`,
`hero_vertical_rail_statusbar`, `rail_statusbar_rhd` — referenced by
`RailStatusBarFragment.java:218-231`) were **NOT READ** (existence not probed
file-by-file; see §9.1 open item).

### 2.2 Layout details (exact attributes)

#### `gh_coolwalk_facet_bar.xml`

- Root `FrameLayout` `match_parent`/`match_parent`.
- `ConstraintLayout @id/rail` `match_parent`/`match_parent`,
  `background="?android:attr/windowBackground"`, `fitsSystemWindows="true"`.
- `FocusInterceptor @id/launcher_and_dashboard_icon_container`
  `wrap_content`/`wrap_content`,
  `layout_marginStart="@dimen/coolwalk_launcher_dashboard_margin"` (= **10dp**,
  `values/dimens.xml:560`) → `CoolwalkButton @id/launcher_and_dashboard_icon`
  **68dp**×**68dp** (`@dimen/facet_bar_touch_target_size`, `dimens.xml:846`),
  `checkable="false"`, `app:icon="@drawable/ic_dashboard_icon_lhd"`,
  `style="@style/Widget.Gearhead.Coolwalk.Rail.Button"`.
- `FrameLayout @id/assistant_icon_container` 68dp×68dp, `clipChildren="false"`,
  `clipToPadding="false"` →
  `FragmentContainerView @id/live_fragment`
  56dp×56dp (`@dimen/coolwalk_rail_hotseat_button_icon_size`, `dimens.xml:581`),
  `visibility="gone"`, `focusable="false"`, `clickable="false"`,
  `descendantFocusability="blocksDescendants"`;
  `CircleOverlayView @id/circle_overlay` same 56dp, `gone`;
  `FocusInterceptor` (no id) `wrap_content`/`wrap_content` →
  `CoolwalkButton @id/assistant_icon` 68dp×68dp, `gone`, `checkable="false"`,
  `app:icon="@drawable/gs_mic_fill1_vd_theme_24"`, same Rail.Button style.
- `FrameLayout @id/ongoing_widget_container` `0dp`/`wrap_content`,
  `layout_marginStart/End="@dimen/rail_coolwalk_rail_margin"` (= **6dp**,
  `dimens.xml:2998`),
  `app:layout_constraintWidth_max="@dimen/rail_widget_max_width"` (= **750dp**,
  `dimens.xml:3032`) → `RailWidgetView @id/ongoing_widget`
  `match_parent`/`wrap_content`, `gone`.
- `ImageView @id/etc_icon` `wrap_content`/`wrap_content`, `gone`,
  `layout_marginLeft="@dimen/etc_icon_margin"` (= `@dimen/gearhead_baseline_grid_1x`
  = **8dp**, `dimens.xml:831,893`), `src="@drawable/ic_gearhead_etc_rail_icon"`,
  `tint="?attr/colorOnSurface"`, `alpha="@dimen/coolwalk_etc_icon_opacity"`
  (= **0.6**, `dimens.xml:545`), `importantForAccessibility="no"`.
- `FrameLayout @id/dock_container` `0dp`/`wrap_content`, `gravity="center"`,
  margins 6dp, maxWidth 750dp → `include layout="@layout/sys_ui_rail_hotseat"`
  `visibility="invisible"` (no id);
  `RailWidgetView @id/rail_ongoing_widget` `wrap_content`/`match_parent`,
  `gone`, `maxWidth 750dp`, `gravity/layout_gravity="center"`.
- `Guideline @id/hotseat_start` / `@id/hotseat_end`,
  `guide_begin`/`guide_end="@dimen/coolwalk_rail_hotseat_guideline"`
  (= **145dp**, `dimens.xml:584`).
- `View @id/rail_invisible_scrim` `0dp`/`match_parent`,
  `background="@android:color/transparent"`.
- `FragmentContainerView @id/status_bar` `wrap_content`/`match_parent`,
  `android:name="com.google.android.apps.auto.components.system.statusbar.RailStatusBarFragment"`.
- `Barrier @id/edge_barrier`, `barrierDirection="start"`,
  `barrierAllowsGoneWidgets="false"`,
  `constraint_referenced_ids="resize_button_ghost, edge_spacer"`.
- `Space @id/resize_button_ghost` `gone`,
  36dp×56dp (`resize_button_short_side_size` `dimens.xml:3046`,
  `resize_button_long_side_size` `dimens.xml:3045`),
  `layout_marginEnd="@dimen/coolwalk_gutter_padding"` (= **10dp**,
  `dimens.xml:553`).
- `Space @id/edge_spacer` `@dimen/rail_edge_margin` (= **2dp**,
  `dimens.xml:3010`) / `match_parent`.
- `ConstraintLayout @id/resize_button_tap_target` `gone`, `clickable="true"`,
  `@dimen/resize_tap_target_size` (= **64dp**, `dimens.xml:3047`) /
  `match_parent` → `ResizeButton @id/resize_button` `gone`,
  `wrap_content`/`wrap_content`, marginEnd 10dp, `app:orientation="vertical"`.
- `ConstraintLayout @id/native_rail` `match_parent`/`match_parent`, `gone`,
  `windowBackground`, `fitsSystemWindows="true"` →
  `CoolwalkButton @id/native_app_back_icon` 68dp, marginStart 10dp,
  `app:icon="@drawable/gs_arrow_back_vd_theme_48"`;
  `CoolwalkButton @id/native_app_exit_icon` 68dp, `app:icon="0x7f08093a"`;
  both `checkable="false"`, Rail.Button style.
- `FragmentContainerView @id/voice_plate` `match_parent`/`match_parent`,
  `gone`, `layout_gravity="bottom"`, `windowBackground`.
- No `textSize` in this file.

#### `sys_ui_rail_hotseat.xml`

- Root `LinearLayout @id/rail_hotseat_container` horizontal
  `wrap_content`/`match_parent`, `gravity/layout_gravity="center"`.
- `RailHotseatItemView @id/hotseat_one|two|three|four`, each
  68dp×68dp (`@dimen/coolwalk_rail_dock_icon_touch_target_size`,
  `dimens.xml:580`), `checkable="false"`, `app:iconTint="@null"`.
- Sibling tokens (not used by this layout but adjacent):
  `coolwalk_rail_hotseat_icon_touch_target_size` = 74dp (`dimens.xml:585`),
  `coolwalk_rail_dock_button_icon_size` = 52dp (`dimens.xml:572`).

#### `frag_dash_media.xml` + `frag_dash_media_button_row.xml`

- Root `DashboardCardView @id/dash_media_card` `match_parent`/`match_parent`,
  `theme="?attr/coolwalk_darkThemeOverlay"`, `swipeToDismissEnabled="false"`.
- `Guideline @id/pager_start_guideline` vertical,
  `guide_end="@dimen/dashboard_pager_indicator_width"` (= **52dp**,
  `dimens.xml:671`).
- `ImageView @id/album_art` `0dp`/`0dp`, `scaleType="centerCrop"`, constrained
  parent all sides.
- `ImageView @id/album_art_scrim` `0dp`/`0dp`, `gone`,
  `src="@drawable/dashboard_media_album_art_scrim"`, `scaleType="fitXY"`.
- `ShapeableImageView @id/source_badge` 24dp×24dp
  (`@dimen/dashboard_media_badge_size` → `gearhead_baseline_grid_3x` = 24dp,
  `dimens.xml:636,900`), `style="@style/Widget.Dashboard.Badge"`
  (`styles.xml:18142-18144`, parent empty:
  `shapeAppearance="@style/CircleShapeAppearance"` = rounded 50%,
  `styles.xml:6596-6599`).
- `FocusInterceptor` (no id) `0dp`/`0dp`,
  `layout_marginStart="@dimen/dashboard_media_text_padding"` (= **5dp**,
  `dimens.xml:666`), `app:focusRingInset="0dp"`,
  `nextFocusForward="@+id/text_container_wrapper_next_focus_forward"` →
  `TappableRegion @id/text_container_wrapper` `match_parent`/`match_parent`,
  `focusRingInset 0dp`,
  `shapeAppearanceOverlay="@style/DashPrimaryTappableRegionAlbumArtMatchingShapeAppearance"`,
  `style="@style/Widget.Dashboard.PrimaryTarget"`
  (`styles.xml:18167-18169`, parent `Widget.Gearhead.Coolwalk.TappableRegion`
  `styles.xml:18488-18493`: `focusable true`,
  `rippleColor="@color/coolwalk_tappable_region_ripple_color_selector"`,
  `shapeAppearance="?attr/shapeAppearanceMediumComponent"`).
- `LinearLayout @id/text_container` vertical `0dp`/`wrap_content`,
  `gravity="start"`,
  `layout_marginTop="@dimen/dashboard_media_text_container_margin_top"`
  (= `@dimen/gearhead_padding_0` = **4dp**, `dimens.xml:665,1029`),
  `layout_marginBottom="@dimen/dashboard_media_text_container_margin_bottom"`
  (= **7dp**, `dimens.xml:664`), `vertical_bias` fraction →
  `TextView @id/title` `wrap_content`/`wrap_content`,
  `layout_marginTop="5dp"`, `maxLines="@integer/dashboard_media_title_max_lines"`
  (= **1**, `values/integers.xml:37`),
  `includeFontPadding="@bool/dashboard_media_include_font_padding"`
  (= **true**, `values/bools.xml:27`),
  `style="@style/Widget.Gearhead.Coolwalk.TextView.Title"`
  (`styles.xml:18514-18519` → `TextAppearance.Gearhead.Coolwalk.Body2.Title`
  `styles.xml:9584-9587`: `textColor ?textColorPrimary`,
  `fontFamily ?attr/titleFontFamily`, parent Body2
  `textSize @dimen/coolwalk_body2_text_size` = **28dp**; `ellipsize 3`,
  `maxLines 1`, `includeFontPadding false`);
  `TextView @id/subtitle` `wrap_content`/`wrap_content`,
  `layout_marginBottom="5dp"`,
  `style="@style/Widget.Dashboard.TextView.Subtitle"`
  (`styles.xml:18170-18172` → Body3.Dashboard `styles.xml:9595-9597`:
  Body3 `textSize @dimen/coolwalk_body3_text_size` = **24dp**,
  `fontFamily ?attr/bodyLegacySansSerifFontFamily`).
- `LinearProgressIndicator @id/progress` `0dp` /
  `@dimen/dashboard_media_progress_height` (= **4dp**, `dimens.xml:651`),
  `indicatorColor="?attr/dashProgressColor"`,
  `trackColor="?attr/dashProgressSurface"`,
  `trackCornerRadius="@fraction/dashboard_media_progress_radius_fraction"`
  (value NOT READ), `trackThickness="4dp"`.
- `include @layout/frag_dash_media_button_row` `0dp`/`wrap_content`,
  `layout_marginBottom="@dimen/dashboard_media_button_margin_bottom"`
  (= **4dp**, `dimens.xml:637`).
- `FocusInterceptor` (no id) `0dp`/`0dp` →
  `TappableRegion @id/primary_target` `match_parent`/`match_parent`,
  `focusable="false"`, same overlay + PrimaryTarget style.
- Button row root `LinearLayout @id/media_button_row`
  `match_parent`/`match_parent`, horizontal, `center_vertical`:
  `Space` weight 1; `CoolwalkButton @id/action_left` 88dp×88dp
  (`@dimen/dashboard_media_action_size` → `gearhead_baseline_grid_11x` = 88dp,
  `dimens.xml:634,884`), `nextFocusForward="@+id/play_pause"`,
  `style="@style/Widget.Dashboard.Media.Button.Action"`
  (`styles.xml:18156-18162`: insets + `focusRingInset`
  `@dimen/dashboard_media_action_inset` = `gearhead_baseline_grid_1.5x` =
  **12dp**, `dimens.xml:633,881`);
  `Space` weight 2; `PlayPauseStopCoolwalkButton @id/play_pause` 88dp,
  `nextFocusForward="@+id/action_right"`,
  `app:icon="@drawable/ic_play_pause_stop_solid"`,
  `style="@style/Widget.Dashboard.Media.Button.Action.Primary"`
  (`styles.xml:18163-18166`: `backgroundTint
  @color/dashboard_primary_action_background_color_selector`,
  `materialThemeOverlay @style/ThemeOverlay.Dashboard.Media.Button.Action.Primary`);
  `Space` weight 2; `CoolwalkButton @id/action_right` 88dp; `Space` weight 1.

#### `drawer_layout.xml` / `drawer_contents.xml` / `adu_drawer.xml`

- `drawer_layout.xml`: root `CarDrawerLayout @id/drawer_container`
  `match_parent`/`match_parent` →
  `FrameLayout @id/container` `match_parent`/`match_parent`;
  `FrameLayout @id/drawer` `match_parent`/`match_parent`,
  `layout_gravity="left"`, `background="@color/gearhead_sdk_card"`
  (day `@color/gearhead_sdk_card_light` → `@color/gearhead_sdk_grey_50` =
  `#fffafafa`, `colors.xml:801,806,846`;
  night `@color/gearhead_sdk_card_dark` → `#ff172026`,
  `values-night/colors.xml:89`, `colors.xml:821`),
  `layout_marginEnd="96dp"` →
  `ViewStub @id/drawer_stub` `match_parent`/`match_parent`,
  `layout="@layout/drawer_contents"`, `inflatedId="@+id/drawer_contents"`;
  `ViewStub @id/alpha_jump_layout_stub` → `@layout/alpha_jump_layout`.
- `drawer_contents.xml`: root `FrameLayout` `match_parent`/`match_parent`;
  `FrameLayout @id/drawer_shadow` `match_parent` /
  `@dimen/gearhead_sdk_drawer_header_height` (= **96dp**, `dimens.xml:1061`),
  `background="@color/gearhead_sdk_card_background"`
  (day `@color/gearhead_sdk_button_background_day` → `#fffafafa`,
  `colors.xml:802,794`; night `@color/gearhead_sdk_button_background_night` →
  `#191f27`, `values-night/colors.xml:90`, `colors.xml:795`),
  `focusable="false"`;
  `CardView @id/unlimited_browsing_exit_header` `match_parent`/`wrap_content`,
  `gone`, margins top/start 8dp, end 8dp
  (`content_browse_notification_margin`/`_margin_end`, `dimens.xml:513-514`),
  `cardBackgroundColor="@color/gearhead_sdk_blue_grey_800"` (= `#ff37474f`,
  `colors.xml:789`),
  `cardCornerRadius="@dimen/gearhead_sdk_card_view_corner_radius"` (= **2dp**,
  `dimens.xml:1057`),
  `cardElevation="@dimen/gearhead_sdk_card_view_elevation"` (= **8dp**,
  `dimens.xml:1058`) →
  `LinearLayout` `match_parent` /
  `@dimen/content_browse_notification_height` (= **88dp**, `dimens.xml:512`),
  horizontal →
  `TextView @id/unlimited_browsing_exit_text` `0dp`/`match_parent`, weight 1,
  `text="@string/content_browse_park_to_continue"` (exact string NOT READ),
  `paddingStart="@dimen/content_browse_notification_text_padding"` (= **22dp**,
  `dimens.xml:515`), `gravity="center_vertical"`,
  `style="@style/GearheadSdkContentBrowseNotification"`
  (`styles.xml:7058-7063`: `textSize @dimen/gearhead_sdk_body_2_size` = **26dp**,
  `dimens.xml:1051`; `textColor @color/gearhead_sdk_grey_100` = `#fff5f5f5`,
  `colors.xml:841`; `fontFamily sans-serif-condensed`);
  `TextView @id/unlimited_browsing_exit_button` `wrap_content`/`wrap_content`,
  `text="@string/content_browse_unlimited_browsing_exit"` = `Exit`
  (`values/strings.xml:1121`), `textColor="@color/unlimited_browsing_exit_text"`
  (value NOT READ), `background="?attr/gearheadLockoutExitButtonBackground"`,
  paddings vert **8dp** (`dimens.xml:3560`), horiz **16dp** (`dimens.xml:3557`),
  margins start **24dp** (`dimens.xml:3559`), end **16dp** (`dimens.xml:3558`),
  `textAllCaps="true"`, `center_vertical`, same style;
  `FrameLayout` `match_parent`/`match_parent`,
  `layout_marginTop="96dp"` →
  `UnlimitedBrowsePagedListView @id/drawer_list_view` (class **MISSING**;
  `sdk/ui/PagedListView.java` is the readable truth)
  `match_parent`/`match_parent`, `paddingEnd="32dp"`;
  `ProgressBar @id/progress` 48dp×48dp, center, indeterminate;
  `CardView @id/truncated_list_card` `match_parent`/`88dp`, `gone`, bottom,
  margins L/R/B 8dp, same card colors/radius/elevation →
  `TextView @id/truncated_list_text` `match_parent`/`match_parent`,
  `center_vertical`, paddings 22dp, same style. No explicit `textSize`.
- `adu_drawer.xml`: root `merge` →
  `CardView @id/truncated_list_card` same 88dp/`gone`/margins/card →
  `TextView @id/truncated_list_text` same;
  `LinearLayout` vertical `match_parent`/`match_parent`, `elevation="7dp"` →
  `View @id/top_focus_dummy` 0dp/0dp elev 7dp;
  `View @id/lockout_scrim` `match_parent`/`match_parent`, `gone`,
  `background="@color/speedbump_drawer_scrim"`
  (day `@color/speedbump_drawer_scrim_day` → `#e6fafafa`, `colors.xml:3687-3688`;
  night → `#e6172026`, `values-night/colors.xml:364`, `colors.xml:3689`),
  `paddingEnd="32dp"`, elev 7dp;
  `View @id/bottom_focus_dummy` 0dp/0dp elev 7dp;
  `PagedListView @id/menu_list` `match_parent`/`match_parent`,
  `layout_marginTop="96dp"`, `paddingEnd="32dp"`, `app:listMaxWidth="0dp"`;
  `TextView @id/empty_view` `match_parent`/`match_parent`, `gone`,
  `text="@string/appdecor_menu_empty"` (exact string NOT READ), `gravity="center"`,
  `style="@style/GearheadSdkBody1"` (`styles.xml:6975-6981`:
  `textSize @dimen/gearhead_sdk_body_1_size` = **32dp**, `dimens.xml:1050`;
  `textColor @color/gearhead_sdk_body1` (value NOT READ);
  `fontFamily sans-serif-condensed`, `duplicateParentState true`);
  `ProgressBar @id/progress` 48dp, `gone`, center, indeterminate;
  `FrameLayout @id/drawer_shadow` `match_parent`/`96dp`, same card_background,
  `focusable="false"`, `clickable="true"`;
  `AlphaJumpFab @id/alpha_jump_fab` 96dp×96dp
  (`@dimen/alpha_jump_fab_width_height`, `dimens.xml:134`), `gone`,
  `end|top`, `marginTop @dimen/alpha_jump_fab_margin_top` = **48dp**
  (`dimens.xml:132`), `marginEnd @dimen/alpha_jump_fab_margin_end` = **22dp**
  (`dimens.xml:131`), `elevation="@dimen/fab_elevation"` = **8dp**
  (`dimens.xml:834`);
  `AlphaJumpKeyboard @id/alpha_jump_keyboard` `match_parent`/`match_parent`,
  `gone`, `background="@color/gearhead_sdk_card"`,
  `layout_marginTop="@dimen/alpha_jump_keyboard_margin_top"` = **96dp**
  (`dimens.xml:138`), `app:mode="fixed"`,
  `app:use_appdecor_drawer_keyboard="true"`;
  `RelativeLayout @id/fundip_container` `match_parent`/`match_parent`, `gone`,
  bottom, `elevation="1500dp"` →
  `ImageView @id/fundip_drawable` `wrap_content`/`96dp`, `marginBottom 8dp`,
  `src="@drawable/speedbump_animation_big_day"`, bottom/center;
  `TextView @id/lockout_text` `wrap_content`/`wrap_content`,
  `text="@string/speedbump_lockout_message"` = `Safety pause. Back soon.`
  (`values/strings.xml:2581`), **`textSize="26dp"`** (only explicit textSize in
  drawer set), `gravity="center"`, aligned to fundip drawable,
  `style GearheadSdkBody1`.

#### `adu_status_bar_view.xml` / `app_bar.xml` / `alpha_jump_layout.xml`

- `adu_status_bar_view.xml` root `merge` →
  `LinearLayout @id/car_mic_underlay` horizontal `match_parent`/`96dp`,
  `paddingStart="96dp"` (`gearhead_sdk_drawer_header_menu_button_size`,
  `dimens.xml:1062`), `elevation="@dimen/appdecor_status_bar_elevation"`
  (= **8dp**, `dimens.xml:187`), `animateLayoutChanges="true"`,
  `clipToPadding="false"` →
  `LinearLayout @id/car_drawer_title_container` horizontal `0dp`/`match_parent`,
  weight 1 →
  `ImageView @id/header_app_icon_view` `@dimen/gearhead_sdk_body_1_size`
  (= **32dp**) / `match_parent`, `gone`, `fitCenter`,
  `layout_marginEnd="@dimen/gearhead_sdk_standard_margin"` (= **16dp**,
  `dimens.xml:1102`), elev 8dp, `right`;
  `TextView @id/car_drawer_title` `wrap_content`/`match_parent`,
  `singleLine`, `ellipsize end`, `center_vertical`, `focusable false`,
  `style="@style/GearheadSdkTitle"` (`styles.xml:7145-7151`:
  `textSize @dimen/gearhead_sdk_title_size` = **26dp**, `dimens.xml:1107`;
  day `@color/gearhead_sdk_title_dark` → `grey_900 #ff212121`,
  `colors.xml:902-903,851`; night `#fff5f5f5`, `colors.xml:904,841`;
  `sans-serif-condensed`, `includeFontPadding false`);
  `CardView @id/car_search_box`
  `@dimen/gearhead_sdk_app_layout_search_box_small_width` (= **320dp**,
  `dimens.xml:1049`) / `match_parent`, margins top/bottom `16dp`, weight 0,
  `focusable false`, `center_vertical`,
  `cardBackgroundColor="@color/search_box_card"`
  (day `#fffafafa`, `colors.xml:3589-3590`; night `#ff172026`,
  `values-night/colors.xml:316`, `colors.xml:3591`) → …hint
  `TextView @id/car_search_box_hint` `text="@string/appdecor_search_box_hint"`
  (exact string NOT READ), `textColor="@color/search_box_text_secondary"`
  (day `#8a000000`, `colors.xml:3598-3599`; night `#66ffffff`,
  `values-night/colors.xml:319`, `colors.xml:3600`), same Title style;
  edit container `gone` → `ImageView @id/car_search_box_icon`
  `@dimen/appdecor_icon_size` (= **44dp**, `dimens.xml:185`) marginStart 12dp;
  `CarRestrictedEditText @id/car_search_box_edit_text` `0dp`/`match_parent`,
  weight 1, hint same string, primary `#de000000` day / `#a6ffffff` night
  (`colors.xml:3595-3597`), `background="@null"`, paddings 16dp,
  `singleLine`, `imeOptions actionSearch`, same Title style;
  `FrameLayout @id/car_drawer_button_frame` 96dp×96dp,
  `background="?attr/gearheadHeaderButtonBackground"`, `addStatesFromChildren`
  → `ImageView @id/car_drawer_button` 96dp / 96dp-height, `gone`,
  `scaleType center`, elev 8dp, `focusable false`, center;
  `ImageView @id/search_exit_button` 96dp/96dp-height, `gone`, same bg,
  `src="0x7f080505"`, center, elev 8dp, `focusable true`;
  `ImageView @id/car_mic_button` 96dp/96dp-height, same bg,
  `src="@drawable/ic_mic_enabled_white"`, center, elev 8dp, `focusable false`,
  `end`.
- `app_bar.xml` root `TouchStealingFrameLayout @id/app_bar_container`
  `match_parent`/`wrap_content`, top,
  `foreground="@drawable/app_bar_container_foreground"` →
  `FrameLayout @id/background_app_bar` `match_parent`/`match_parent`,
  `alpha="0"`; `FrameLayout @id/app_bar_inset` `match_parent`/`match_parent`,
  margins start/end `@dimen/app_bar_horizontal_margin`
  (→ `gearhead_edge_column_margin` → `gearhead_width_keyline_1` = **8dp**,
  `dimens.xml:144,928,1163`) →
  `ImageView @id/transition_view` `gone`;
  `LinearLayout @id/widget_container` horizontal `match_parent` /
  `@dimen/app_bar_height` (= **72dp**, `dimens.xml:143`),
  `baselineAligned="false"` →
  `FrameLayout @id/header_button_frame` `@dimen/gearhead_edge_column_width`
  (= `@dimen/gearhead_touch_target_minimum_size` = **68dp**, `dimens.xml:929,1156`)
  / `match_parent`, `gone` →
  `FrameLayout @id/header_button_tap_target`
  `@dimen/app_bar_header_button_size` (= 68dp minimum, `dimens.xml:141`) square,
  center, `background="@drawable/gearhead_oval_focus_background"`,
  `focusable/clickable true` → `ImageView @id/header_button_image`
  `match_parent`/`match_parent`, center;
  `FrameLayout @id/header_icon_container` 68dp/`match_parent`, `gone` →
  `ImageView @id/header_icon_image` `@dimen/app_bar_header_icon_size`
  (= **44dp**, `dimens.xml:142`) square, center, `fitCenter`;
  `FrameLayout` (no id) `0dp`/`match_parent`, weight 1 →
  `TextView @id/app_bar_title` `match_parent`/`match_parent`, `gone`,
  `singleLine`, `ellipsize end`, `center_vertical`, `focusable false`,
  margins `@dimen/app_bar_title_horizontal_margin` (→ `gearhead_padding_1` =
  **8dp**, `dimens.xml:151,1030`),
  `style="@style/TextAppearance.Boardwalk.Body3"`
  (`styles.xml:9288-9291`, parent `TextAppearance.Boardwalk`
  `styles.xml:9267-9271`: `textSize @dimen/boardwalk_body3_text_size` = **24dp**,
  `dimens.xml:221`; `fontFamily ?attr/bodyLegacySansSerifFontFamily`;
  color `@color/boardwalk_default_text_color` → `@color/boardwalk_white` =
  `#ffffff`, `styles/colors 231,292`);
  `LinearLayout @id/tab_strip` `gone`; `LinearLayout @id/auxiliary_buttons_strip`
  `wrap_content`/`match_parent`, `gone`.
- `alpha_jump_layout.xml` root `FrameLayout` →
  `AlphaJumpFab @id/alpha_jump_fab` 96dp, `gone`, `end|top`, margins top 48dp /
  end 22dp, elev 8dp;
  `AlphaJumpKeyboard @id/alpha_jump_keyboard` `match_parent`/`match_parent`,
  `gone`, `background gearhead_sdk_card`, marginTop 96dp, `mode fixed`,
  `use_appdecor_drawer_keyboard true`.

#### `app_launcher_item.xml` / `assistant_scrim.xml`

- `app_launcher_item.xml` root support-`ConstraintLayout` `0dp` /
  `@dimen/gearhead_launcher_app_height` (= **156dp**, `dimens.xml:980`),
  weight 1,
  `background="@drawable/gearhead_rectangle_round_corner_focus_background"`,
  paddings L/R `@dimen/gearhead_launcher_app_left_right_paddding`
  (triple-d in file) = **10dp** (`dimens.xml:981`),
  T/B `@dimen/gearhead_launcher_app_top_bottom_paddding` = **14dp**
  (`dimens.xml:984`), `focusable true`, `visibility="invisible"` →
  `CardView @id/icon_circular_crop_container`
  `@dimen/gearhead_launcher_icon_diameter` (= **84dp**, `dimens.xml:989`) square,
  center, `cardBackgroundColor @android:color/transparent`,
  `cardCornerRadius @dimen/gearhead_launcher_icon_radius` (= **42dp**,
  `dimens.xml:990`), `cardElevation 0dp` →
  `ImageView @id/icon` `match_parent`/`match_parent`;
  `ImageView @id/notification_badge`
  `@dimen/gearhead_launcher_notification_badge_diameter` (= **22dp**,
  `dimens.xml:995`) square,
  `background="@drawable/launcher_notification_badge"`,
  margins top **2dp** (`dimens.xml:997`), right **3dp** (`dimens.xml:996`);
  `ImageView @id/badge_background`
  `@dimen/gearhead_launcher_badge_background_diameter` (= **34dp**,
  `dimens.xml:985`), margins left **53dp** (`dimens.xml:987`), top NOT READ
  (second margin attribute value NOT READ — same element also carries top margin;
  exact second value not captured, see §9.1);
  `ImageView @id/badge` `@dimen/gearhead_launcher_badge_diameter` (= **28dp**,
  `dimens.xml:986`);
  `TextView @id/label` `match_parent` /
  `@dimen/gearhead_launcher_label_height` (= **28dp**, `dimens.xml:991`),
  `layout_marginTop="@dimen/gearhead_launcher_label_margin"`
  (→ `gearhead_baseline_grid_2x` = **16dp**, `dimens.xml:992,898`),
  `maxLines 1`, `center_horizontal`,
  `style="@style/AppLauncherItemText"` (`styles.xml:188-194`, parent
  `GearheadSdkBody2`: `textSize @dimen/gearhead_launcher_label_text_size` =
  **24dp**, `dimens.xml:993`; `textColor @color/boardwalk_white` = `#ffffff`,
  `colors.xml:292`; `alpha @dimen/boardwalk_launcher_text_opacity` =
  **1** day (`dimens.xml:234`) / **0.88** night
  (`values-night/dimens.xml`); `fontFamily
  ?attr/bodyLegacyRobotoRegularFontFamily`; `ellipsize 3`).
- `assistant_scrim.xml` root `FrameLayout @id/assistant_scrim`
  `match_parent`/`match_parent`,
  `background="@color/assistant_scrim_background_color"` (= `#151616`,
  `colors.xml:41`), `focusable="true"`, `alpha="0.24"`. No children.

### 2.3 Night deltas (exact)

`values-night/dimens.xml` (15 lines): **none of the requested dimen tokens
differ** — only opacities (`boardwalk_assistant_mic_icon_unavailable_opacity`
0.46→0.4, `boardwalk_launcher_text_opacity` 1→0.88, `boardwalk_opacity1` 1→0.88,
`opacity2` 0.72→0.6, `opacity3` 0.56→0.5, `opacity4` 0.24→0.2, hun/notification
opacities, `media_image_scrim_alpha` 0.6→0.8, `rail_icon_alpha_normal` 0.7→0.6).
`values-night/colors.xml`: `gearhead_sdk_card` → dark `#ff172026`;
`gearhead_sdk_card_background` → `#191f27`; `search_box_card` → `#ff172026`;
`search_box_text_primary` → `#a6ffffff`; `search_box_text_secondary` →
`#66ffffff`; `speedbump_drawer_scrim` → `#e6172026`.
`values-night/integers.xml`: only `gearhead_rail_icon_alpha` 255→224.
`values-night/styles.xml`: only survey/DayNight aliases — **none of the
requested Widget/GearheadSdk styles overridden**. No `values-night-v*` dir.

### 2.4 Behavior classes (all paths under
`analysis/maauto/jadx-out/sources/`)

- `com/google/android/apps/auto/components/system/statusbar/RailStatusBarFragment.java`:
  `onCreateView:218-231` picks `hero_vertical_rail_statusbar` if `nso.F()&&D()`,
  `vertical_rail_statusbar` if `F()`, `rail_statusbar_rhd` if `J()`, else
  `rail_statusbar`; `onGetLayoutInflater:234-245` clones
  `ThemeOverlay_Gearhead_Coolwalk_Dark` vs `DayNight` on `leg.a().b()` if
  `acxa.aJ()`; `onViewCreated:248-497`; hero `z2:454-456` →
  card+frame `setFocusable(false)/setClickable(false):458-459,471-472`, else
  `true/true` + `OnClickListener nmp:463` + `OnLongClickListener jzr:464`,
  wrapper focus foreground `kwi` with
  `rail_coolwalk_status_bar_focus_drawable_inset:478-483`; badge
  `e(count,verticalRail):166-211` `ValueAnimator.ofInt` width/height +
  alpha/translationY, `duration 200`, listener `nzi`. No dpad/rotary/touch-target
  code in file.
- `.../system/facetbar/hotseat/RailHotseatItemView.java`: ctor `73-103`
  inflates `rail_hotseat_item_view_dynamic_icon_shape:79`, padding
  `coolwalk_rail_dock_icon_padding_gm3:93-95`, foreground `nmn:101`;
  `a(nmj):125-190` defers via `ols.a+kok:140` if `!isLaidOut:134`, badge
  `VISIBLE/GONE:153-159`, `setOnTouch(jzt)+setOnClick(nmp):161-162,188-189`;
  icon change → shrink `scale 0.85, alpha 0.75, Accelerate, 83ms:174`;
  pressed `e(view,event):244-254` `ACTION_DOWN scale 0.95 50ms`,
  `ACTION_UP setBackground(null) scale 1.0`; disabled `g(view,disabled):113-123`
  `saturation 0 + alpha 178` else `clear + 255`; `c(color):222-227` outline.
- `.../system/facetbar/widget/RailWidgetView.java`: init `106-186` inflates
  `sys_ui_coolwalk_rail_widget[_right]` on `nsr.J()` RHD; focus ring `kwi` on
  `background_pill:157-163`; transition `coolwalk_rail_widget:164-166`;
  `b(nnl):321-371` queue, hide `a(0.5,0,b,k):333` or exit
  `beginDelayedTransition(m,E)+alpha/scale:338-342`, mini enter `345-351`,
  `e():353-355,367-368`; `e(nnl):419-526` always `beginDelayedTransition:428`,
  bg focus vs ripple `430-436`, `setFocusable(!z):468-469`, focus restore
  `516-525`; `m(nnl):281-294` shrink `0.85/0.75 Accelerate:292`;
  `o():300-315` fade `alpha 0 67ms`; `k():244-274` badge `alpha 0→1`;
  `g():188-210` `colorSurface:192`, pill ripple gm3:204;
  `d():384-414` `colorSurface/OnSurface:401-402`.
- `.../system/dashboard/design/DashboardCardView.java`: `onAttached:125-128`
  `addOnGlobalFocusChangeListener(f:hle)`, `onDetached:131-134` remove;
  `addView:75-117` forwards to `b:ConstraintLayout`;
  `onInterceptTouchEvent:137-143` swipe via `e.e(motion)` if `d` (attr
  `mwm.a:61-62`, `d=true:45`); `onTouchEvent:158-178` handles 1/2/3 via `mwu`;
  `c:close_button_inner` click `mli:60`. Base
  `.../coolwalk/card/CoolwalkCardView.java:57-91` draws `kwi` in
  `onDrawForeground`, `drawableStateChanged:64-70`.
- `com/google/android/projection/gearhead/sdk/CarDrawerLayout.java:33-88`:
  ctor `h(1):39`; scrim `k(color):57-59` `super.k((rgb)|(alpha*0.8)<<24)`;
  `dispatchGenericMotionEvent:43-48` + `dispatchKeyEvent:51-54` call `s()`;
  `onLayout:62-88` resets `l.e(0)`, drawer children `alpha=fE`. No
  back/scrim-click/focus code in file.
- `.../components/drawer/MotionFilteringDrawerLayout.java:25-84`: `y(1.0)`,
  `setFocusable(0)`, scrim transparent:30; `y(f):82-84` `touchSlop*f`;
  `k(i):35-37` dim `*0.8`; `onInterceptTouchEvent:41-80` tracks X axis vs
  `lbb.a` slop, DOWN/UP/CANCEL reset, in-slop MOVE returns false.
- `.../coolwalk/button/CoolwalkButton.java`: `b=kwi:32`,
  `LayerDrawable[foreground,kwi]:48-50`; `hasFocus:74-76` /
  `requestFocus:85-87` delegate to parent `FocusInterceptor`;
  `onAttached:79-82` auto-wrap; setters sync `FocusInterceptor.a:90-111`;
  `setVisibility:136-141` forwards to parent; `setLayoutParams:128-133`
  throws if parent is `FocusInterceptor` (touch target lives on parent).
- `.../ui/media/PlayPauseStopCoolwalkButton.java:30-62`: merges
  `R.attr.state_play` if `c in 1,2`, `state_stop` if `c in 3-6,8-11 && d!=1`,
  else `state_pause`; unknown logs `n.h():39`; `c=-1,d=1:25-26`.
- `.../coolwalk/focusring/TappableRegion.java:30-135`: style
  `Widget_Gearhead_Coolwalk_TappableRegion`, `kwi:34` foreground:36,
  `RippleDrawable(coolwalk_tappable_region_ripple_color_selector):41-43`;
  same focus delegation + `setBackground` guard `c:84-95`.
- `.../coolwalk/focusring/FocusInterceptor.java:35-180`: `focusable+clickable`,
  `descendantFocusability 393216`; `a(view):47-64` sync;
  `b(view):66-91` throws if parent Constraint/Relative, else wraps + `fww.u`;
  dpad `getNextFocusDown/Up/Left/Right/Forward:121-163`;
  `dispatchSetPressed:116-118`, `performClick:178-180`,
  `onFocusChanged:166-175` + `setActivated(z)`.
- `.../sdk/ui/PagedListView.java`: rotary `onGenericMotionEvent:361-435`
  ignores if `gearhead_sdk_true_for_touch||f():365-367`, 50px steps
  `requestFocus:398-404`, ≥15px drag consumes:385; dpad
  `dispatchKeyEvent:238-252`, `onKeyUp:446-455` (22→66, 21→l()),
  `onRequestFocusInDescendants:491-498`, `onLayout:458-488` autofocus;
  `d(show):217-235` scrollbar; `n(colorIdx):339-342`;
  `PagedScrollBarView.b():167-196` thumb vs night; `setClickable(true),
  focusable(false):151-152`.
- `.../drawer/AlphaJumpFab.java:17-42`: `ic_alpha_jump_fab` +
  `alpha_jump_fab_background`, `setFocusable(false):29`, click forces
  unfocusable. `.../drawer/AlphaJumpKeyboard.java:70-152`: `a(list)` sets
  text/enabled/focusable/click; `onConfigurationChanged:104-152`
  re-inflates, re-applies visibility/enabled/focusable, drawer bg if `j:108`.
  `AlphaJumpKey.java:27-30` square `onMeasure(size,size)`.
- Scrims are visibility bits, not windows:
  `.../ui/actionpanel/ExpandingActionPanel.java:206,209-278,325-327,368-370`
  (`R.id.action_panel_scrim`, `ChangeBounds+Fade`, `requestFocus`);
  `.../ui/media/MediaPlaybackView.java:221-234,733-748`
  (`R.id.playback_scrim`, `playback_scrim_coolwalk`, auto-hide when
  `i==0 && o VISIBLE && driving (nrd)`).
- `.../system/facetbar/ResizeButton.java:44-123`: Compose `kmd`;
  type Exit/Smaller/Larger from `nit.a[1]:56-66`; orientation V/H from `[0]`;
  `a=true GM3:54`; click `njg:111`.
- `.../ui/media/MediaPlaybackView.java`: `onRequestFocusInDescendants:808-810`
  / `setVisibility:813-818` via `n()`; `i(state):544-662`
  (`k.c=Q():562`, corners `play_button_rounded_corners` vs `width/2:565,568`,
  spring `emp:577-581`, skips/queue `omu.m:619-650`, `l():206-219`);
  `d(colors):284-316` (bg `i3`, gradient `dvd.e`, button filter `i6:298-304`,
  progress `f/j` + thumb `i4:306-314`); touch targets `un_ar_icon_size:692-714`;
  progress focus `krj:753`.
- `.../ui/listview/GhListView.java:26-46`: `n=nrd.a().c():29` rotary cap,
  `q(omg:30)`, focus listener `krj:31`, `setPaddingRelative` no-op:45-46.
  `UnListView.java:56-80` key 22/21 routing.
- `.../ui/telecom/UnCallView.java` (+ `MaterialUnCallView.java`):
  `b(one):94-204` incoming (`k==2`) vs active (`3`) via `ncp e/f/g/h/i/j`;
  disabled `f(view,z):74-77` + alpha; hold disables `h/k/f:151,171-172`;
  `onRequestFocusInDescendants:319-321`; `onFinishInflate:257-316` 4
  `ImageButton` `un_ar_icon_size:262`, `end_call_fab focusable:285`,
  `PHONE_FACET:299-300`; duration `postDelayed(l,1000):201`.
- `.../ui/widget/navigation/DetailedNavigationStepView.java:34-78`
  (`turn_symbol/distance/description/lanes_image/turn_container/lanes_container`,
  GONE-if-empty) +
  `.../ui/widget/navigation/NavigationTravelEstimateView.java:31-59`
  (`arrival_time_text/time_and_distance_text`, GONE-if-empty). No
  focus/dpad/rotary/night/touch code in either file.
- **MISSING classes:** `RailHotspot` (facetbar has only `CircleOverlayView`,
  `ResizeButton`, `hotseat/`, `widget/`); `UnlimitedBrowsePagedListView`
  (sdk/ui has `PagedListView`, `CarRecyclerView`, `AutoTunedRecyclerView`,
  `PagedScrollBarView`, `FocusClusterLayout`, `MaxWidthLayout`,
  `CarLayoutManager`, `FloatingActionButton`); standalone assistant-scrim class;
  `*Voice*Plate*` class for `@id/voice_plate`.

### 2.5 `layout-car/` + `values-car/`

- `layout-car/`: 1 file — `common_switch_bar_toggle_widget.xml` (Switch,
  `?attr/switchStyle`). No HU surface file.
- `values-car/`: `dimens.xml` (28 `bubble_*`+`survey_*` entries, e.g.
  `bubble_size` 64dp vs 56dp base — no requested tokens), `drawables.xml` (6
  survey icons + `survey_close_button_icon=?unknown_ref: 7f0809f6`),
  `strings.xml` (3 survey fonts: `google-sans[-medium|-regular]`),
  `styles.xml` (11 survey styles). No requested Widget styles.

---

## 3. MA `CarDisplay.kt` inventory (exact lines)

Path prefix: `auto/src/main/java/com/vayunmathur/auto/platform/CarDisplay.kt`.
No `Typeface` set anywhere, no `elevation` anywhere, no icon tint (hotseat
explicitly untinted, L728 comment). Constants: `MATCH` L1049, `WRAP` L1050,
`COLUMNS=3` L1051, `MAX_DOCK_APPS=3` L1056, `MAPS_PACKAGE/LABEL` L1059-1060,
`MAX_INVALIDATE_FPS=60` L1067, `dp()` L1045-1046.

- `onCreate` L463-492: `root` L470-474 + `content` FrameLayout L476-480 +
  `facetBar(apps)` L482 + `setContentView` L483 + `pendingNowPlaying` L485-486 +
  clock start L488-491.
- `root` LinearLayout L470-474: VERTICAL L471, bg `#101418` L472,
  `ViewGroup(MATCH,MATCH)` L473.
- `content` FrameLayout L476-480: `LinearLayout(MATCH,0,1f)` L477; children
  `splitCards(apps)` L478, `appDrawer(apps).also{drawerView=it}` L479.
- `facetBar` LinearLayout L681-686: HORIZONTAL L682, CENTER_VERTICAL L683,
  bg `#161C24` L684, `LinearLayout(MATCH,WRAP)` L685,
  padding `dp(10),dp(6),dp(6),dp(6)` L686.
  Home `facetButton` L687-691 (glyph `R.string.car_home_glyph` L688 =
  `⌂`, `strings.xml:279`; tap `drawerView=GONE` L689).
  Hotseat container LinearLayout L693-696: HORIZONTAL, CENTER,
  `LinearLayout(0,WRAP,1f)` L696; fills `apps.take(MAX_DOCK_APPS)` L697 +
  `hotseatCell(app)` L698 + drawer cell L701-704 (`drawerView=VISIBLE` L702).
  Status `facetStatus()` L707.
- `facetButton` TextView L712-724: text glyph L714, desc L715, WHITE L716,
  **28sp** L717, CENTER L718, `LinearLayout(dp(68),dp(68))` L719,
  clickable/focusable L720-721, click L722.
- `hotseatCell` FrameLayout L732-760: `LinearLayout(dp(68),dp(68))` L734,
  clickable/focusable L735-736. App ImageView L739-744: drawable L740, desc
  label L741, padding `dp(8)` all L742,
  `FrameLayout(dp(56),dp(56),CENTER)` L743; tap `startActivity(app.launch)`
  L746. Drawer-glyph TextView L749-755 (null app): text `car_drawer_glyph`
  L750 = `▦` (`strings.xml:277`), WHITE, 28sp, CENTER,
  `FrameLayout(dp(56),dp(56),CENTER)` L754; tap `onDrawerTap` L757.
- `facetStatus` TextView (`clockView` L774) L767-775: WHITE L769, **24sp** L770,
  `END|CENTER_VERTICAL` L771, `LinearLayout(WRAP,dp(68))` L772,
  padding `dp(6),0,dp(6),0` L773.
- `splitCards` LinearLayout L783-789: HORIZONTAL L784, `ViewGroup(MATCH,MATCH)`
  L788 (comment L785-787), padding `dp(16),dp(8),dp(16),dp(8)` L789. Children:
  `navCard` `LinearLayout(0,MATCH,1f)+marginEnd=dp(8)` L791-795,
  `nowPlayingCard` `LinearLayout(0,MATCH,1f)+marginStart=dp(8)` L797-803.
- `navCard` LinearLayout L819-823: VERTICAL L820, CENTER L821, bg `#1B2430`
  L822, padding `dp(8)` all L823. Clickable/focusable only if maps!=null
  L864-865, `startActivity(maps.launch)` L866 (pick L815-818).
  Banner TextView (`navBanner` L829) L825-834: **18sp** L826, WHITE L827,
  CENTER L828, `GONE` L830, `LinearLayout(MATCH,WRAP)+bottomMargin=dp(8)`
  L831-833.
  TextureView L837-861: `LinearLayout(MATCH,0,1f)` L838, listener L839-860 →
  `mapSurfaceListener(Surface,w,h)` L845 / `(null,0,0)` L855 / `true` L856.
- `nowPlayingCard` LinearLayout L562-572: VERTICAL L563, bg `#1B2430` L564,
  padding `dp(24),dp(16),dp(24),dp(16)` L565, `LinearLayout(MATCH,WRAP)` L566
  (caller overrides to `LinearLayout(0,MATCH,1f)+marginStart=dp(8)` L798-802),
  clickable L567, focusable L568, `GONE` L571, click `onMediaTap` L617.
  Header TextView L574-579: `car_now_playing` L575 (`strings.xml:263`),
  `#8AB4F8` L576, **14sp** L577. Title (`mediaTitle` L585) L581-586: **28sp**,
  maxLines 1, WHITE. Subtitle (`mediaSubtitle` L592) L589-593: **24sp**,
  `#C7CFD9`. State (`mediaState` L599) L596-600: 14sp, `#8AB4F8`. Track View
  L602-607: bg `#2A3138`, `LinearLayout(MATCH,dp(4))+topMargin=dp(12)`.
  Fill (`mediaProgress` L614) L609-615: bg `#8AB4F8`,
  `LinearLayout(0,dp(4))+topMargin=dp(-4)` (negative overlap). Width mutated
  L546-548. Bounds L619-627 (`OnGlobalLayoutListener` L620,
  `getLocationOnScreen` L623, `onCardBounds` L624).
- `appDrawer` LinearLayout L895-900: VERTICAL L896, bg `#101418` L897,
  `ViewGroup(MATCH,MATCH)` L898, `GONE` L899, padding `dp(16)` all L900.
  Heading TextView L902-907: `car_drawer_apps` L903 (`strings.xml:275` =
  `Apps`), WHITE, **28sp**, padding `0,0,0,dp(16)` L906.
  Empty TextView L911-917 (if `apps.isEmpty()` L909): `car_no_apps` L912
  (`strings.xml:273`), `#8AB4F8`, **24sp**, CENTER,
  `LinearLayout(MATCH,0,1f)` L916.
  GridLayout L921-924: `columnCount=COLUMNS` L922,
  `LinearLayout(MATCH,0,1f)` L923. Item wrapper
  `GridLayout.LayoutParams(width=0,height=dp(156),columnSpec=spec(UNDEFINED,1,FILL,1f))`
  L927-935, tap `startActivity`+`drawerView=GONE` L937-940.
- `launcherItem` LinearLayout L956-961: VERTICAL L957, CENTER_HORIZONTAL L958,
  padding `dp(8)` all L959, clickable/focusable L960-961.
  Icon ImageView L963-973: drawable L964, desc L965,
  `LinearLayout(dp(84),dp(84))` L966, `clipToOutline=true` L967,
  `outlineProvider=setOval(0,0,w,h)` L968-972 (circular, no CardView L952-953).
  Label TextView L976-985: label L977, WHITE L978, **24sp** L979, CENTER L980,
  maxLines 1 L981, `LinearLayout(MATCH,dp(28))+topMargin=dp(16)` L982-984.
- Dead code (no callers): `statusBar()` L632-665, `divider()` L667-670,
  `appGrid()` L990-1006, `appCell()` L1008-1033 (72dp icon L1019, 16sp label
  L1026), `emptyState()` L1035-1043 (20sp L1039).
- Touch/key/scroll: `TapBounds` L1082-1085 (inclusive contains);
  `mediaCardBounds` volatile L96, `onCardBounds` L136-138 (ctor param L395);
  `handleCarTap(x,y)` L218-223 (null→false, miss→false, hit→post onMediaTap);
  `injectTouch` L236-240; `dispatchTouch` L285-335 (decor L290,
  ACTION_DOWN+1-pointer card-intercept L295-298, downTime L300-305,
  POINTER_DOWN/UP shift L306-312, id L313-315, x/y/pressure=1 L316-322,
  `SOURCE_TOUCHSCREEN` L323-327, dispatch L328, recycle L329, reset on UP/CANCEL
  L330-334); `injectKey` L246-256 (`KeyEvent` DOWN/UP L249-252,
  `decorView.dispatchKeyEvent` L253); `injectScroll` L262-282
  (`AXIS_VSCROLL=delta` L266-268, `ACTION_SCROLL` + `SOURCE_MOUSE` L269-277,
  `decorView.dispatchTouchEvent` L278).
- Invalidation: `startFrameInvalidation(fps)` L199-201 →
  `CarPresentation.startFrameInvalidation` L441-454 (`stop` L442, decor L443,
  `intervalMs=(1000/fps.coerceIn(1,60)).coerceAtLeast(0)` L444 cap
  `MAX_INVALIDATE_FPS` L1067, `tick{invalidate; postDelayed}` L446-451);
  `stopFrameInvalidation` L456-461; doc L433-437, L191-198; `onStop` stops L501;
  1Hz clock `ticker` L425-431 started L488-491.
- Content updates: `updateNowPlaying` GONE-when-idle L525-527, texts L531-536,
  progress fraction L543-551 (`progressFraction` L1069-1073: 0.5 if no
  duration); `updateNavSnapshot` L876-885 (VISIBLE iff `guidanceActive &&
  bannerText()!=null` L879-881 else GONE); `bannerText()` L37-46
  (`"$turn · $road"` L41); `onStop` nulls views/listeners L499-512.
- Trusted route: `trusted:Boolean=false` L63-69, `PRESENTATION` else
  `|trustedDisplayFlag()` L115-129, reflective `VIRTUAL_DISPLAY_FLAG_TRUSTED`
  else 0 L1091-1101, KDoc L117-119/51-56.

---

## 4. Per-surface diffs (checklists — `[ ]` = open gap)

### 4.1 Status bar / clock

- [x] Signal/battery/phone-status content. Gearhead rail carries a
  `RailStatusBarFragment` (`gh_coolwalk_facet_bar.xml: @id/status_bar`,
  `RailStatusBarFragment.java:218-245` layout/theme pick + `248-497` clock,
  icon row, notification badge count with 200ms width/height/alpha/translationY
  animator `166-211`). MA now builds the same vertical packed chain in
  `CarDisplay.kt:facetStatus/iconRowView` — icon row (36dp signal overlay +
  24dp cell bars + 24dp battery with 6dp sides/2dp verticals + 24dp DND gone +
  8dp badge with 12/4dp margins, all `colorOnSurface` day / 224 night)
  constrained above the 24sp clock, fed by `PhoneStatusMonitor`
  (TelephonyManager bars 0-4, sticky battery level + charging, interruption
  filter DND, listener badge count) on the 1Hz ticker. Absent sources stay
  gone (never faked); the 200ms badge animator is not reproduced (instant
  visibility). DHU shots: `dhu_ma_statusbar_*` vs `dhu_gearhead_statusbar_*`.
- [x] Status-bar fragment layouts compared. `rail_statusbar`,
  `hero_vertical_rail_statusbar` read fully (vertical packed chain, icon row
  margins, badge/DND slots, autosize single-line clock `h:mm`/`HH:mm` Body3
  `colorOnSurface`, card `windowBackground` radius 18dp elev 0). Remaining
  variants (`vertical_rail_statusbar`, `rail_statusbar_rhd`) share the same
  slot geometry mirrored; see §9.1. MA equivalent: `facetStatus()` cluster.
- [x] Day/night theme pick. Gearhead clones
  `ThemeOverlay_Gearhead_Coolwalk_Dark` vs `DayNight`
  (`RailStatusBarFragment.java:234-245`). MA `setNight` recolors the full
  palette per §2.3 night deltas (cards `#ff172026`, shadow `#191f27`, hint
  `#66ffffff`, labels 0.88, icons/clock 224, scrim `#e6172026`) and tints the
  status icons; the service drives it from `NavSnapshot.isNight` (HU truth,
  phone seed fallback) via `VideoSinkChannel.setNightDark`.
- [x] Hero (minimized-widget) focus suppression. Gearhead sets
  card+frame unfocusable/unclickable when `z2` (`RailStatusBarFragment.java:
  454-459,471-472`), else clickable + focus foreground `kwi` with
  `rail_coolwalk_status_bar_focus_drawable_inset` (`:463-464,478-483`).
  MA maps hero to badge-visible (`updatePhoneStatus`): badge on means
  unfocusable/unclickable, badge off means clickable with the focus ring.

### 4.2 Facet / rail bar

- [x] Assistant slot absent. Gearhead: 68dp `assistant_icon_container` with
  56dp `live_fragment` (gone) + `circle_overlay` (gone) + 68dp `assistant_icon`
  (gone, `gs_mic_fill1_vd_theme_24`) (`gh_coolwalk_facet_bar.xml`). MA: no
  assistant view anywhere in `CarDisplay.kt` (deliberate — no backend).
- [x] Dashboard icon differs. Gearhead `CoolwalkButton
  @id/launcher_and_dashboard_icon` 68dp, marginStart 10dp,
  `ic_dashboard_icon_lhd`, Rail.Button style (padding 8dp, inset 6dp, icon 40dp;
  `styles.xml:18421-18432`). MA home `facetButton` TextView 68dp
  (`CarDisplay.kt:712-724`), glyph `⌂` (`strings.xml:279`), 28sp WHITE, no
  padding/inset/icon semantics (`:719` LP only).
- [x] Ongoing-widget slots absent. Gearhead `ongoing_widget_container` +
  `RailWidgetView @id/ongoing_widget` (gone) + `dock_container` +
  `rail_ongoing_widget` (gone), margins 6dp, maxWidth 750dp, with
  show/hide/transition/focus/ripple behavior
  (`RailWidgetView.java:106-526`). MA: no widget container or pill.
- [x] `etc` affordance absent. Gearhead `etc_icon` (gone, marginLeft 8dp,
  `ic_gearhead_etc_rail_icon`, `?attr/colorOnSurface`, alpha 0.6)
  (`gh_coolwalk_facet_bar.xml`). MA: none.
- [x] Hotseat include visibility differs. Gearhead includes
  `sys_ui_rail_hotseat` with `visibility="invisible"`. MA hotseat container is
  always VISIBLE when built (`CarDisplay.kt:693-706`).
- [x] Hotseat supports 4 slots; MA caps at 3. Gearhead
  `hotseat_one..four` (`sys_ui_rail_hotseat.xml`), each 68dp
  (`coolwalk_rail_dock_icon_touch_target_size`), `iconTint @null`, with
  defer-if-`!isLaidOut`, badge show/hide, touch+click, icon-change shrink
  (0.85/0.75, 83ms Accelerate), press scale 0.95/50ms, disabled
  saturation-0/alpha-178 (`RailHotseatItemView.java:73-254`). MA
  `MAX_DOCK_APPS=3` (`CarDisplay.kt:1056`), `apps.take(3)` (`:697`), 68dp cells
  (`:734`), 56dp untinted icons with 8dp padding (`:739-744`), no badge, no
  press/disabled animation, no defer.
- [x] Dock icon geometry close but padding differs. Gearhead dock touch 68dp,
  icon 52–56dp family (`dock_icon_touch 68dp :580`, `dock_button_icon 52dp
  :572`, `hotseat_button_icon 56dp :581`, `hotseat_icon_touch 74dp :585`).
  MA: 68dp cell + 56dp icon + 8dp padding (`CarDisplay.kt:734,742-743`) —
  arithmetically 56+2×8=72 ≠ 68, so MA icons overflow the nominal cell by 4dp
  (ImageView is 56dp with 8dp internal padding → 40dp visible; record exact
  follow-up: measure drawn bounds on DHU shot).
- [x] Hotseat guidelines absent. Gearhead `hotseat_start/end` 145dp + edge
  `2dp` spacer + `edge_barrier` (`gh_coolwalk_facet_bar.xml`). MA: none.
- [x] `rail_invisible_scrim` absent. Gearhead transparent `0dp/match_parent`
  view. MA: none.
- [x] Resize control absent. Gearhead `resize_button_ghost` 36×56dp gone +
  `resize_button_tap_target` 64dp gone/clickable +
  `ResizeButton` gone/vertical (`gh_coolwalk_facet_bar.xml`,
  `ResizeButton.java:44-123` Compose Exit/Smaller/Larger). MA: none.
- [x] Native-rail (back/exit) mode absent. Gearhead `native_rail` gone with
  68dp back (`gs_arrow_back_vd_theme_48`) + exit (`0x7f08093a`) buttons. MA:
  none.
- [x] Voice-plate slot absent. Gearhead `voice_plate` gone match/match bottom
  windowBackground; no `*Voice*Plate*` class found (MISSING). MA: none.
- [x] Rail padding differs. MA rail padding `10,6,6,6dp`
  (`CarDisplay.kt:686`) vs gearhead per-slot margins (dashboard 10dp start,
  rail margins 6dp, gutter 10dp end) — reconciling requires the DHU shot
  (§9.3).

### 4.3 Media card

- [x] Album art absent. Gearhead `album_art` 0dp centerCrop full-bleed +
  `album_art_scrim` (gone, `dashboard_media_album_art_scrim`, fitXY) +
  `source_badge` 24dp circle (`frag_dash_media.xml`). MA card
  (`CarDisplay.kt:561-630`) has no ImageView at all (header/title/subtitle/
  state/track/fill only) — admitted at `CarDisplay.kt:554-560`; `NowPlayingInfo`
  carries title/artist/playing/position/duration only
  (`MediaNowPlaying.kt:12-23`).
- [x] Prev/next + play/pause row absent. Gearhead `media_button_row`: 88dp
  `action_left`, 88dp `play_pause` (`ic_play_pause_stop_solid`,
  state_play/state_stop/state_pause merge in
  `PlayPauseStopCoolwalkButton.java:30-62`), 88dp `action_right`, Space
  weights 1/2/2/1, insets 12dp, `nextFocusForward` chains, Primary
  backgroundTint+overlay (`frag_dash_media_button_row.xml`,
  `styles.xml:18156-18166`). MA: whole card is one tap target → `onMediaTap`
  toggle (`CarDisplay.kt:567-568,617,221-222`; `MediaPlaybackMonitor.toggle()
  :67-72` via `VideoSinkChannel.kt:328`); no prev/next, no queue
  (`MediaPlaybackMonitor.kt:18-23` GAL 11/12 replacement, never speaks media
  channels).
- [x] Text specs differ. Gearhead title Body2 28dp `?attr/titleFontFamily`
  primary, maxLines 1 (`integers.xml:37`), `includeFontPadding true`
  (`bools.xml:27`) + style-level false, ellipsize 3, marginTop 5dp; subtitle
  Body3 24dp `bodyLegacySansSerifFontFamily`, marginBottom 5dp; container
  margins top 4dp / bottom 7dp + bias fraction; text padding 5dp
  (`frag_dash_media.xml`, `styles.xml:18514-18519,18170-18172,9595-9597`).
  MA: header 14sp `#8AB4F8` (`:574-579`), title 28sp WHITE maxLines 1
  (`:581-586`), subtitle 24sp `#C7CFD9` (`:589-593`), state 14sp `#8AB4F8`
  (`:596-600`); card padding 24/16 (`:565`); no fontFamily, no bias, no
  includeFontPadding control.
- [x] Progress bar differs. Gearhead `LinearProgressIndicator` 0dp×4dp,
  `?attr/dashProgressColor` / `?attr/dashProgressSurface`, corner-radius
  fraction (value NOT READ), thickness 4dp, marginBottom 4dp
  (`frag_dash_media.xml`). MA: track View `MATCH×4dp` `#2A3138` topMargin 12dp
  (`:602-607`) + fill `0×4dp` `#8AB4F8` topMargin **-4dp** overlap (`:609-615`),
  width mutated post-layout (`:546-548`, fraction `:1069-1073`, 0.5 default);
  no theme colors, no rounded corners.
- [x] Card container differs. Gearhead `DashboardCardView` match/match,
  `?attr/coolwalk_darkThemeOverlay`, `swipeToDismissEnabled false`, pager
  guideline 52dp, `TappableRegion` wrappers with `kwi` + ripple +
  `DashPrimaryTappableRegionAlbumArtMatchingShapeAppearance`, focusable primary
  target false, `nextFocusForward` chain, focus-driven `onDrawForeground`
  (`DashboardCardView.java:45-178`, `CoolwalkCardView.java:57-91`,
  `TappableRegion.java:30-135`). MA: LinearLayout card, bg `#1B2430`,
  padding 24/16, clickable+focusable, GONE-when-idle (`:525-527`), no swipe
  config, no focus ring (`kwi` absent), no pager guideline.
- [x] Driving-restriction auto-hide absent. Gearhead `MediaPlaybackView.n():
  221-234` hides action panel when `i==0 && o VISIBLE && driving (nrd)` +
  `playback_scrim` handling `:733-748`. MA: no `nrd` equivalent; card stays.
- [x] Source badge absent (see art item): 24dp circle, bias fraction
  (`frag_dash_media.xml`). MA: none.

### 4.4 Nav card

- [x] Nav template stack absent — mirror only. Gearhead owns
  `ui/widget/navigation/DetailedNavigationStepView` (turn/distance/description/
  lanes, GONE-if-empty, `:34-78`) +
  `NavigationTravelEstimateView` (arrival/time-distance, GONE-if-empty, `:31-59`)
  inside `CarAppLayout` nav templates. MA `navCard`
  (`CarDisplay.kt:814-869`): VERTICAL CENTER bg `#1B2430` padding 8dp, banner
  18sp WHITE CENTER GONE (`:825-834`, `LinearLayout(MATCH,WRAP)+bottom 8dp`),
  `TextureView` weight-1 (`:837-861`) fed by `CarMapsMirror`
  (`SurfaceMapRenderer`, `CAR_LAYERS poi=true transit=false`,
  `CarMapsMirror.kt:67-70,102-129,178`, zoom 17.0 nav / 15.0 idle `:181-182`,
  E7→deg + puck + identity-gated route `:131-149`); host
  (`CarAppProjectedService`, `MapsCarAppService`) only probed
  (`GearheadHostProbe.kt:37-49,56-73`), session default mirror
  (HANDOFF §10). Text format `"$turn · $road"` (`CarDisplay.kt:37-46`) vs
  gearhead step/lanes/ETA views — no field mapping exists.
  - [x] RESOLVED (host rework): the mirror is deleted. `CarAppHost`
    binds `MapsCarAppService`, implements the host binders
    (`ICarHost`/`IAppHost`/`INavigationHost`/`IConstraintHost` per the
    `app-1.4.0` contract), hands Maps the nav card's `TextureView` surface
    as a `SurfaceContainer` (Maps draws its own map, same renderer/archive/
    tile cache as the phone), and renders the real `NavigationTemplate`
    into the step header: cue/road, distance, lane arrows from
    `Step.lanes`/`LaneDirection`, remaining-time ETA, and the app's own
    action strip through its `OnClickDelegate`. Ch8 map touches forward
    into the app's `SurfaceCallback` (tap = click, drag = scroll). Empty
    launch tile only when maps is missing or the bind fails.
- [x] Nav banner typography is MA-invented. 18sp WHITE CENTER
  (`CarDisplay.kt:826-828`) — no gearhead token matches (gearhead nav text
  lives in unread `CarAppLayout`/template styles; see §9.1).
- [x] Map surface lifecycle differs. MA surface callbacks
  (`CarDisplay.kt:839-860`) forward to `mapSurfaceListener`; composition is the
  bring-up owner's work (`CarMapsMirror.kt:18-30` KDoc). Gearhead binds nav
  into `CarAppLayout`/`CarDrawerLayout` decor with focus + driving-restriction
  handling — not compared (template layouts NOT READ).

### 4.5 Drawer + launcher grid

- [x] Container differs. Gearhead `CarDrawerLayout` (`sdk/CarDrawerLayout.java:
  33-88`): scrim `*0.8` (`k(color):57-59`), generic-motion/key `s()`
  (`:43-54`), `onLayout` alpha reset (`:62-88`); motion filtering variant
  (`MotionFilteringDrawerLayout.java:25-84`): unfocusable, transparent scrim,
  touchSlop-scaled X-axis intercept. Drawer pane `match/match`,
  `layout_gravity left`, bg `gearhead_sdk_card`, **`layout_marginEnd="96dp"`**
  (`drawer_layout.xml`) with `drawer_contents` + `alpha_jump` stubs. MA
  `appDrawer` (`CarDisplay.kt:894-948`): plain VERTICAL LinearLayout bg
  `#101418` `ViewGroup(MATCH,MATCH)` GONE padding 16dp — full-screen overlay,
  no scrim factor, no motion filter, no 96dp end margin, no stub inflation.
- [x] Open/close triggers gutted. MA drawer opens ONLY via hotseat drawer cell
  (`CarDisplay.kt:702` VISIBLE) and closes via Home (`:689`), launcher tap
  (`:939`), or initial GONE (`:899`) — 4 lines total, no scrim-tap, no back,
  no focus-loss, no park auto-close. Gearhead: drawer-layout gesture/key
  dispatch + `PagedListView` dpad/rotary + focus-restore paths (§2.4) — exact
  back/scrim mapping lives in unread `CarDrawerLayout` base/`DrawerLayout`
  behavior (see §9.1).
- [x] List substrate differs. Gearhead `PagedListView` (`sdk/ui/PagedListView.
  java`): rotary 50px-step focus / 15px-drag consume (`:361-435`), dpad
  22/21/283/282 routing (`:238-252,446-455,491-525`), scrollbar show
  (`:217-235`), night recolor (`:339-342`), `clickable true / focusable false`
  (`:151-152`); `GhListView` adds rotary cap + `krj` focus (`:26-46`);
  alpha-jump FAB unfocusable + keyboard with config-change re-inflate
  (`AlphaJump*.java`). MA: static 3-col `GridLayout`
  (`CarDisplay.kt:921-935`), no scroll, no rotary, no dpad routing, no
  scrollbar, no alpha jump.
- [x] Launcher cell metrics close but not identical. Gearhead cell
  (`app_launcher_item.xml`): 0dp-weight × **156dp**, focusable, `invisible` by
  default, focus bg `gearhead_rectangle_round_corner_focus_background`,
  paddings 10dp L/R + 14dp T/B; icon `CardView` **84dp** transparent radius
  **42dp** elev 0dp + `ImageView` match; notification badge **22dp**
  (`launcher_notification_badge`, margins top 2dp / right 3dp); badge bg
  **34dp** (left margin **53dp**, top margin NOT READ); badge **28dp**; label
  `match × 28dp`, top margin **16dp**, maxLines 1, center, 24sp
  `boardwalk_white` alpha 1 day / 0.88 night, RobotoRegular, ellipsize 3.
  MA `launcherItem` (`CarDisplay.kt:956-985`): VERTICAL CENTER_HORIZONTAL pad
  8dp, clickable+focusable; icon ImageView **84dp** circular via
  `clipToOutline`+oval provider (`:963-973`, no CardView); label WHITE **24sp**
  CENTER maxLines 1 `MATCH×28dp` topMargin **16dp** (`:976-984`); wrapper
  `width=0,height=156dp,FILL/1f` (`:927-935`); heading `Apps` 28sp WHITE
  padding-bottom 16dp (`:902-907`). Deltas: MA wrapper height 156dp matches
  gearhead cell height exactly; icon 84dp matches exactly; label 24sp/28dp/
  16dp-top matches exactly; MA lacks focus bg, `invisible`-until-bound, badges
  (all three), RobotoRegular/alpha, 10dp/14dp cell paddings (MA uses 8dp).
- [x] Empty/loading/lockout states differ. Gearhead: `empty_view` GONE
  `appdecor_menu_empty` centered Body1 32sp (`adu_drawer.xml`); `progress`
  48dp GONE center indeterminate; `truncated_list_card` 88dp GONE bottom +
  26sp notification text; `lockout_scrim` GONE `#e6fafafa` day / `#e6172026`
  night + 96dp `fundip_drawable` + 26sp `Safety pause. Back soon.`
  (`strings.xml:2581`). MA: single empty TextView `car_no_apps` 24sp
  `#8AB4F8` CENTER weight-1 (`CarDisplay.kt:909-917`); no progress, no
  truncated card, no lockout scrim/animation/text.
- [x] Parked-browsing (unlimited-browse exit) header absent. Gearhead
  `unlimited_browsing_exit_header` CardView GONE, 88dp row, `#ff37474f`, radius
  2dp, elev 8dp, `park_to_continue` 22dp-padded text + `Exit` allCaps button
  with 8/16/24/16 paddings/margins (`drawer_contents.xml`). MA: none.
- [x] Drawer header/shadow differs. Gearhead `drawer_shadow` match×96dp
  card_background, AppDecor variant clickable (`adu_drawer.xml`); MA heading is
  a 28sp TextView, no 96dp header, no shadow surface, no elev (MA sets no
  elevation anywhere).

### 4.6 AppDecor header / app bar (no MA equivalent — all open)

- [x] `adu_status_bar_view` entire header absent in MA: 96dp `car_mic_underlay`
  (paddingStart 96dp, elev 8dp) with 32dp app icon (gone) + 26sp condensed
  title + 320dp search card (16dp vert margins, day `#fffafafa` / night
  `#ff172026`, hint secondary `#8a000000`/`#66ffffff`, primary `#de000000`/
  `#a6ffffff`) + 96dp drawer/mic/search buttons (elev 8dp), search-exit
  `0x7f080505`, mic `ic_mic_enabled_white`. MA car surface has no title,
  no search, no mic button.
- [x] `app_bar` entire bar absent in MA: `TouchStealingFrameLayout`
  match/wrap top + foreground, 0-alpha background, 8dp side margins, 72dp
  `widget_container`, 68dp header button + tap target
  (`gearhead_oval_focus_background`) + 44dp header icon + 8dp-margined 24sp
  Boardwalk title (gone) + tab/aux strips (gone). MA: none.
- [x] Alpha-jump absent in MA: 96dp FAB (gone, end/top, margins 48/22dp, elev
  8dp, unfocusable) + full-bleed keyboard (gone, card bg, marginTop 96dp,
  fixed mode), square keys, config-change re-inflate. MA: none.

### 4.7 Assistant scrim

- [x] Assistant overlay absent. Gearhead `assistant_scrim`: match/match,
  `#151616`, focusable, alpha **0.24** (`assistant_scrim.xml`); no standalone
  class (MISSING) — sibling scrims are `ExpandingActionPanel.action_panel_scrim`
  and `MediaPlaybackView.playback_scrim` visibility bits tied to driving
  restriction `nrd`. MA: no scrim view, no assistant slot (§4.2), no `nrd`
  equivalent.

### 4.8 Touch targets (48dp audit)

- [x] Gearhead minima: rail buttons 68dp (`facet_bar_touch_target_size`),
  dock 68dp (`coolwalk_rail_dock_icon_touch_target_size`), hotseat-icon alt
  74dp, app-bar header 68dp (`gearhead_touch_target_minimum_size`),
  `FocusInterceptor` owns the target (child LP throws). MA: home/drawer cells
  68dp, icons reconciled to the 52–56dp family (68/56/8 overflow fixed),
  drawer grid tiles 156dp tall, launcher icons 84dp, call actions 44dp
  (`un_ar_icon_size`) with 44dp minWidth/minHeight — all ≥48dp.
  **Audit passes structurally; exact drawn-bounds proof needs the DHU shot
  (§9.3).** Media-card tap uses screen-space `TapBounds` inclusive contains
  from `OnGlobalLayoutListener` — no min-size enforcement on the card itself
  (matches gearhead: the card is a `TappableRegion`, not a min-target).

### 4.9 Focus states

- [x] dpad/rotary highlight system absent. Gearhead single path: `kwi` ring +
  `FocusInterceptor` (wrap/delegate/dpad `getNextFocus*`/pressed/click/focused/
  activated) + `TappableRegion`/`CoolwalkButton` (ripple selector, shape
  overlays) + list rotary/dpad (`PagedListView`, `GhListView`, `UnListView`)
  + card focus listener (`DashboardCardView`) + status-bar focus foreground +
  `krj` progress/list focus. MA: focusable/clickable flags only, no focus
  ring drawable, no `FocusInterceptor`, no dpad overrides, no rotary handling
  (scroll is injected as `SOURCE_MOUSE ACTION_SCROLL` in
  `CarDisplay.kt:262-282`; volume 24/25 consumed in
  `InputChannel.kt:148-149`; tap-select 65541→DPAD_CENTER in
  `InputChannel.kt:156-160`).

### 4.10 Densities / configurations

- [x] Only DHU default verified. DHU defaults 800×480, 160dpi, 30fps, touch
  (HANDOFF §6) = `VIDEO_800x480`. Gearhead ships `layout-car/h/sw/w`
  buckets + `values-h*/w*/sw*` (dozens), `drawable-*dpi`, `font-v29/31`,
  `layout-v29/31/33/36`, `layout-night/television/watch`.
  MA `VideoSinkChannel` maps fixed `VideoResolution` incl. portrait
  720×1280/1080×1920 etc. (default 800×480, fps 30, density 160,
  `MIN_SANE_FPS 15`); the display now rebuilds on negotiated size change
  (`CarDisplay.updateConfig` — teardown + re-show on the cached surface,
  same-size no-op). Encoder Baseline 4Mbps, I-interval 1, surface input.
  Non-DHU densities remain DHU-unverifiable (no real car); the rebuild path
  is unit-exercised only.

---

## 5. Behavior gaps gating pixels

- [x] Drawer triggers: MA open ONLY hotseat cell (`CarDisplay.kt:702`),
  close ONLY Home (`:689`) / launcher tap (`:939`) / init (`:899`). No
  scrim-tap, back, focus-loss, park close. Gearhead
  `CarDrawerLayout.dispatchGenericMotionEvent/dispatchKeyEvent→s()`,
  `MotionFilteringDrawerLayout` X-slop intercept, `PagedListView` key/rotary
  focus — MA has none of these paths.
- [x] Tap routing: MA ACTION_DOWN single-pointer inside `mediaCardBounds`
  toggles media (`CarDisplay.kt:295-298,218-223`); everything else dispatches
  verbatim (`:285-335`, pressure=1). Gearhead routes per-template with focus
  rings + driving-restriction gates (`nrd` in `MediaPlaybackView:221-234`,
  `GhListView:29`). MA has no driving-restriction gate anywhere on the car
  path.
- [x] Scroll/fling/edge: MA injects one `ACTION_SCROLL` mouse event per call
  (`CarDisplay.kt:262-282`); no fling velocity, no nested/edge handling. MA
  drawer grid does not scroll at all (static `GridLayout`).
- [x] Keys: MA `injectKey` builds bare `KeyEvent(DOWN/UP)` and dispatches to
  decor (`CarDisplay.kt:246-256`); no dpad navigation graph (gearhead
  `FocusInterceptor.getNextFocus*:121-163`, `PagedListView` 22/21/283/282).
- [x] Invalidation cadence: MA `startFrameInvalidation(fps)` ≤60
  keeps the encoder fed; vsync drain in `VideoSinkChannel` (Choreographer
  chain at display cadence, each tick draining whatever the encoder
  produced, so 30fps configs send every other vsync). Gearhead
  vsync/dirty-rect strategy NOT READ (see §9.1) — exact cadence parity
  unproven, but the encoder-paced shape matches the observed DHU behavior
  (acks at stream rate, no starvation).
- [x] Night/theme switch: **absent in MA car UI.** Phone night is read
  (`ProjectionService.isNightNow:156-165`, `SensorChannel:21-24,47,66-69,89-96`
  HU NIGHT_MODE override, `NavGuidanceMonitor.isNightNow:160,181-192`,
  `CarMapsMirror.setDark:88-91`) but nothing calls `CarMapsMirror.setDark`
  from a night source (attach hardcodes `false` at `CarMapsMirror.kt:110`),
  and `CarDisplay.kt` has zero night/dark/UiMode references (hardcoded
  `#101418/#1B2430/#161C24`). Gearhead overlays Dark vs DayNight per surface
  (`RailStatusBarFragment:234-245`, night colors §2.3).
- [x] Rotation/config: **absent in MA car UI.** Phone `MainActivity` handles
  `orientation|screenLayout|screenSize|...|uiMode`
  (`AndroidManifest.xml:65`); car path (`VideoSinkChannel`, `CarDisplay`,
  `ProjectionService`) has no `orientation/configuration/
  onConfigurationChanged`, fixed size at `show()`.
- [x] Display lifecycle: MA `onStop` nulls views/listeners and stops
  invalidation (`CarDisplay.kt:499-512,501`); teardown order session-scoped
  reverse release (`ProjectionService.kt:255-293`); reconnect
  full-jitter exponential 500ms→30s (`ReconnectBackoff.kt:28-46`).
  Gearhead multi-display/private lifecycle NOT READ (see §9.1).

---

## 6. System requirements gating car UI (requirements, not implementation)

MA manifest (`auto/src/main/AndroidManifest.xml`): **13** `<uses-permission>`
(`FOREGROUND_SERVICE:6`, `CONNECTED_DEVICE:7`, `POST_NOTIFICATIONS:8`,
`WAKE_LOCK:9`, `INTERNET:12`, `CHANGE_WIFI_STATE:17`, `ACCESS_WIFI_STATE:21`,
`CHANGE_NETWORK_STATE:22`, `ACCESS_FINE_LOCATION:23`,
`NEARBY_WIFI_DEVICES:24`, `RECEIVE_BOOT_COMPLETED:27`, `RECORD_AUDIO:31`,
`MEDIA_PROJECTION:36`); **1** activity (`.MainActivity` exported + MAIN/LAUNCHER
+ USB_ATTACHED + filter `:63-82`); **3** services (`ProjectionService`
`connectedDevice` `:84-87`, `MusicCaptureService` `mediaProjection` `:93-96`
split for API34+, `MessageMirrorService` exported + BIND_NLS `:128-131`);
**2** receivers (`UsbReceiver` `:101-108`, `BootReceiver` `:111-117`);
**0** providers; queries maps/music/browser/DIAL (`:42-52`).

Gearhead manifest (`manifest-out/resources/AndroidManifest.xml`): **81**
`<uses-permission>` lines 22-102 (FINDINGS says 78 — file has 81 incl.
`START_PROJECTED_ACTIVITY:88`, `DYNAMIC_RECEIVER_NOT_EXPORTED:89`);
**56a/87s/42r/11p** per FINDINGS §6 (11 providers verified: DeveloperSettings,
SettingsSearch, ProjectionStateProvider `:404-408` exported, Initialization,
GhMicrophone, SharedPreferences, Troubleshooter, CoolwalkColors, Bugreport,
Proxy, ProcessLifecycleOwner).

- [x] Role pin: `android.app.role.SYSTEM_AUTOMOTIVE_PROJECTION`
  (exclusive/static/systemOnly/visible=false,
  `defaultHolders="config_systemAutomotiveProjection"`, `roles.xml:624` per
  FINDINGS §1). MA constants `MaosRole.kt:19-28` (`PROJECTION_ROLE:22`,
  `PROJECTION_ROLE_CONFIG:25`, package `com.vayunmathur.auto:28`,
  capabilities `TRUSTED_DISPLAY/INPUT_INJECTION/PROJECTION_DATA:34-43`,
  `capabilities(false)=empty:49-50`); reader `MaosRoleStatus.kt:1-22`
  (`isRoleHeld`, fail-false). RRO must override
  `config_systemAutomotiveProjection → com.vayunmathur.auto` exactly like the
  five roles in `MaosFrameworkResRRO` (FINDINGS §1); second patch needed for MA
  Cast `COMPANION_DEVICE_APP_STREAMING` (no `defaultHolders`, RRO cannot add a
  framework name — HANDOFF §8). Without: `DisplayRoutePolicy.routeFor(false)=
  PRIVATE_VIRTUAL` (`DisplayRoutePolicy.kt:28-35`), `VideoSinkChannel:321-325`
  stays private, `CarDisplay:51-56,117-121` renders MA-only `Presentation` —
  real HU shows placeholder/black for hosted surfaces (DHU loopback unaffected).
- [x] Trusted display: `ADD_TRUSTED_DISPLAY:94` (+ `ADD_ALWAYS_UNLOCKED_DISPLAY:
  90`). MA requests `VIRTUAL_DISPLAY_FLAG_TRUSTED` reflectively only on the
  trusted route (`CarDisplay.kt:1091-1101,115-129,63-69`), default off. Without:
  other apps' activities cannot launch onto the car display → black surface
  where HU expects hosted template.
- [x] Input injection: `CREATE_VIRTUAL_DEVICE:91` (`MaosRole.kt:38-39`
  INPUT_INJECTION). MA `InputChannel:115-119` drops without `inputAllowed`,
  `:120-125` drops with no display, `:80-87` binds only on grant,
  `:95-103` pre-bind drops (`DroppedNoFocus`). Without: video flows, **no
  touch** (session-card `Input dropped` counter rises).
- [x] Projection/system-UI/decor: `TOGGLE_AUTOMOTIVE_PROJECTION:92`,
  `CAPTURE_SECURE_VIDEO_OUTPUT:98`, `CarSystemUiControllerService:930-933`
  (exported), `AppDecorService:934-937` (exported), `GhostActivity:300-305`,
  `CarVirtualDeviceActivity:2006-2015`, `ProjectionRootActivity:1006-1011`,
  `ProjectedHomeActivity/Service:1029-1041`, `Template*Service + GhAppLauncher
  + Trampoline* (CATEGORY_PROJECTION + GHOST_ACTIVITY):504-548,761-866`. MA has
  only `CarDisplay`+`VideoEncoder` (`ProjectionService:512-527,301-358`).
  Without: secure content black, HU compositor rejects embedded windows.
- [x] Wireless/CDM trigger: `ASSOCIATE_COMPANION_DEVICES:95`,
  `REQUEST_COMPANION_PROFILE_AUTOMOTIVE_PROJECTION:93`,
  `CarProcessCompanionDeviceService:2016-2027` (CDM, `:car`),
  `WirelessSetupSharedService:1308-1313` + CarService `:1314-1319`,
  `WirelessStartupActivity:1301-1307`, `DeveloperHeadUnitNetworkService:925-929`,
  `WifiBluetoothReceiver:2467-2481`,
  `WirelessStartupReceiver:2507-2515`, `DeepLinkResolver:2491-2506`,
  `CarStartupServiceImpl:2051-2062` (BT_START + START_USB_PROJECTION),
  `CarChimeraService:2106-2115` (`microphone|connectedDevice|location`,
  `car.service.START`), `CarSetupServiceImpl:2045-2050`. MA has only
  `WifiDirectConnector/UsbConnector`, no CDM service. Without: wireless
  CDM-triggered bring-up never fires → **no wireless projection** on real cars.
- [x] USB auto-launch/reset: `MANAGE_USB:74`, `CarUsbReceiver:2129-2141`,
  `CarUsbReceiverTPlus:2142-2150`, `FirstActivityImpl:2063-2086`
  (USB_ATTACHED + FORCE_START + START_DUPLEX),
  `ConnectionResetReceiver:2264-2274` (RESET_USB_PORT/GADGET/ROLES/FUNCTION),
  `ConnectivityEventBroadcastReceiver:1050-1096`. MA has `UsbReceiver` +
  accessory filter only. Without: no wired auto-launch/role-switch on real
  cars (DHU loopback unaffected).
- [x] Calls: `InCallService` entries `NonCarInCallServiceImpl:1251-1263`
  (disabled) + `CarProjectionInCallServiceImpl:1264-1280` (CAR_MODE_UI),
  `CONTROL_INCALL_EXPERIENCE:70`, `CALL_PRIVILEGED:66`, `MODIFY_PHONE_STATE:97`,
  runtime `CALL_PHONE:47 READ_PHONE_STATE:48 READ_CALL_LOG:49 READ_CONTACTS:50`.
  MA: no `InCallService`. Without: projected call UI dead (UnCallView §2.4 has
  no MA counterpart at all).
- [x] Messaging/sensitive data: `RECEIVE_SENSITIVE_NOTIFICATIONS:96`,
  `GET_INTENT_SENDER_INTENT:99`, `RECEIVE_SMS:54 SEND_SMS:55 READ_CALENDAR:57`,
  app-ops `receive_sensitive_notifications/read_restricted_messages`
  (FINDINGS §1); gearhead listener
  `SharedNotificationListenerManager$ListenerService:992-1005` vs MA single
  `MessageMirrorService`. Without role app-ops: messaging/call templates empty,
  fail-closed silence.
- [x] Audio routing + FGS types: `MODIFY_AUDIO_ROUTING:75`,
  `TETHER_PRIVILEGED:82`, `BLUETOOTH_PRIVILEGED:65`, `POWER_SAVER:81`,
  `MANAGE_USERS:83`, `READ_PRIVILEGED_PHONE_STATE:77`,
  `CHANGE_COMPONENT_ENABLED_STATE:67`, `START_ACTIVITIES_FROM_BACKGROUND:79`,
  `ENTER_CAR_MODE_PRIORITIZED:72`, `LOCAL_MAC_ADDRESS:73`,
  `COMPANION_APPROVE_WIFI_CONNECTIONS:68`, `REQUEST_COMPANION_SELF_MANAGED:78`.
  MA `ProjectionService:84-87` is `connectedDevice`-only (vs triple
  `microphone|connectedDevice|location` on `CarChimeraService:2111` and dual on
  `WirelessSetup:1313`). Without: background mic/location FGS killed, audio
  misroutes to phone speaker, sinks/mic (ch3/4/5/6,
  `ProjectionService:596-627`) starve.
- [x] Privapp allowlist (exact entries for `com.vayunmathur.auto`, minimum):
  `MANAGE_USB, MODIFY_AUDIO_ROUTING, TETHER_PRIVILEGED, CALL_PRIVILEGED,
  POWER_SAVER, READ_PRIVILEGED_PHONE_STATE, CHANGE_COMPONENT_ENABLED_STATE,
  MANAGE_USERS, CAPTURE_SECURE_VIDEO_OUTPUT, BLUETOOTH_PRIVILEGED,
  CONTROL_INCALL_EXPERIENCE, MODIFY_PHONE_STATE` (FINDINGS §1 + `MaosRole.kt:
  8-11`). Both role and allowlist halves are boot-fatal if wrong — land one at
  a time (HANDOFF §8).

---

## 7. Phone-UI gating of car pixels (out-of-scope except this)

Phone `AutoSessionState`/`AutoViewModel` **never gate** car pixels — they only
mirror: `onFocusChanged:353-361`, `resetFocus:364-368`, `onVideoEvent:396-434`
(counts/surface), `onInputEvent:458-468` (counts), `AutoViewModel.kt:15-17`
KDoc never-writes + `stateIn` mirrors (`:33-34,45-46,85-87`).
Streaming/input decisions read `session.focus` directly
(`ProjectionService:555,601`, `VideoSinkChannel:400`, `InputChannel:115`).
Bring-up is sequential HU-wire-order one-in-flight (`pendingChannels`,
`ProjectionService:304-383,503-527`); per-grant triggers video→messaging→
input→sensors→guidance→nav→audio→mic (`:329-381`); focus mirror re-binds input
(`:222-231`); TTS/maps/music start at session-start (`:237-253`,
`MediaPlaybackMonitor.start:48-64` outlives session at `:71-74`).

---

## 8. GAL channels — pixel-affecting notes only

- Video (paints all HU pixels): `SETUP media_type=3` (`VideoSinkChannel:202-208,
  524`); accept-index pick (`:232-250`, `firstOrNull?:0 :234`); focus request
  `PROJECTED` (`:252-261`); indication → arbitration + keyframe on PROJECTED
  (`:264-281`, absent-mode ignore `:271-274`); start resolution/density/fps →
  encoder+display (`:301-358`, `dimensions :303`, `negotiateFrameRate :307`,
  `density?:160 :309`, `VideoEncoder :316`, `CarDisplay trusted :326`,
  `show :335`, `invalidate :336`, `START sessionId=0 :349-357`); stream gate
  `shouldStreamVideo` (`:400`, `FocusArbitration:90 PROJECTED`); vsync drain
  (`:368-373,438-445`); resolution table/defaults (`:507-518,525-526,533`);
  encoder H264 Baseline surface-input 4Mbps I-1 (`VideoEncoder:33-56,104-105`),
  `drain→onFrame :63-84`, keyframe `:87-91`. Gaps: DHU-1fps forced to 30
  (`VideoSinkChannel:471-490`, sibling-30 `:479-484`); transient focus not
  distinguished (`FocusArbitration:52-53`); unknown-enum absent ignored
  (`FocusArbitration:108-113` + `VideoSinkChannel:271-274`); input derived
  from video (`FocusArbitration:63-65,67-69,112-126`).
- Input ch8 (HU→display pixels): HU size from `touchscreenList` else 800×480
  (`InputChannel:61-63`); binding echo on grant (`:80-87`); gating + scaling
  (`:90-142`, `InputCodec.scalePointer:159-174`, defaults 800/480 `:86-87`);
  vol consumed (`:148-149`), 65541→DPAD_CENTER (`:156-160`), scroll
  (`:164-166`); inject fan-out `VideoSinkChannel:186-199`.
- ch10 NavStatus (HU-native render, not ch2): inactive stub on grant
  (`NavStatusChannel:32-38`, bytes `NavStatusCodec:31-35`), live
  `encodeStatus :54-69` (`NavStatusCodec:45-56`, sender-defined `0x8001`),
  inbound ignored (`:41-47`, stub note `:17-21`); live `postStatus` unwired —
  monitor seam only (`NavGuidanceMonitor:64-69,143-171`, banner
  `CarDisplay:876-885`).
- Media metadata (ch2 card text): `NowPlayingInfo` title/artist/playing/pos/dur
  (`MediaNowPlaying:12-23`), visible iff playing or titled (`:29`); resolved
  via MA music `PlaybackService :127-128` or generic browser `:120-122`
  (`MediaPlaybackMonitor:48-64,93-105`); **no artwork/queue/prev-next**
  (`CarDisplay:554-560`, `MediaGalGapTest:10-13` GAL 11/12 opaque).
- Maps (ch2 nav pixels): mirror path only (§4.4); ch7 stub subs
  LOCATION/NIGHT/SPEED/DRIVING_STATUS (`SensorCodec` + `SensorChannel`
  `:66-69`, live seam `onValues`); ch7 values via `MapsGuidance:130-156`,
  ch10 args `:159-173`; `GuidanceChannel` ch3 claims-then-idles, no pixels
  (`GuidanceChannel:46-68,81-99`).
- Non-pixel internals (one line each): audio sinks/mic/TTS/music-capture,
  sensors batch/error, messaging ch14 threads/message/dismiss/action +
  reply/mark-read/voice-reply — see FINDINGS/HANDOFF, no HU pixel effect
  except the card/banner text noted above.

---

## 9. Verification appendix (zero-estimate completeness proof)

### 9.1 Layout coverage — OPEN

Every HU `res/layout/*.xml` must appear once as covered or explicitly
out-of-scope with reason; counts reconciled against 751. **Not complete in
this revision:** 12 HU layouts read fully (§2.1–2.2); ~739 remaining
unenumerated (spot-checked families: `abc_*` appcompat, phone setup). The
following HU-adjacent items are explicitly NOT READ and must be closed before
any "parity complete" claim:

- [x] `rail_statusbar*.xml` content layouts — `rail_statusbar.xml` and
  `hero_vertical_rail_statusbar.xml` read fully (vertical packed chain,
  icon row, badge/DND slots, autosize clock, card metrics); remaining
  variants (`vertical_rail_statusbar`, `rail_statusbar_rhd`) share the slot
  geometry mirrored. `sys_ui_coolwalk_rail_widget*.xml` still NOT READ
  (widget backend stubbed gone).
- [ ] `CarAppLayout` / nav-template / media-browse / telecom layouts backing
  §4.4/§4.6 gaps (filenames NOT FOUND under guessed names — enumerate via
  `R.layout` references in `CarAppLayout.java`, `MediaPlaybackView.java`,
  `UnCallView.java` instead of guessing).
- [ ] `badge_background` top margin second attribute
  (`app_launcher_item.xml` — one of the two margin values not captured).
- [ ] `@fraction/dashboard_media_progress_radius_fraction`,
  `@color/unlimited_browsing_exit_text`, `@color/gearhead_sdk_body1`,
  `@string/content_browse_park_to_continue`, `@string/appdecor_search_box_hint`,
  `@string/appdecor_menu_empty` exact values.
- [ ] `CarDrawerLayout` base/`DrawerLayout` back/scrim behavior, gearhead
  vsync/dirty-rect strategy, multi-display lifecycle, `nrd` truth table
  (`defpackage`, needs `disasm.py` — jadx must not be trusted there per
  FINDINGS §3-4 traps: `roc.aQ` loop cache, `abpe:501` inversion, `wub.o`
  +1, descriptor `\b\f\r` escapes).

### 9.2 R.java ID cross-check — OPEN

- [ ] Every HU-referenced `R.layout/R.id/R.dimen/R.color/R.drawable`
  traced to a MA equivalent (`CarDisplay.kt:NNN`) or an item in §4–§6.
  Spot-covered: rail/drawer/media/launcher IDs in §2.2; full trace not done.

### 9.3 DHU screenshot comparison procedure (documented, not run)

- [ ] Matrix: DHU 2.0 (`desktop-head-unit.exe`), configs 800×480/160dpi/30fps
  touch (default `config/default.ini` = `VIDEO_800x480`) × {day, night} ×
  {idle, nav-active, media-playing, drawer-open, call-incoming (gearhead
  only)}; relay via `adb forward tcp:5277` + `analysis/maauto/dhu_relay.py`
  per HANDOFF §6; capture with `Start-Process` (DHU exits if stdout piped).
- [ ] Launch (mandatory): the config file MUST be passed with `-c` —
  `Start-Process ... -ArgumentList "-c", "config\default.ini"`. A bare
  launch prints `[W]: No configuration specified - using default values`
  and runs on compiled defaults, which silently disables touch input and
  the `[sensors]` section (location/night/driving_status): ch8 verification
  is invalid on such a run, and ch7 `0xff` rejections cannot be attributed
  to unimplemented sensors. A positional ini path is ignored — only `-c`
  (or `--config=`) loads it. Use `-i touch|rotary|hybrid` to force the
  input mode under test independently of the ini.
- [ ] Naming: `dhu_gearhead_<surface>_<config>.png` vs
  `dhu_ma_<surface>_<config>.png` (`surface ∈ {rail, statusbar, media, nav,
  drawer, launcher, scrim, empty, lockout}`).
- [ ] Pixel-diff tool + acceptance bar: **documented as required, not
  implemented** (no threshold asserted — asserting one would be an estimate).
- [ ] Render path itself unconfirmed (HANDOFF §2: frames acked, display on DHU
  surface not yet confirmed) — screenshot leg is blocked on that first.

### 9.4 Read-only smoke checks (recorded, not run here)

- [ ] `./gradlew :auto:assembleDebug` — must pass (doc adds no code, so this
  is a no-op guard for the tree).
- [ ] `./gradlew lint` — must pass (`Toast` ban, app-module icon rules per
  repo conventions).
- [ ] Any failure filed as follow-up, never fixed in a parity-doc task.

Explicitly out of verification: phone screenshots, GAL conformance tests,
audio quality tests.

---

## 11. Control-plane functional parity

Truth: `auto/docs/FINDINGS.md` §2 control table + §3 crypto. MA session:
`auto/protocol/src/main/java/com/vayunmathur/auto/protocol/GalControlSession.kt`,
wire IDs `GalMessage.kt`, handshake `VersionNegotiation.kt`, TLS `TlsCodec.kt`,
credential `GalCredential.kt`, connection `GalConnection.kt`, protos
`auto/protocol/src/main/proto/gal/control.proto` + `services.proto`.

### 11.1 Control message IDs

- SUPPORTED — 1 `VersionRequest` recv (raw uint16 major/minor, HU asks first):
  dispatch `GalControlSession.kt:134` → `onVersionRequest :248-275`, raw
  `ByteBuffer.short &0xFFFF` `VersionNegotiation.kt:43-52`, KDoc HU-asks
  `VersionNegotiation.kt:22-24`.
- SUPPORTED — 2 `VersionResponse` send (major/minor/status, -1 as `0xFFFF`):
  `GalControlSession.kt:257-261` (`encrypted=false`), shorts
  `VersionNegotiation.kt:61-66`, `-1→0xFFFF` comment `:57-59`, sign-extended
  parse `:69-80`.
- SUPPORTED — 3 SSL handshake both dirs (cleartext, `rvg` wrap/unwrap):
  `GalControlSession.kt:135,277-283,445-492` (`NEED_UNWRAP/NEED_WRAP/FINISHED`),
  out `encrypted=false :476-480`; per-frame `TlsCodec.kt:21-38,47-66`;
  `FrameReader.kt:72` decrypt-iff-ENCRYPTED.
- SUPPORTED — 4 `AuthComplete` recv (`SUCCESS/CERT_EXPIRED/CERT_NOT_YET_VALID`):
  `GalControlSession.kt:136,285-289` (non-0 → fail, no per-code branch),
  `control.proto:183-185` status field, `:23-58` incl. `-23/-24` values.
- SUPPORTED — 5 `ServiceDiscoveryRequest` send (`xoa`, ≥1.6 only, field-5-only,
  `0x0B` CONTROL-clear): floor `GalControlSession.kt:290-298`
  (`MINIMUM_FOR_DISCOVERY`), field 5 `composeDiscoveryLabel :310-313,508-509`
  (`MANUFACTURER + " " + MODEL`), flags `:306-309`, shape
  `control.proto:110-117,98-109`.
- SUPPORTED — 6 `ServiceDiscoveryResponse` recv (`xob`, HU advertises/phone
  consumes): `GalControlSession.kt:137,319-323` (`state=ACTIVE`),
  `control.proto:120-137` (repeated Service + HU fields 2-11,13-15,17).
- SUPPORTED — 7 `ChannelOpenRequest` send (`{priority,service_id}` order,
  target-channel `0x0F`): `GalControlSession.kt:195-208` via
  `control.proto:169-172` (`sint32 priority=1; int32 service_id=2`,
  priority-first comment), `-128` control self-open, `isControl=true,
  channelId=service.id, encrypted=true → 0x0F :185-193`; enqueue
  target-channel `GalConnection.kt:238-250`.
- SUPPORTED — 8 `ChannelOpenResponse` recv (any channel, `xir/status`):
  `GalControlSession.kt:138,355-365` (non-SUCCESS → `drainPendedToRefused`,
  else pending→open), `control.proto:178-180`; routed on ANY channel
  `GalConnection.kt:170-172` (`izd.b via izl.g(b)`); ch0-framed `0x7` refused
  `-5` (`GalControlSession.kt:191-193`, `GalConnection.kt:163-169`).
- SUPPORTED — 11/12 ping: request parsed, response echoes timestamp encrypted
  (`GalControlSession.kt:139,409-418`, `control.proto:188-192,195-198`);
  inbound 12 ignored (`GalControlSession.kt:148-150`).
- SUPPORTED — 14 nav-focus / 19 audio-focus recv (observed, never answered):
  `GalControlSession.kt:157,400-403` → `focus.onNavigationFocus`
  (`FocusArbitration.kt:137-143`), `control.proto:220-222,89-92`;
  `GalControlSession.kt:156,391-394` → `focus.onAudioFocus`
  (`FocusArbitration.kt:129-134`), `control.proto:214-217,78-87`.
- SUPPORTED both dirs — 15/16 byebye (`USER_SELECTION` + 7 more reasons):
  recv 15 → CLOSED + encrypted empty 16 (`:140,420-429`); recv 16 → CLOSED
  (`:141-144`); send `disconnect(USER_SELECTION) :211-216`;
  `control.proto:201-203,206,60-69`.
- SUPPORTED — 18 `AudioFocusRequest` send (fire-and-forget):
  `GalControlSession.kt:240-244`, `control.proto:209-211,71-76` (4 types).
- SUPPORTED — 26 `ServiceDiscoveryUpdate` recv (hot-add): same-id replace,
  serviceless ignore (`GalControlSession.kt:155,333-339`, comment `:151-154`
  wire-order loop picks up without reconnect), `control.proto:163-165`.
- [x] PARTIAL — 24 `CallAvailabilityStatus` recv: routed but never parsed
  (`GalControlSession.kt:148-150` groups with `PING_RESPONSE → emptyList`,
  comment `:145-147`: higher layers observe); `control.proto:225-227` defines
  `optional bool call_available=1` but no `parseFrom` call in session.
- [x] PARTIAL — 255 `MessageError`: recv SUPPORTED
  (`GalControlSession.kt:171,376-379` → `drainPendedToRefused`, rationale
  `:159-170` bare-`0xff` = refused open; trace `GalConnection.kt:181-186`);
  send MISSING — no `send(255)` path in
  `GalControlSession.kt:133-174` or `GalConnection.kt:211-266`.
- [x] MISSING — 65535 `FramingError` (defined only): constant
  `GalMessage.kt:51`, unsigned-safe codec `MessageCodec.kt:23-25,47`, but no
  `onMessage` branch (`GalControlSession.kt:133-174`), no `rtr`-style `0xFFFF`
  reply/teardown (`FrameReader.kt:57-89` waits/compacts only).

### 11.2 Service table `xnz` fields 1–14 + `rro` 1–22

MA `Service` `control.proto:141-160`, descriptors `services.proto`, IDs
`GalMessage.kt:158-180`.

- SUPPORTED descriptor — f1 id==channel (`control.proto:142`,
  `GalMessage.kt:159` CONTROL); f2 SensorSource (`:143`,
  `services.proto:101-106,97-99`, ch7 IDs `GalMessage.kt:89-93`); f3 MediaSink
  (`:144`, `services.proto:65-75,44-62,20-40`, codecs 1-7 + resolutions 1-9
  matching FINDINGS §2, IDs `GalMessage.kt:160-163`); f4 InputSource (`:145`,
  `services.proto:89-95`, IDs `GalMessage.kt:82-86`); f5 MediaSource mic
  (`:146`, `services.proto:78-81`, semantic `GalMessage.kt:76-79`); f6
  Bluetooth (`:147`, `services.proto:108-111`); f8 NavigationStatus (`:149`,
  `services.proto:113-117` — channel `0x8001` is MA-defined stub,
  `GalMessage.kt:115-118,102-114`); f12 VendorExtension (`:153`,
  `services.proto:119-123`); f14 WifiProjection (`:155`,
  `services.proto:125-127`).
- [x] PARTIAL (opaque/slot) — f7 Radio (`control.proto:148` `bytes radio=7`,
  ID `GalMessage.kt:173`); f9 MediaPlaybackStatus (`:150` bytes + explicit
  NOT_SUPPORTED `GalMessage.kt:191-199`, gap `services.proto:4-8`, ID `:169`);
  f10 PhoneStatus (`:151` bytes, ID `:171`); f11 MediaBrowser (`:152` bytes,
  same NOT_SUPPORTED); f13 Notification (`:154` bytes — real `xjm` never
  recovered, MA channel `0x8001-0x8004` sender-defined `GalMessage.kt:120-142`,
  `notification.proto:3-13`); `rro` 18–22 (IDs `GalMessage.kt:176-180`, payloads
  only generic `control.proto:156-159` bytes f15–18 for 5 services, no typed
  decode).

### 11.3 Frame / fragmentation / TLS / KDF rules

- SUPPORTED — frame `[ch/fl/len16/total32-iff-FIRST&&!LAST]`
  (`FrameHeader.kt:25-30`), flags `0x01/0x02/0x04/0x08` (`:11-20`), BE reads
  (`:84-102`), `[u16 type][protobuf]` unsigned (`MessageCodec.kt:23-25,31-51`).
- SUPPORTED — fragment 16128 (`FrameHeader.kt:73-76` `rto.a()`),
  `Fragmenter.kt:44-46,55-58` header math, 256B TLS headroom below 16384
  staging (`:23-25`, `FrameWriter.kt:33-34`).
- SUPPORTED — control never fragments (`Fragmenter.kt:30-33,48-51`
  `require(!isControl)`, `FrameWriter.kt:28-34`).
- SUPPORTED — CONTROL-bit scoping (only `0x7` sets it):
  `GalControlSession.kt:40-47,185-189` (`0x7` → `0x0F`),
  `:306-309` (`0x5` → `0x0B`); service sends never CONTROL
  (`GalConnection.kt:221-231,257-266`, classifier must not drive bit
  `:22-33`, per-message `isControl :243-247`).
- SUPPORTED — per-frame TLS order: decrypt per-frame before reassembly
  (length=ciphertext, `totalLength` untrusted hint,
  `FrameReader.kt:20-22,72,78-80`); encrypt per-fragment after split
  (`FrameWriter.kt:8-11,37,43-48`).
- SUPPORTED — TLS posture phone-server + client-auth, GAL-only anchor, 1.2:
  `GalCredential.kt:100-103` (`useClientMode=false, needClientAuth=true`,
  matching `rth:180-209`), `:84-86` `TLSv1.2`, `:42,75-82` keystore `GAL` sole
  anchor (`:27-32` KDoc). Cipher not pinned (FINDINGS verify
  `ECDHE-RSA-AES256-GCM-SHA384` not enforced in code).
- SUPPORTED — version 1.7 + 1.6 discovery floor:
  `VersionNegotiation.kt:34,37`, enforced `GalControlSession.kt:290-293`,
  `requested>supported→null` (`VersionNegotiation.kt:89-90`).
- SUPPORTED (accounted) — `wub.o` off-by-one: `GalMessage.kt:9-14` documents
  `jdk/jdi id+1` vs `k()` raw; raw wire values throughout `:55-93`,
  agreeing with aasdk/openauto (`:12-14`).
- MISSING by design — `roc.aQ` KDF + crosswise `rvd/rvg` + phenotype rotation:
  no KDF/AES-CBC/PKCS5/strip/PKCS8 path in `GalCredential.kt:53-87,125-133`
  (expects plaintext PEMs `assets/gal/`, `:36-39,90-94`, expiry 2026-12-09
  `:21-23`, surfacing only `:111-123`); no remote `adbo.gu a-f` path.
  Consequence: credential cannot be renewed in code — must re-extract per
  HANDOFF §7 runbook before expiry.
- Channel-open order: no auto-sort; caller order = `pendingChannels` order
  (`GalControlSession.kt:113-115,195-196`); wait SUCCESS before media
  (`:341-347`); refusal recorded non-fatal (`:349-353,357-359`); bare `0xff`
  drains head (`:368-379,381-385`); ch0-first + round-robin drain
  (`GalConnection.kt:50-55,268-285`).

---

## 12. Channel + transport functional parity

Bring-up: sequential HU-wire-order one-in-flight (`pendingChannels`),
per-grant video→msg→input→sensors→guidance→nav→audio→mic
(`ProjectionService.kt:329-381`), owners in `openNext` (`:512-627`), focus
mirror + ch8 rebind (`:222-231`), session-scoped TTS/maps/music (`:237-253`),
reverse-release teardown (`:257-293`), clean/failed backoff (`:99-122`,
`ReconnectBackoff.kt:28-46` 500ms→30s full-jitter).

### 12.1 Media sink `jdk` (shared ch2/3/4/5)

- SUPPORTED — `0x0000` DATA_WITH_TIMESTAMP send video
  (`VideoSinkChannel.kt:425`) + audio (`AudioSinkChannel.kt:227`), recv mic
  (`AudioCodec.kt:232-239`, `TIMESTAMP_BYTES=8 :121`); `0x8000/0x8001` setup/
  start (`AudioCodec.kt:128-140`, guidance `:48-49,81-90` idle-by-default,
  `AudioSinkChannel.kt:94-95,193-194`, video `:202-208,349-357 sessionId=0`);
  `0x8003` config recv (`AudioCodec.kt:211-215`,
  `AudioSinkChannel.kt:179-204`, guidance `:61-68`, video `:225,232-262`
  `firstOrNull?:0 :234`); `0x8004` ack (sink decode `AudioCodec.kt:216-219` +
  `AckTracker.kt:30-49,MOD=256:57`, video `:283-299`, audio `:116-119`; mic
  send `:151-155` + `MicSourceChannel.kt:136-140`).
- [x] PARTIAL — `0x0001` bare data: video/audio send `0x0000` only, never
  `0x0001`; mic recv SUPPORTED (`AudioCodec.kt:240`, `-1` ts, empty→null).
- [x] PARTIAL — `0x8002` stop: audio + guidance send (`AudioCodec.kt:143-144`,
  `AudioSinkChannel.kt:166-169`, `GuidanceChannel.kt:93-99`); video releases
  codec/display but never sends STOP (`VideoSinkChannel.kt:452-463`) — ch2
  MISSING.
- [x] PARTIAL (observed only, correctly unanswered) — `0x800B` sync
  (`GalMessage.Audio.SYNC:150`, `AudioCodec.kt:220→InboundAudio.Sync:56`,
  `AudioSinkChannel.kt:120`, `GuidanceChannel.kt:70`); `0x8013 xkq`
  (`AudioCodec.kt:59-62→Observed:62`, `AudioSinkChannel.kt:121`, video `:228`
  log, never parsed).
- PCM-only by design (`AudioCodec.kt:168-179,383-400`; non-PCM → UNSUPPORTED
  parked `AudioSinkChannel.kt:84-89`). Gearhead `xkj` AAC/VP9/AV1/H265 =
  MISSING, never negotiated.

### 12.2 Video `jem` / mic `jdi` / input `jar` / sensor `rvb`

- SUPPORTED — `0x8007` focus request PROJECTED + `0x8008` indication →
  arbitration + keyframe (`VideoSinkChannel.kt:226,253-281`,
  `GalControlSession.kt:229-232`, `FocusArbitration.kt:112-126`; absent-mode
  ignore `:271-274` + `:108-113`; transient folds to NATIVE `:52-53,114-120`;
  `NO_INPUT` streams without input `:63-69,89-90`).
- [x] MISSING — `0x800A` UpdateUiConfigRequest: constant
  (`GalMessage.kt:73`), never sent/handled (`VideoSinkChannel.kt:219-230`).
- SUPPORTED — mic `0x0000` recv + `0x8004` send + `0x8006` request
  (`AudioCodec.kt:232-240,151-155`, `MicSourceChannel.kt:63-86,136-140`,
  session 0 `:118`); acks-from-first-chunk, retention gated by `MicPermission`
  (`MicSourceChannel.kt:11-17,50-55,79,94-107,378-381`).
- SUPPORTED — input `0x8001` report (`InputCodec.kt:110-132`,
  `InputChannel.kt:110-167`: scroll 65536 zero-drop `:70`, tap-select
  65541→DPAD_CENTER `:76,79,156-160`, vol 24/25 consumed `:148-149`, scaling
  `scalePointer :159-174`, DHU 800×480 `:86-87,61-63`); `0x8002` binding echo
  on grant + focus-flap rebind (`InputCodec.kt:90-94`,
  `InputChannel.kt:80-87`, `ProjectionService.kt:229-231`); `0x8003` response
  logged, never gates (`InputCodec.kt:97-98`, `InputChannel.kt:104-109`).
- [x] MISSING — input `0x8004` feedback (`xjs` DTO in `input.proto:125-130`
  defined, no encoder/send in `InputCodec.kt`/`InputChannel.kt`).
- Sensor: `0x8001` request SUPPORTED (`SensorCodec.kt:144-148`,
  `SensorChannel.kt:70-75`, fields 1-2 exact, rest sender-defined
  `sensors.proto:14-19`); `-1` unsubscribe SUPPORTED
  (`GalMessage.Sensor.UNSUBSCRIBE_PERIOD=-1:96`, `SensorCodec.kt:151-152`,
  `SensorChannel.kt:113-118`); phone-night seed SUPPORTED
  (`SensorChannel.kt:21-24,66-69`, `ProjectionService.kt:156-165`).
- [x] PARTIAL — `0x8002/0x8003/0x8004` decoded (`SensorCodec.kt:171-186`,
  `sensors.proto:74-94,122-125` MA-defined layouts per caveat `:14-19`) but
  only logged/folded for 4 types (`SensorChannel.kt:85-102`, alias scoped by
  ch-id `:36-39`); no 2s-timeout enforcement (documented
  `GalMessage.kt:88`, `sensors.proto:4-7`, no timer in `SensorChannel.kt`).
- Sensor types (26 `xny`, `sensors.proto:35-62` dumper order): SUPPORTED
  (subscribed + folded) — LOCATION(1), SPEED(3), NIGHT_MODE(10),
  DRIVING_STATUS_DATA(13) (`SensorCodec.kt:133-138,191-241`,
  `MapsGuidance.kt:130-156`); PARTIAL — COMPASS(2) (proto `:103-105`
  decodable, dropped in `withEvents else :49-81`); MISSING (no payload,
  dropped) — RPM(4), ODOMETER(5), FUEL(6), PARKING_BRAKE(7), GEAR(8),
  OBDII(9), ENVIRONMENT(11), HVAC(12), DEAD_RECKONING(14), PASSENGER(15),
  DOOR(16), LIGHT(17), TIRE_PRESSURE(18), ACCELEROMETER(19), GYROSCOPE(20),
  GPS_SATELLITE(21), TOLL_CARD(22), VEHICLE_ENERGY(23), TRAILER(24),
  RAW_VEHICLE_ENERGY(25), RAW_EV_TRIP(26).

### 12.3 Other services

- SUPPORTED — svc 1 CONTROL (IDs above, svc-1 opened first
  `ProjectionService.kt:495-501`); svc 2 VIDEO (§12.2); svc 4/5 SYSTEM/MEDIA
  audio (`AudioSinkChannel.kt:82-200`, TTS→ch4 `CarTts.kt:58-63,111-121`,
  music→ch5 `MusicCapture.kt:60-86,125-150`, focus `AudioCodec.kt:256-284` +
  `FocusArbitration.kt:96-105`); svc 6 MIC; svc 8 INPUT (minus feedback).
- PARTIAL — svc 3 GUIDANCE (claim-then-idle stub,
  `GuidanceChannel.kt:46-99`, `startStream/stopStream` unwired to TTS); svc 7
  SENSOR (4/26, §12.2); svc 10 NAV_STATUS (sender-defined `0x8001` inactive
  stub `NavStatusCodec.kt:31-35` + `NavStatusChannel.kt:32-38`, live
  `encodeStatus :45-56` / `postStatus :54-69` + `MapsGuidance.kt:159-173`
  built but unwired, inbound ignored `:41-47`); svc 14 NOTIFICATION
  (sender-defined stub `MessagingCodec.kt:44-94`, owner
  `ProjectionService.kt:534-548` — real `xjm` IDs never recovered,
  `notification.proto:3-13`).
- MISSING by design — svc 11/12 media playback/browser (ignored
  `GalMessage.kt:198-199`, `ProjectionService.kt:177-181`; replaced by
  `MediaPlaybackMonitor.kt:29-64` + `MediaNowPlaying.kt:12-30` into ch2 — no
  artwork/queue/prev-next).
- MISSING — svc 9 BLUETOOTH (enum `GalService.kt:167` only, no owner;
  wireless is WiFi-Direct only); svc 13 PHONE_STATUS (control-24 observed
  only, no `InCallService`); svc 15 RADIO (opaque bytes); svc 16
  VENDOR_EXTENSION (DTO `services.proto:119-123` only); svc 17/18
  WIFI_PROJECTION/DISCOVERY (DTO only — wireless never speaks 17/18); svc
  19–22 CAR_CONTROL/LOCAL_MEDIA/BUFFERED/INTENT (enum only
  `GalService.kt:177-181`); INST cluster / DIAGNOSTICS / LATENCY / SYNC /
  SNOOP / SECURITY subsystems (no ID/codec/owner; closest is local
  encode-to-send `VideoEvent.FrameSent.latencyUs`,
  `VideoSinkChannel.kt:409-416`).

### 12.4 Transports

Infra SUPPORTED: ch0-prio + round-robin (`ChannelSendQueue.kt:39-68,94`);
full-jitter backoff (`ReconnectBackoff.kt:28-46`); `AckTracker`;
`FocusArbitration`; target-channel CONTROL-only-0x7 opens; sequential
wire-order opens.

- USB AOAP: fd-as-stream PARTIAL (consumes ATTACHED as `StreamTransport.kt:
  13-16` + `GalTransport.kt:8-11`, opened `UsbConnector.kt:127-130` — HU puts
  phone in accessory mode, MA never drives the switch); receiver PARTIAL
  (`UsbReceiver.kt:17-29`, `UsbConnector.kt:44-82`, `MainActivity` filter —
  no `FORCE_START`, no TPlus, no `MANAGE_USB`); permission/retry/detach/fail
  SUPPORTED (`UsbSession.kt:55-109`, `UsbConnector.kt:92-141`,
  `TransportState.kt:44-48`).
- Wireless: shape PARTIAL (`WirelessSession.kt:41-52`
  IDLE→NEGOTIATING→READY→CONNECTED; pairing-screen tap stands in for BT,
  `WifiDirectConnector.kt:30-34,58-64` `onBluetoothAssociated` with no BT);
  socket→intake + state mirror SUPPORTED (`:167-199,226-234`,
  `TransportIntake.kt:29-34`, `TransportState.kt:51-54`,
  `WirelessSession.kt:70-110`); fixed `GAL_PORT=5277 :264`,
  `CONNECT_TIMEOUT=10s :266`, `tcpNoDelay :174`, best-effort WiFi bind
  `:206-224`.
- [x] MISSING wireless — `BT_START`, BT ACL/HFP per-stage timeouts, RFCOMM
  handshake + `fallback_to_rfcomm_on_t_minus`, CDM association trigger (no
  `CarProcessCompanionDeviceService`), hidden-SSID / dual-STA /
  local-only-network (`ebe` flags).
- SUPPORTED — selector USB > wireless > loopback-dev-only
  (`TransportSelector.kt:22-28`, `TransportKind.kt:13-23`,
  `TransportState.kt:61-67`); loopback listener (`HeadUnitServer.kt:25-45,58`
  IPv4 + `tcpNoDelay :42`). Note: `ProjectionService.kt:167-168` currently
  `accept()`s TCP directly — USB/wireless `TransportIntake` parking is built
  but not drained in this `serve()` leg.

---

## 13. Services / components / permissions / role

MA manifest (`auto/src/main/AndroidManifest.xml:1-135`): **13** perms, **1**
activity (`.MainActivity` exported + MAIN/LAUNCHER + USB_ATTACHED + filter
`:63-82`), **3** services (`ProjectionService` `connectedDevice` `:84-87`,
`MusicCaptureService` `mediaProjection` `:93-96` API34+ split,
`MessageMirrorService` exported + BIND_NLS `:124-132`), **2** receivers
(`UsbReceiver` `:101-108`, `BootReceiver` `:111-117`), **0** providers,
queries maps/music/browser/DIAL (`:42-52`). Gearhead manifest-out (2702
lines): **81** `<uses-permission>` (`:22-102`), 56a/87s/42r/11p (11 providers
verified: DeveloperSettings, SettingsSearch, ProjectionState `:404-408`
exported, Initialization, GhMicrophone, SharedPreferences, Troubleshooter,
CoolwalkColors, Bugreport, Proxy, ProcessLifecycleOwner).

- EQUIVALENT string — `WAKE_LOCK:22→MA:9`, `INTERNET:23→:12`,
  `ACCESS_WIFI_STATE:26→:21`, `CHANGE_NETWORK_STATE:27→:22`,
  `CONNECTED_DEVICE:29→:7`, `CHANGE_WIFI_STATE:37→:17`,
  `FOREGROUND_SERVICE:38→:6`, `BOOT_COMPLETED:39→:27`, `FINE_LOCATION:51→:23`,
  `RECORD_AUDIO:56→:31`, `NEARBY_WIFI_DEVICES:60→:24`,
  `POST_NOTIFICATIONS:62→:8`. PARTIAL — `COARSE_LOCATION:52` (MA FINE only);
  `QUERY_ALL_PACKAGES:32` (MA `<queries>` + `CarApps.kt:60-94` instead).
- [x] MISSING + breakage — FGS LOCATION/MICROPHONE (MA `MEDIA_PROJECTION:36`
  only; `ProjectionService` connectedDevice-only vs `CarChimeraService:2111`
  triple `microphone|connectedDevice|location`): API34+ bg mic/location FGS
  killed, ch6/ch7 starve. BT family (`BLUETOOTH:35/_ADMIN:36/_SCAN:58/
  _CONNECT:59/_PRIVILEGED:65`, `LOCAL_MAC_ADDRESS:73` — MA zero BT perms):
  `BT_START→RFCOMM→TCP` never fires, wireless discovery dead. Telephony/
  sensitive (`CALL_PHONE:47`, `READ_PHONE_STATE:48`, `READ_CALL_LOG:49`,
  `READ_CONTACTS:50`, `RECEIVE_SMS:54`, `SEND_SMS:55`, `READ_CALENDAR:57`,
  `RECEIVE_SENSITIVE_NOTIFICATIONS:96`, `GET_INTENT_SENDER_INTENT:99` +
  app-ops): call/message templates empty, fail-closed silence
  (`MessagingCarAppService.kt:98-140` fires app intents but cannot read
  restricted data). `MANAGE_USB:74`: no wired auto-launch/role-switch.
  Audio/priv (`MODIFY_AUDIO_ROUTING:75`, `TETHER_PRIVILEGED:82`,
  `POWER_SAVER:81`, `MANAGE_USERS:83`, `READ_PRIVILEGED_PHONE_STATE:77`,
  `CHANGE_COMPONENT_ENABLED_STATE:67`, `MODIFY_PHONE_STATE:97`,
  `CONTROL_INCALL_EXPERIENCE:70`, `CALL_PRIVILEGED:66`): sinks misroute to
  phone speaker, InCall dead. Trusted/projection (`ADD_TRUSTED_DISPLAY:94`,
  `ADD_ALWAYS_UNLOCKED_DISPLAY:90`, `CREATE_VIRTUAL_DEVICE:91`,
  `TOGGLE_AUTOMOTIVE_PROJECTION:92`, `CAPTURE_SECURE_VIDEO_OUTPUT:98`):
  private-only, secure black. CDM (`ASSOCIATE_COMPANION_DEVICES:95`,
  `REQUEST_COMPANION_PROFILE_AUTOMOTIVE_PROJECTION:93`,
  `REQUEST_COMPANION_SELF_MANAGED:78`,
  `COMPANION_APPROVE_WIFI_CONNECTIONS:68`,
  `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE:44`): wireless CDM trigger
  absent. Launch (`START_ACTIVITIES_FROM_BACKGROUND:79`,
  `ENTER_CAR_MODE_PRIORITIZED:72`, `SYSTEM_ALERT_WINDOW:34`,
  `ACTIVITY_EMBEDDING:64`, `INTERACT_ACROSS_PROFILES:101`): background/trusted
  launch denied. Host/GMS (`NAVIGATION_TEMPLATES:42`, `ACCESS_SURFACE:43`,
  `READ_GSERVICES:33`, `GEARHEAD_SERVICE:41`, `START_PROJECTED_ACTIVITY:88`,
  telemetry/persona/appwidget/dump/location-hardware/day-night/app-ops/wifi-coex/
  vibrate/biometric/storage): host/template/GMS paths absent (NAV/
  ACCESS_SURFACE blocks CarApp host — `GearheadHostProbe.kt:70-73`
  `Class.forName` only).
- Components: PARTIAL — `CarChimeraService:2107-2115` (MA covers
  connectedDevice FGS + accept/pump/openNext `ProjectionService.kt:57-77,
  304-628`; missing mic/location types, START action, GAL replacement:
  GMS-compat clients cannot bind); wireless setup (`WirelessSetupSharedService:
  1309-1313` + CarService + StartupActivity/Receivers + `WifiBluetoothReceiver:
  2468-2481` + `DeepLinkResolver:2492-2506` + `CarStartupServiceImpl:2052-2062`
  — MA has `WifiDirectConnector/UsbConnector/WirelessSession/
  TransportSelector` only: no real-car wireless, DHU TCP only); NLS
  (`SharedNotificationListener:993-1005` — MA structure-equivalent
  `MessageMirrorService.kt:22-31` + dual-gate `MessagingPrefs`, missing
  singleUser/meta, sensitive perm, app-ops, intent-sender: sensitive threads
  invisible); USB (`CarUsbReceiver:2130-2141` + TPlus `:2143-2150` +
  `FirstActivityImpl:2067-2086` + reset/connectivity receivers — MA
  `UsbReceiver` + filter + `UsbConnector/UsbSession` only: no STATE/HANDSHAKE/
  FORCE_START/DUPLEX/RESET); boot (`ComponentInitReceiver:2152-2160` BOOT +
  REPLACED exported `:car` — MA `BootReceiver.kt:17-21` non-exported BOOT
  restart + role republish only).
- [x] MISSING components — `GearheadCarStartupService:2174-2177`
  (`CAR_STARTUP_NOTIFICATION`: HU startup notification unconsumed, MA manual/
  boot start only); `CarSetupServiceImpl:2045-2050` (`CAR_SETUP_SERVICE`
  handshake absent); `CarProcessCompanionDeviceService:2017-2027` (CDM:
  wireless association never triggers); `CarProjectionInCallServiceImpl:
  1266-1280` (+ disabled NonCar `:1252-1263` — no `InCallService` in MA
  manifest: projected calls dead); `CarSystemUiControllerService:931-933` +
  `AppDecorService:935-937` (HU compositor/decor bind rejected, embedded
  windows fail).
- MA-only (sender-specific, no gearhead counterpart):
  `MusicCaptureService.kt:31-106` (mediaProjection split),
  `MessagingCarAppService.kt:31-170` (plain class owned by
  `ProjectionService.kt:534-548`), `CarMessagingAudio.kt:28-75` (TTS-ch4 +
  mic-turn-ch6 seam, `transcribe` no-op), `CarApps.kt:25-99` (3-slot launcher),
  `HostVsMirror.kt:19-67` (MIRROR default) + `GearheadHostProbe.kt:37-116`
  (on-demand probe, never watched).
- Role: PARTIAL — constants `MaosRole.kt:22,25,28` + reader
  `MaosRoleStatus.kt:18-21` (fail-false) + `DisplayRoutePolicy.kt:34-35`
  (`false→PRIVATE_VIRTUAL`) + probe `:40` + boot republish
  (`BootReceiver.kt:19`); RRO pin + privapp allowlist MISSING (acknowledged
  `MaosRole.kt:8-11`, both boot-fatal — land one at a time).

---

## 14. Protobuf coverage (MA `gal/*.proto` vs gearhead `x*`)

Provenance notes live in `control.proto:1-9`, `services.proto:10-11`.

- EQUIVALENT (numbers/wire/cardinality exact): `xoa→control.proto:110-117`,
  `xob→:120-137`, `xnz/Service→:141-160`, `xko→services.proto:65-75`,
  `xhf→:44-48`, `xpd→:50-62`, `xkj→:20-28`, `xpc→:30-40`,
  `xof/MediaSetupRequest→media.proto:36-38`, `xoh→:49-54`, `xoi→:57`,
  `xgz/MediaAck→:60-64`, `xph→:67-70`, `xpf→:73-76`, `xjt→input.proto:103-111`,
  `xom→:62-66`, `xkf→:97-99`, `xkc→:121-123`, `xnu→sensors.proto:67-70`,
  `xny→:35-62` (26 types, dumper order).
- PARTIAL (wire ID in `GalMessage.kt`, layout MA-defined): `xiu/0x8003` vs
  `MediaSetupResponse:42-46` (name/layout not pinned); `xnv/0x8002→
  sensors.proto:74-77`, `xnr/0x8003→:80-94`, `xns/0x8004→:122-125` (explicit
  caveat `sensors.proto:14-19`); `xoa` sender shape field-5-only
  (`control.proto:96-109`).
- [x] MISSING (no MA DTO; HU answers `0xff`, tolerated per
  `ProjectionService.kt:177-182,211-213` + `GalMessage.kt:190-199`):
  `xow/0x800A`, `xkt/0x8006` (ID `GalMessage.kt:77-79` only),
  `xkq/0x8013`, `0x800B` sync (no-payload, counted `GalMessage.kt:144-151`),
  `xkn`/svc-11 + `xki`/svc-12 channel messages (gap `services.proto:4-8`,
  `media.proto:4-9` — now-playing rendered into ch2 instead), `xjm`/svc-14
  real IDs (MA `notification.proto:1-13` + `GalMessage.kt:120-142`
  MA-defined), nav-status real IDs (MA `navigation.proto:1-17` +
  `GalMessage.kt:99-118` MA-defined), `xjw/xnx/xht/xlo/xoz/xpl` channel
  payloads beyond descriptor slots (`services.proto:89-127`),
  `xnb/xmf/xkp` descriptor bytes (`control.proto:147-155` placeholders).
- Traps (FINDINGS §4, must re-check with `disasm.py` before trusting
  `defpackage` logic): `abpe.java:501` inverted (`0x1000` = has-hasbit,
  `xhf 0x150B` proof); descriptor escapes `\b\f\r` = fields 8/12/13
  (single-scan unescaper required); `wub.o` +1 on `jdk/jdi` recv
  (documented `GalMessage.kt:9-14`; `jem/jar` raw).

---

## 15. Functional verification appendix (open)

- [ ] Control-plane interop: full DHU handshake per HANDOFF §6 with every ID
  in §11 exercised (send 24-parse + 255-send + 0xFFFF-teardown probes need a
  DHU + Pixel run; JVM leg is `:auto:protocol:testDebugUnitTest` — GREEN,
  281 tests, incl. the red-from-birth `GalConnectionIoThreadTest` concurrency
  case fixed via an explicit `encrypted` seam on the service-channel
  send/enqueue overloads).
- [ ] Channel interop: video STOP on ch2, `0x800A` round-trip, input-feedback
  `0x8004`, sensor 2s-timeout + remaining 22 types, BT/CDM/RFCOMM + `ebe`
  flags — each needs DHU/real-HU capture via `dhu_relay.py`. Call card +
  status cluster + night/parked wiring are DHU-verifiable (loopback shows
  them); real-car wireless/CDM/InCall stay deferred-blind.
- [ ] Missing-proto recovery: `xkt` typed, `xow` envelope stub, 22 sensor
  readings typed, `xnv/xnr/xns` MA-defined layouts documented; `xjm/xkn/xki`
  still empty-descriptor (stubbed, never invented). Re-prove with
  `verify_tls.sh`-style mutual-auth + DHU run before shipping any new bytes.
- [ ] Component/permission leg: RRO pin → `cmd role get-role-holders`,
  allowlist → boot + `SecurityException`-free projection, FGS types → bg
  mic/location alive, CDM → wireless trigger, InCall → HU call UI — MAOS
  side done (RRO repoint + Cast streaming migration + allowlist + privapp
  wiring, single flash at end); device leg needs the flash + Pixel run.
- [ ] Credential rotation before 2026-12-09 per HANDOFF §7 runbook
  (`extract_key.py` re-pin + `verify_tls.sh` + PEM swap + DHU handshake).

---

## 16. Critical files

- `auto/src/main/java/com/vayunmathur/auto/platform/CarDisplay.kt` — MA
  surface under test (`CarPresentation` 390–1075).
- `auto/src/main/java/com/vayunmathur/auto/platform/VideoSinkChannel.kt`,
  `platform/VideoEncoder.kt` — frame feed / cadence.
- `auto/src/main/java/com/vayunmathur/auto/platform/InputChannel.kt` —
  touch/key/scroll routing.
- `auto/src/main/java/com/vayunmathur/auto/platform/CarApps.kt`,
  `platform/CarMapsMirror.kt`, `platform/MediaNowPlaying.kt`,
  `platform/MediaPlaybackMonitor.kt`, `platform/NavGuidanceMonitor.kt`,
  `platform/NavStatusChannel.kt`, `platform/GuidanceChannel.kt`,
  `platform/SensorChannel.kt` — card content.
- `auto/protocol/.../NavStatusCodec.kt`, `FocusArbitration.kt`,
  `InputCodec.kt`, `DisplayRoutePolicy.kt`, `MaosRole.kt` — pixel-path codec,
  focus, scale, route, role.
- `auto/src/main/AndroidManifest.xml`, `auto/src/main/res/values/strings.xml`
  (`car_*` 260–279), `res/xml/usb_accessory_filter.xml` — manifest/strings.
- `analysis/maauto/jadx-out/sources/com/google/android/apps/auto/components/ui/*`
  (media, listview, telecom, touch, widget/navigation, actionpanel),
  `.../system/statusbar/RailStatusBarFragment.java`,
  `.../system/facetbar/` (hotseat, widget, `ResizeButton.java`),
  `.../system/dashboard/design/DashboardCardView.java`,
  `.../coolwalk/` (button, focusring, card),
  `.../drawer/` (alpha jump, motion filtering), `.../sdk/ui/PagedListView.java`.
- `analysis/maauto/jadx-out/sources/.../projection/gearhead/sdk/CarDrawerLayout.java`,
  `sdk/CarAppLayout.java`, `sdk/CarDrawerLayout.java` (latter two: existence
  assumed from plan — verify before citing).
- `analysis/maauto/jadx-out/sources/.../gearhead/appdecor/` (`AppContentLayout`,
  `DrawerContentLayout`, `StatusBarView`), `.../gearhead/service/`
  (`GearheadService`, `CarSystemUiControllerService`), `.../gearhead/rail/ui/`
  (`SlidableLinearLayout`, `ImageButtonWithAlphaFade`).
- `analysis/maauto/jadx-out/sources/.../projection/gearhead/R.java` — ID
  authority (verify exact package path before citing — `R.java` location
  assumed from plan).
- `analysis/maauto/manifest-out/resources/res/layout/*` (751 files),
  `res/values*/` (dimens/colors/styles/strings/integers/bools + night/car),
  `res/drawable-*/`, `res/font*/`, `assets/*`,
  `manifest-out/resources/AndroidManifest.xml` (2702 lines, 81 perms, 11
  providers).
- `analysis/maauto/disasm.py` (+ `disasm_*.py` / `disasm*.txt`) — mandatory
  cross-check for load-bearing `defpackage` logic.
- `auto/docs/FINDINGS.md` (§1 role, §2 GAL, §6 components 56a/87s/42r/11p),
  `auto/docs/HANDOFF.md` (§2 bring-up, §6 test loop, §7 credential/expiry,
  §8 MAOS, §10 mirror decision).
