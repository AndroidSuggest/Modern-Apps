# AGENTS.md — fooddelivery/ (':fooddelivery')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `fooddelivery/` + allowed shared modules. Do not root-scan.

_Install: ./install fooddelivery (dev by default)._

- Gradle: :fooddelivery / dir: fooddelivery/
- Package roots present: ui, data, platform, intents, notifications
- Entry files: MainActivity.kt, FoodDeliveryApplication.kt
- Deps: :library:image, :library:map, :library:network
- Metadata: metadata_data/fooddelivery.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/fooddelivery/FoodDeliveryApplication.kt
- com/vayunmathur/fooddelivery/MainActivity.kt
- com/vayunmathur/fooddelivery/api/BitesApi.kt
- com/vayunmathur/fooddelivery/data/AddressStore.kt
- com/vayunmathur/fooddelivery/data/CartStore.kt
- com/vayunmathur/fooddelivery/data/Models.kt
- com/vayunmathur/fooddelivery/intents/OrderLookupIntent.kt
- com/vayunmathur/fooddelivery/ipc/OrderLookupContract.kt
- com/vayunmathur/fooddelivery/notifications/OrderLiveUpdate.kt
- com/vayunmathur/fooddelivery/notifications/OrderTrackingService.kt
- com/vayunmathur/fooddelivery/platform/AppInit.kt
- com/vayunmathur/fooddelivery/ui/AccountAddresses.kt
- com/vayunmathur/fooddelivery/ui/AccountAuth.kt
- com/vayunmathur/fooddelivery/ui/AccountProfile.kt
- com/vayunmathur/fooddelivery/ui/AccountScreen.kt
- com/vayunmathur/fooddelivery/ui/AddressFormCard.kt
- com/vayunmathur/fooddelivery/ui/CartScreen.kt
- com/vayunmathur/fooddelivery/ui/CheckoutScreen.kt
- com/vayunmathur/fooddelivery/ui/CheckoutSections.kt
- com/vayunmathur/fooddelivery/ui/CheckoutSuccess.kt
- com/vayunmathur/fooddelivery/ui/CheckoutTotals.kt
- com/vayunmathur/fooddelivery/ui/DealsScreen.kt
- com/vayunmathur/fooddelivery/ui/HomeScreen.kt
- com/vayunmathur/fooddelivery/ui/ModifierDialog.kt
- com/vayunmathur/fooddelivery/ui/OrdersScreen.kt
- com/vayunmathur/fooddelivery/ui/OrdersScreenSection.kt
- com/vayunmathur/fooddelivery/ui/OrderTrackingScreen.kt
- com/vayunmathur/fooddelivery/ui/RestaurantScreen.kt
- com/vayunmathur/fooddelivery/ui/RestaurantSections.kt

## Verify (this module only)
```
./gradlew :fooddelivery:compileDevKotlin
./gradlew :fooddelivery:lint
./gradlew :fooddelivery:checkMetadata
```


