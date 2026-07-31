package com.vayunmathur.fooddelivery.data

import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse<T>(
    val message: String = "",
    val data: T? = null,
)

@Serializable
data class MerchantsWrapper(
    val merchants: List<Merchant> = emptyList(),
)

@Serializable
data class Merchant(
    val id: Int = 0,
    val name: String = "",
    val addressStreet: String = "",
    val addressCity: String = "",
    val addressState: String = "",
    val addressZip: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val logoUrl: String = "",
    val imageUrl: String = "",
    val isOpen: Boolean = true,
    val isDeliveryEnabled: Boolean = true,
    val isPickupEnabled: Boolean = true,
    val closingTime: String = "",
    val nextOpenWindow: String = "",
    val storefrontAlias: String = "",
    val averageRating: Double? = null,
    val totalRatings: Int? = null,
    val rewardsPercentage: Double? = null,
    val merchantTags: List<String> = emptyList(),
    val brandColor: String? = null,
    val freeDeliveryThreshold: Int = 0,
    val doordashMarkup: Int? = null,
    val doordashMarkupComparison: Int? = null,
    val items: List<MerchantItem> = emptyList(),
    val brand: Brand? = null,
    val distance: Double? = null,
    val sortOrder: Int? = null,
) {
    val address: String get() = buildString {
        if (addressStreet.isNotEmpty()) append(addressStreet)
        if (addressCity.isNotEmpty()) append(", $addressCity")
        if (addressState.isNotEmpty()) append(", $addressState")
    }
    val displayImage: String get() = imageUrl.ifEmpty { brand?.imageUrl ?: "" }
    val displayLogo: String get() = logoUrl.ifEmpty { brand?.logoUrl ?: "" }
    val freeDeliveryThresholdDollars: Double get() = freeDeliveryThreshold / 100.0
    val displayRating: Double get() = averageRating ?: 0.0
    val displayTotalRatings: Int get() = totalRatings ?: 0
    val displayRewardsPercentage: Double get() = rewardsPercentage ?: 0.0
}

@Serializable
data class Brand(
    val logoUrl: String? = null,
    val imageUrl: String? = null,
    val showOnApp: Boolean = false,
)

@Serializable
data class MerchantItem(
    val id: Int = 0,
    val name: String = "",
    val price: Int = 0,
    val imageUrl: String? = null,
    val thumbnailUrl: String? = null,
) {
    val priceDollars: Double get() = price / 100.0
}

@Serializable
data class MerchantDetail(
    val id: Int = 0,
    val name: String = "",
    val addressStreet: String = "",
    val addressCity: String = "",
    val addressState: String = "",
    val addressZip: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val logoUrl: String = "",
    val imageUrl: String = "",
    val isOpen: Boolean = true,
    val isDeliveryEnabled: Boolean = true,
    val isPickupEnabled: Boolean = true,
    val closingTime: String = "",
    val nextOpenWindow: String = "",
    val averageRating: Double? = null,
    val totalRatings: Int? = null,
    val rewardsPercentage: Double? = null,
    val merchantTags: List<String> = emptyList(),
    val brandColor: String? = null,
    val freeDeliveryThreshold: Int = 0,
    val doordashMarkup: Int? = null,
    val doordashMarkupComparison: Int? = null,
    val doordashUrl: String? = null,
    val categories: List<MenuCategory> = emptyList(),
    val items: List<MenuItem> = emptyList(),
    val deals: List<Deal> = emptyList(),
    val promotions: List<Promotion> = emptyList(),
)

@Serializable
data class MenuCategory(
    val id: Int = 0,
    val name: String = "",
    val sortOrder: Int = 0,
    val isActive: Boolean = true,
    val itemIds: List<Int> = emptyList(),
    val merchantId: Int = 0,
)

@Serializable
data class MenuItem(
    val id: Int = 0,
    val name: String = "",
    val description: String = "",
    val price: Int = 0,
    val isAvailable: Boolean = true,
    val isInStock: Boolean = true,
    val imageUrl: String? = null,
    val thumbnailUrl: String? = null,
    val category: String = "",
    val merchantId: Int = 0,
    val doordashPrice: Int? = null,
    val ubereatsPrice: Int? = null,
    val isRecommended: Boolean = false,
    val isCatering: Boolean = false,
    val modifierGroups: List<ModifierGroup> = emptyList(),
    val tags: List<String> = emptyList(),
) {
    val priceDollars: Double get() = price / 100.0
    val displayImage: String get() = imageUrl ?: thumbnailUrl ?: ""
    val doordashPriceDollars: Double? get() = doordashPrice?.let { it / 100.0 }
}

@Serializable
data class ModifierGroup(
    val id: Int = 0,
    val name: String = "",
    val required: Boolean = false,
    val minSelections: Int = 0,
    val maxSelections: Int = 1,
    val modifiers: List<Modifier> = emptyList(),
)

@Serializable
data class Modifier(
    val id: Int = 0,
    val name: String = "",
    val price: Int = 0,
) {
    val priceDollars: Double get() = price / 100.0
}

@Serializable
data class Deal(
    val id: Int = 0,
    val title: String = "",
    val description: String = "",
    val image: String = "",
    val merchantId: Int = 0,
    val merchantName: String = "",
    val discountPercent: Double = 0.0,
    val discountAmount: Int = 0,
    val isActive: Boolean = true,
) {
    val discountAmountDollars: Double get() = discountAmount / 100.0
}

@Serializable
data class Promotion(
    val id: Int = 0,
    val name: String = "",
    val description: String = "",
)

@Serializable
data class OrderMerchant(
    val id: Int = 0,
    val name: String = "",
    val imageUrl: String? = null,
    val logoUrl: String? = null,
)

@Serializable
data class Order(
    val id: Int = 0,
    val createdAt: String? = null,
    val channel: String? = null,
    val merchant: OrderMerchant? = null,
    val orderItems: List<OrderItem> = emptyList(),
    val foodTotal: Int = 0,
    val deliveryFee: Int = 0,
    val fees: Int? = null,
    val taxes: Int = 0,
    val tips: Int = 0,
    val promoCode: String? = null,
    val deliveredAt: String? = null,
    val pickedupAt: String? = null,
    val dueAt: String? = null,
) {
    val displayTotal: Double get() = (foodTotal + (fees ?: 0) + taxes + deliveryFee + tips) / 100.0
    val foodTotalDollars: Double get() = foodTotal / 100.0
    val taxesDollars: Double get() = taxes / 100.0
    val tipsDollars: Double get() = tips / 100.0
    val deliveryFeeDollars: Double get() = deliveryFee / 100.0
    val isDelivery: Boolean get() = channel == "ORDER_CHANNEL_STOREFRONT_DELIVERY"
    val isDone: Boolean get() = if (isDelivery) deliveredAt != null else pickedupAt != null
    val displayStatus: String get() = when {
        isDone && isDelivery -> "Delivered"
        isDone -> "Picked up"
        else -> "In progress"
    }
}

@Serializable
data class OrderItem(
    val id: Int = 0,
    val name: String? = null,
    val quantity: Int = 1,
    val price: Int = 0,
    val modifiers: List<OrderItemModifier> = emptyList(),
    val specialInstructions: String? = null,
) {
    val priceDollars: Double get() = price / 100.0
}

@Serializable
data class OrderItemModifier(
    val name: String? = null,
    val price: Int = 0,
    val quantity: Int = 1,
    val modifierGroupName: String? = null,
)

@Serializable
data class SavedAddress(
    val id: String = "",
    val label: String = "",
    val addressStreet: String = "",
    val addressCity: String = "",
    val addressState: String = "",
    val addressZip: String = "",
    val aptUnit: String = "",
    val gateCode: String = "",
    val deliveryInstructions: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val isDefault: Boolean = false,
)

@Serializable
data class CartItem(
    val menuItem: MenuItem,
    val quantity: Int = 1,
    val selectedModifiers: List<Modifier> = emptyList(),
    val merchantId: Int = 0,
    val merchantName: String = "",
) {
    val totalPrice: Double
        get() = (menuItem.priceDollars + selectedModifiers.sumOf { it.priceDollars }) * quantity
}

@Serializable
data class Customer(
    val id: Int = 0,
    val email: String = "",
    val phone: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val rewardPoints: Int = 0,
    val totalCustomerSavings: Int = 0,
) {
    val displayName: String get() = "$firstName $lastName".trim()
    val savingsDollars: Double get() = totalCustomerSavings / 100.0
}

@Serializable
data class AuthToken(
    val access_token: String = "",
    val refresh_token: String = "",
    val token_type: String = "",
    val expires_in: Long = 0,
)

@Serializable
data class Reward(
    val id: Int = 0,
    val title: String = "",
    val description: String = "",
    val pointsRequired: Int = 0,
    val merchantName: String = "",
)

@Serializable
data class CustomerSavings(
    val totalCustomerSavings: Int = 0,
    val totalMerchantSavings: Int = 0,
) {
    val customerSavingsDollars: Double get() = totalCustomerSavings / 100.0
}

@Serializable
data class CheckoutModifier(
    val modifierId: Int = 0,
    val modifierGroupId: Int = 0,
    val name: String = "",
    val price: Int = 0,
    val quantity: Int = 1,
)

@Serializable
data class CheckoutCartItem(
    val itemId: Int,
    val quantity: Int,
    val specialInstructions: String? = null,
    val modifiers: List<CheckoutModifier> = emptyList(),
)

@Serializable
data class CheckoutAddress(
    val addressStreet: String = "",
    val addressCity: String = "",
    val addressState: String = "",
    val addressZip: String = "",
    val addressUnit: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
)

@Serializable
data class CheckoutRequest(
    val cartItems: List<CheckoutCartItem>,
    val address: CheckoutAddress? = null,
    val isPickup: Boolean = false,
    val inStore: Boolean = false,
    val tips: Int = 0,
    val promoCode: String? = null,
    val deliveryInstructions: String? = null,
    val gateCode: String? = null,
    val leaveAtDoor: Boolean = false,
    val isMobile: Boolean = true,
)

@Serializable
data class CheckoutResponse(
    val clientSecret: String = "",
    val order: Order? = null,
    val serviceable: kotlinx.serialization.json.JsonElement? = null,
) {
    val isServiceable: Boolean get() = serviceable != null &&
        serviceable !is kotlinx.serialization.json.JsonPrimitive
}
