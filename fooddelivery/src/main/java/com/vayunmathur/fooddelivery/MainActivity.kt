package com.vayunmathur.fooddelivery

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.stripe.android.PaymentConfiguration
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconLocalOffer
import com.vayunmathur.library.ui.IconPackage
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.IconShoppingCart
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.fooddelivery.api.BitesApi
import com.vayunmathur.fooddelivery.data.CartItem
import com.vayunmathur.fooddelivery.data.CartStore
import com.vayunmathur.fooddelivery.notifications.OrderLiveUpdate
import com.vayunmathur.fooddelivery.ui.AccountScreen
import com.vayunmathur.fooddelivery.ui.CartScreen
import com.vayunmathur.fooddelivery.ui.CheckoutScreen
import com.vayunmathur.fooddelivery.ui.DealsScreen
import com.vayunmathur.fooddelivery.ui.HomeScreen
import com.vayunmathur.fooddelivery.ui.OrderTrackingScreen
import com.vayunmathur.fooddelivery.ui.OrdersScreen
import com.vayunmathur.fooddelivery.ui.RestaurantScreen
import kotlinx.serialization.Serializable
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle

sealed interface Route : NavKey {
    @Serializable data object Home : Route
    @Serializable data object Cart : Route
    @Serializable data object Deals : Route
    @Serializable data object Orders : Route
    @Serializable data object Account : Route
    @Serializable data class Restaurant(val id: Int) : Route
    @Serializable data object Checkout : Route
    @Serializable data class OrderTracking(val orderId: Int) : Route
}

class MainActivity : ComponentActivity() {
    // The order to open on the tracking screen, set from a notification tap. Held as
    // Compose state so onNewIntent can push a new deep link into the running UI.
    private val trackOrderId = mutableStateOf<Int?>(null)

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        trackOrderId.value = intent.trackOrderIdOrNull()
        requestNotificationPermissionIfNeeded()
        // api.deliverycollective.com is on AWS Elastic Beanstalk and serves an ACM cert
        // chaining to Amazon Root CA 1, which FIRST_PARTY (ISRG + GTS only) doesn't carry —
        // pinning to it fails the handshake before any request goes out. STANDARD adds the
        // Amazon roots.
        NetworkClient.init(this, TrustBundle.STANDARD)
        enableEdgeToEdge()

        val prefs = getSharedPreferences("fooddelivery_prefs", Context.MODE_PRIVATE)
        val tokenJson = prefs.getString("token_json", null)
        if (tokenJson != null) {
            BitesApi.restoreToken(tokenJson)
        }

        PaymentConfiguration.init(
            applicationContext,
            "pk_live_51NQy7lFJFBMK4hv9KubgZcyH2Wy0MsXn9BtrtM7moEi762WE7pcmZ1JL9BrCKPRKw6ZJdGo9YJSA1pidb0KUthlJ00Wr4bcpVD"
        )

        setContent {
            DynamicTheme {
                FoodDeliveryApp(trackOrderId)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.trackOrderIdOrNull()?.let { trackOrderId.value = it }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private fun Intent.trackOrderIdOrNull(): Int? =
    getIntExtra(OrderLiveUpdate.EXTRA_TRACK_ORDER_ID, -1).takeIf { it > 0 }

@Composable
private fun FoodDeliveryApp(trackOrderId: MutableState<Int?>) {
    val context = LocalContext.current
    val backStack = rememberNavBackStack<Route>(Route.Home)

    // A notification tap deep-links to that order's tracking screen.
    LaunchedEffect(trackOrderId.value) {
        val id = trackOrderId.value ?: return@LaunchedEffect
        backStack.add(Route.OrderTracking(id))
        trackOrderId.value = null
    }
    val currentPage = backStack.backStack.last()
    val cart = remember { mutableStateListOf<CartItem>().also { it.addAll(CartStore.getAll(context)) } }

    val pages = listOf(
        BottomBarItem("Home", Route.Home) { IconHome() },
        BottomBarItem("Cart", Route.Cart) { IconShoppingCart() },
        BottomBarItem("Deals", Route.Deals) { IconLocalOffer() },
        BottomBarItem("Orders", Route.Orders) { IconPackage() },
        BottomBarItem("Account", Route.Account) { IconPerson() },
    )

    MainNavigation(
        backStack = backStack,
        bottomBar = { BottomNavBar(backStack, pages, currentPage) }
    ) {
        entry<Route.Home> {
            HomeScreen(onMerchantClick = { id -> backStack.add(Route.Restaurant(id)) })
        }
        entry<Route.Cart> {
            CartScreen(
                items = cart,
                onRemoveItem = { cart.removeAt(it); CartStore.save(context, cart) },
                onCheckout = { backStack.add(Route.Checkout) },
                onEditModifiers = { index, modifiers ->
                    cart[index] = cart[index].copy(selectedModifiers = modifiers)
                    CartStore.save(context, cart)
                },
            )
        }
        entry<Route.Deals> {
            DealsScreen(onMerchantClick = { id -> backStack.add(Route.Restaurant(id)) })
        }
        entry<Route.Orders> {
            OrdersScreen(onTrackOrder = { id -> backStack.add(Route.OrderTracking(id)) })
        }
        entry<Route.OrderTracking> { route ->
            OrderTrackingScreen(orderId = route.orderId, onBack = { backStack.pop() })
        }
        entry<Route.Account> { AccountScreen() }
        entry<Route.Checkout> {
            CheckoutScreen(
                items = cart,
                onBack = { backStack.pop() },
                onOrderPlaced = {
                    cart.clear()
                    CartStore.clear(context)
                    backStack.reset(Route.Orders)
                },
            )
        }
        entry<Route.Restaurant> { route ->
            RestaurantScreen(
                merchantId = route.id,
                onBack = { backStack.pop() },
                onAddToCart = { item -> cart.add(item); CartStore.save(context, cart) }
            )
        }
    }
}
