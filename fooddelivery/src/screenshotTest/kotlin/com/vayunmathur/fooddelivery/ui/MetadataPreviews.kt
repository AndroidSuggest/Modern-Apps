package com.vayunmathur.fooddelivery.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.fooddelivery.data.CartItem
import com.vayunmathur.fooddelivery.data.MenuCategory
import com.vayunmathur.fooddelivery.data.MenuItem
import com.vayunmathur.fooddelivery.data.Merchant
import com.vayunmathur.fooddelivery.data.MerchantDetail
import com.vayunmathur.fooddelivery.data.Modifier as DataModifier
import com.vayunmathur.fooddelivery.data.ModifierGroup
import com.vayunmathur.library.ui.DynamicTheme

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store listing images for `:fooddelivery`. See `common-conventions-preview-metadata`.
 *
 * The screens fetched from the Bites API inside a `LaunchedEffect`, so [HomeContent] and
 * [RestaurantContent] were split out of them; [CartScreen] was already stateless.
 *
 * Every image URL below is deliberately empty. `AsyncImage` needs a real `Context` for its
 * `ImageLoader` and a network round-trip that a static preview never waits for, so a URL here
 * would buy an empty box at best and a Layoutlib failure at worst — the card layouts already
 * degrade to text-only when there is no image.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1…, Preview2…).
 */
class MetadataPreviews {

    /** Prices are in cents, the way the API reports them. */
    private val merchants = listOf(
        Merchant(
            id = 101,
            name = "Taqueria del Sol",
            addressStreet = "418 Valencia St",
            addressCity = "San Francisco",
            addressState = "CA",
            isOpen = true,
            averageRating = 4.8,
            totalRatings = 1_204,
            rewardsPercentage = 5.0,
            merchantTags = listOf("Mexican", "Tacos", "Burritos"),
            freeDeliveryThreshold = 2_500,
            doordashMarkup = 1_150,
            doordashMarkupComparison = 1_580,
            distance = 0.4,
        ),
        Merchant(
            id = 102,
            name = "Golden Wok",
            addressStreet = "77 Clement St",
            addressCity = "San Francisco",
            addressState = "CA",
            isOpen = true,
            averageRating = 4.5,
            totalRatings = 862,
            merchantTags = listOf("Chinese", "Noodles"),
            distance = 1.2,
        ),
        Merchant(
            id = 103,
            name = "Marina Poke",
            addressStreet = "2201 Chestnut St",
            addressCity = "San Francisco",
            addressState = "CA",
            isOpen = false,
            closingTime = "Closed · opens 11:00 AM",
            averageRating = 4.6,
            totalRatings = 431,
            merchantTags = listOf("Hawaiian", "Healthy"),
            distance = 2.1,
        ),
        Merchant(
            id = 104,
            name = "Sunset Slice",
            addressStreet = "1300 Irving St",
            addressCity = "San Francisco",
            addressState = "CA",
            isOpen = true,
            nextOpenWindow = "Open until 11:00 PM",
            averageRating = 4.3,
            totalRatings = 2_017,
            rewardsPercentage = 3.0,
            merchantTags = listOf("Pizza", "Late night"),
            distance = 3.4,
        ),
    )

    private val salsaChoice = ModifierGroup(
        id = 1,
        name = "Salsa",
        required = true,
        minSelections = 1,
        maxSelections = 1,
        modifiers = listOf(
            DataModifier(id = 11, name = "Salsa verde"),
            DataModifier(id = 12, name = "Salsa roja"),
            DataModifier(id = 13, name = "Habanero", price = 50),
        ),
    )

    private val extras = ModifierGroup(
        id = 2,
        name = "Add extras",
        maxSelections = 3,
        modifiers = listOf(
            DataModifier(id = 21, name = "Guacamole", price = 225),
            DataModifier(id = 22, name = "Queso fresco", price = 150),
            DataModifier(id = 23, name = "Extra tortilla", price = 75),
        ),
    )

    private val carnitasTaco = MenuItem(
        id = 1001,
        name = "Carnitas taco",
        description = "Slow-braised pork shoulder, onion, cilantro, corn tortilla",
        price = 495,
        category = "Tacos",
        merchantId = 101,
        doordashPrice = 675,
        modifierGroups = listOf(salsaChoice, extras),
    )

    private val menuItems = listOf(
        carnitasTaco,
        MenuItem(
            id = 1002,
            name = "Al pastor taco",
            description = "Adobada pork off the trompo, grilled pineapple",
            price = 495,
            category = "Tacos",
            merchantId = 101,
            doordashPrice = 675,
            modifierGroups = listOf(salsaChoice),
        ),
        MenuItem(
            id = 1003,
            name = "Baja fish taco",
            description = "Beer-battered cod, cabbage slaw, chipotle crema",
            price = 550,
            category = "Tacos",
            merchantId = 101,
        ),
        MenuItem(
            id = 1004,
            name = "Carne asada burrito",
            description = "Grilled steak, rice, black beans, pico de gallo, jack cheese",
            price = 1_295,
            category = "Burritos",
            merchantId = 101,
            doordashPrice = 1_580,
            modifierGroups = listOf(extras),
        ),
        MenuItem(
            id = 1005,
            name = "Veggie burrito",
            description = "Grilled peppers, rice, pinto beans, guacamole",
            price = 1_095,
            category = "Burritos",
            merchantId = 101,
        ),
        MenuItem(
            id = 1006,
            name = "Horchata",
            description = "House-made, cinnamon and rice",
            price = 425,
            category = "Drinks",
            merchantId = 101,
        ),
    )

    private val taqueria = MerchantDetail(
        id = 101,
        name = "Taqueria del Sol",
        addressStreet = "418 Valencia St",
        addressCity = "San Francisco",
        addressState = "CA",
        isOpen = true,
        nextOpenWindow = "Open until 10:00 PM",
        averageRating = 4.8,
        totalRatings = 1_204,
        rewardsPercentage = 5.0,
        merchantTags = listOf("Mexican", "Tacos", "Burritos"),
        categories = listOf(
            MenuCategory(id = 1, name = "Tacos", sortOrder = 0, itemIds = listOf(1001, 1002, 1003), merchantId = 101),
            MenuCategory(id = 2, name = "Burritos", sortOrder = 1, itemIds = listOf(1004, 1005), merchantId = 101),
            MenuCategory(id = 3, name = "Drinks", sortOrder = 2, itemIds = listOf(1006), merchantId = 101),
        ),
        items = menuItems,
    )

    private val cart = listOf(
        CartItem(
            menuItem = carnitasTaco,
            quantity = 2,
            selectedModifiers = listOf(DataModifier(id = 11, name = "Salsa verde"), DataModifier(id = 21, name = "Guacamole", price = 225)),
            merchantId = 101,
            merchantName = "Taqueria del Sol",
        ),
        CartItem(
            menuItem = menuItems[3],
            merchantId = 101,
            merchantName = "Taqueria del Sol",
        ),
        CartItem(
            menuItem = menuItems[5],
            quantity = 2,
            merchantId = 101,
            merchantName = "Taqueria del Sol",
        ),
    )

    @PreviewTest
    @Preview(name = "1-nearby", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Nearby() {
        DynamicTheme(darkTheme = true) {
            HomeContent(merchants = merchants)
        }
    }

    @PreviewTest
    @Preview(name = "2-menu", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Menu() {
        DynamicTheme(darkTheme = true) {
            RestaurantContent(merchant = taqueria)
        }
    }

    @PreviewTest
    @Preview(name = "3-cart", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3Cart() {
        DynamicTheme(darkTheme = true) {
            CartScreen(items = cart, onRemoveItem = {}, onCheckout = {})
        }
    }
}
