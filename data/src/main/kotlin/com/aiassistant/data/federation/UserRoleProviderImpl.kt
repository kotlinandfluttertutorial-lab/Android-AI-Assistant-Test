/**
 * UserRoleProviderImpl.kt — data module
 *
 * Purpose: Concrete implementation of [UserRoleProvider] that reads the authenticated
 *          user's RBAC role from the local Room database (UserDao).
 *
 * Architecture: data module — bridges core-network's [UserRoleProvider] contract with
 *               the local Room data source. Bound at runtime via [FederationDataModule].
 *
 * Requirements: 35.2, 35.4
 */
package com.aiassistant.data.federation

import com.aiassistant.core.database.dao.UserDao
import com.aiassistant.core.network.federation.UserRoleProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking

/**
 * Default role returned when no authenticated user is found in Room.
 * Falls back to "user" (the lowest-privilege role), matching the [UserRole.USER] value.
 */
private const val DEFAULT_ROLE = "user"

/**
 * Implementation of [UserRoleProvider] backed by the Room [UserDao].
 *
 * [getRole] executes a synchronous query (via [runBlocking]) because it is called
 * from [okhttp3.Interceptor.intercept], which runs on a background thread managed by
 * OkHttp's dispatcher. The blocking call is safe in that context.
 *
 * @param userDao Room DAO used to look up the current user's profile and role.
 */
@Singleton
class UserRoleProviderImpl @Inject constructor(private val userDao: UserDao) : UserRoleProvider {

    /**
     * Returns the RBAC role string of the currently authenticated user, or [DEFAULT_ROLE]
     * when no user is found in Room (i.e. logged out).
     *
     * This value is matched against [com.aiassistant.domain.model.BackendEndpoint.allowedRoles]
     * by [com.aiassistant.core.network.federation.BackendEndpointSelector] to enforce RBAC
     * constraints (Requirement 35.2).
     *
     * @return Role value such as "user", "premium", or "admin".
     */
    override fun getRole(): String {
        val user = runBlocking { userDao.getFirstUser() }
        return user?.role ?: DEFAULT_ROLE
    }
}
