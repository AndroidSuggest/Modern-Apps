//! Offline-transit JNI: `findTransitRouteNative`, `getStopDeparturesNative`,
//! `activeVehiclesNative`, `getFeedTimezoneNative` and
//! `nearestStopMotisIdNative`, plus the transit-pack cache and the realtime
//! overlay decoder they share. Pure move out of `lib.rs`; no logic changes.
//!
//! Split into [`cache`] (pack cache), [`overlay`] (realtime decoder),
//! [`route`] (`findTransitRouteNative`), [`departures`]
//! (`getStopDeparturesNative`), [`vehicles`] (`activeVehiclesNative`) and
//! [`lookups`] (`getFeedTimezoneNative`, `nearestStopMotisIdNative`). Pure
//! moves; this module only declares the submodules.

pub mod cache;
pub mod departures;
pub mod lookups;
pub mod overlay;
pub mod route;
pub mod vehicles;
