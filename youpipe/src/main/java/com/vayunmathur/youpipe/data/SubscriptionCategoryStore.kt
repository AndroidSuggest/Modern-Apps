package com.vayunmathur.youpipe.data

/**
 * Subscription-category writes, split from [SubscriptionRepository] (TooManyFunctions cap).
 *
 * Same package and module; behavior identical. Access via
 * [SubscriptionRepository.categoryStore].
 */
internal class SubscriptionCategoryStore(
    private val subscriptionCategoryDao: SubscriptionCategoryDao,
) {
    suspend fun replaceCategory(originalCategoryName: String?, categoryName: String, ids: List<Long>) =
        subscriptionCategoryDao.replaceCategory(originalCategoryName, categoryName, ids)

    suspend fun deleteCategory(categoryName: String) = subscriptionCategoryDao.deleteCategory(categoryName)

    suspend fun upsertSubscriptionCategories(items: List<SubscriptionCategory>) =
        subscriptionCategoryDao.upsertAll(items)
}
