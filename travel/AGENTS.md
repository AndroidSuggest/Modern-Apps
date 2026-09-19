# AGENTS.md — travel/ (':travel')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `travel/` + allowed shared modules. Do not root-scan.

_Install: ./install travel (dev by default)._

- Gradle: :travel / dir: travel/
- Package roots present: ui, data, network
- Entry files: MainActivity.kt
- Deps: :library:room, :library:network, :library:image
- Metadata: metadata_data/travel.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/travel/MainActivity.kt
- com/vayunmathur/travel/data/BookedTrip.kt
- com/vayunmathur/travel/data/Customer.kt
- com/vayunmathur/travel/data/FrequentFlyer.kt
- com/vayunmathur/travel/data/RecentSearch.kt
- com/vayunmathur/travel/data/TravelDatabase.kt
- com/vayunmathur/travel/data/TravelRepository.kt
- com/vayunmathur/travel/network/AirlineDto.kt
- com/vayunmathur/travel/network/AncillaryDto.kt
- com/vayunmathur/travel/network/CustomerDto.kt
- com/vayunmathur/travel/network/ManageDto.kt
- com/vayunmathur/travel/network/OfferDto.kt
- com/vayunmathur/travel/network/OrderDto.kt
- com/vayunmathur/travel/network/PlaceDto.kt
- com/vayunmathur/travel/network/StaysApi.kt
- com/vayunmathur/travel/network/StaysDto.kt
- com/vayunmathur/travel/network/TravelApi.kt
- com/vayunmathur/travel/ui/AncillariesPage.kt
- com/vayunmathur/travel/ui/CancellationPage.kt
- com/vayunmathur/travel/ui/ChangePage.kt
- com/vayunmathur/travel/ui/Common.kt
- com/vayunmathur/travel/ui/ConfirmationPage.kt
- com/vayunmathur/travel/ui/FlightResultsPage.kt
- com/vayunmathur/travel/ui/FlightSearchForm.kt
- com/vayunmathur/travel/ui/HomePage.kt
- com/vayunmathur/travel/ui/OfferReviewPage.kt
- com/vayunmathur/travel/ui/OrderDetailPage.kt
- com/vayunmathur/travel/ui/PartialOffersPages.kt
- com/vayunmathur/travel/ui/PassengersFields.kt
- com/vayunmathur/travel/ui/PassengersPage.kt
- com/vayunmathur/travel/ui/PaymentPage.kt
- com/vayunmathur/travel/ui/SeatMapPage.kt
- com/vayunmathur/travel/ui/SettingsPage.kt
- com/vayunmathur/travel/ui/StayConfirmationPage.kt
- com/vayunmathur/travel/ui/StayDetailPage.kt
- com/vayunmathur/travel/ui/StayGuestsPage.kt
- com/vayunmathur/travel/ui/StayLabels.kt
- com/vayunmathur/travel/ui/StayResultsScreen.kt
- com/vayunmathur/travel/ui/StaySearchForm.kt
- com/vayunmathur/travel/ui/StaysPages.kt
- com/vayunmathur/travel/ui/TravelFormat.kt
- com/vayunmathur/travel/ui/TripsPage.kt
- com/vayunmathur/travel/util/ContactAutofill.kt
- com/vayunmathur/travel/util/TravelBookingOps.kt
- com/vayunmathur/travel/util/TravelCustomerOps.kt
- com/vayunmathur/travel/util/TravelOrderOps.kt
- com/vayunmathur/travel/util/TravelSearchOps.kt
- com/vayunmathur/travel/util/TravelStayOps.kt
- com/vayunmathur/travel/util/TravelUiContract.kt
- com/vayunmathur/travel/util/TravelUiState.kt
- com/vayunmathur/travel/util/TravelViewModel.kt

## Verify (this module only)
```
./gradlew :travel:compileDevKotlin
./gradlew :travel:lint
./gradlew :travel:checkMetadata
```


