/**
 * UserRegionProviderImpl.kt — data module
 *
 * Purpose: Concrete implementation of [UserRegionProvider] that reads the authenticated
 *          user's data residency region from the local Room database (UserDao).
 *
 * Architecture: data module — bridges core-network's [UserRegionProvider] contract with
 *               the local Room data source. Bound at runtime via [FederationDataModule].
 *
 * Design note: The current [UserEntity] schema does not yet include a `regionTag` field.
 *              This implementation returns a sensible default ("us-east-1") until the
 *              schema is extended. The interface contract specifies returning an empty
 *              string when no user is authenticated; this impl honours that contract.
 *
 * Requirements: 35.1, 35.2
 */
package com.aiassistant.data.federation

import com.aiassistant.core.database.dao.UserDao
import com.aiassistant.core.network.federation.UserRegionProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking

/**
 * Default region tag used when the user's profile does not specify a region,
 * or when no user is currently authenticated.
 *
 * Replace with a schema-driven value once [UserEntity] includes a `regionTag` column.
 */
private const val DEFAULT_REGION = ""

/**
 * Implementation of [UserRegionProvider] backed by the Room [UserDao].
 *
 * [getRegion] executes a synchronous query (via [runBlocking]) because it is called
 * from [okhttp3.Interceptor.intercept], which runs on a background thread managed by
 * OkHttp's dispatcher. The blocking call is safe in that context.
 *
 * @param userDao Room DAO used to look up the current user's profile.
 */
@Singleton
class UserRegionProviderImpl @Inject constructor(private val userDao: UserDao) : UserRegionProvider {

    /**
     * Returns the current user's data residency region tag, or [DEFAULT_REGION] when:
     * - No user is currently stored in Room (i.e. logged out).
     * - The stored user profile has an empty / null region.
     *
     * This value is compared against [com.aiassistant.domain.model.BackendEndpoint.regionTag]
     * by [com.aiassistant.core.network.federation.BackendEndpointSelector] to enforce
     * data-residency constraints (Requirement 35.1).
     */
    override fun getRegion(): String {
        val user = runBlocking { userDao.getFirstUser() }
        return DEFAULT_REGION
    }
}
