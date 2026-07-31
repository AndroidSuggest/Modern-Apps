package com.vayunmathur.fooddelivery

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.stripe.android.PaymentConfiguration
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconHome
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
import com.vayunmathur.fooddelivery.ui.AccountScreen
import com.vayunmathur.fooddelivery.ui.CartScreen
import com.vayunmathur.fooddelivery.ui.CheckoutScreen
import com.vayunmathur.fooddelivery.ui.HomeScreen
import com.vayunmathur.fooddelivery.ui.OrdersScreen
import com.vayunmathur.fooddelivery.ui.RestaurantScreen
import kotlinx.serialization.Serializable

sealed interface Route : NavKey {
    @Serializable data object Home : Route
    @Serializable data object Cart : Route
    @Serializable data object Orders : Route
    @Serializable data object Account : Route
    @Serializable data class Restaurant(val id: Int) : Route
    @Serializable data object Checkout : Route
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                FoodDeliveryApp()
            }
        }
    }
}

@Composable
private fun FoodDeliveryApp() {
    val context = LocalContext.current
    val backStack = rememberNavBackStack<Route>(Route.Home)
    val currentPage = backStack.backStack.last()
    val cart = remember { mutableStateListOf<CartItem>().also { it.addAll(CartStore.getAll(context)) } }

    val pages = listOf(
        BottomBarItem("Home", Route.Home) { IconHome() },
        BottomBarItem("Cart", Route.Cart) { IconShoppingCart() },
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
                onCheckout = { backStack.add(Route.Checkout) }
            )
        }
        entry<Route.Orders> { OrdersScreen() }
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
