/**
 * FederationDataModule.kt — data module
 *
 * Purpose: Hilt [dagger.Module] that binds the data-layer implementations of
 *          [UserRegionProvider] and [UserRoleProvider] into the DI graph.
 *
 *          These interfaces are defined in core-network ([FailoverInterceptor.kt]) and
 *          are implemented in the data module (which has access to Room's [UserDao]).
 *          core-network cannot depend on data, so the bindings live here.
 *
 * Bindings:
 *   - [UserRegionProviderImpl] → [UserRegionProvider]
 *   - [UserRoleProviderImpl]   → [UserRoleProvider]
 *
 * Architecture: data module — installs into [SingletonComponent].
 * Dependencies: Hilt, core-network (interfaces), data.federation (implementations)
 *
 * Requirements: 35.1, 35.2
 */
package com.aiassistant.data.di

import com.aiassistant.core.network.federation.UserRegionProvider
import com.aiassistant.core.network.federation.UserRoleProvider
import com.aiassistant.data.federation.UserRegionProviderImpl
import com.aiassistant.data.federation.UserRoleProviderImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class FederationDataModule {

    /**
     * Binds [UserRegionProviderImpl] to the [UserRegionProvider] interface consumed by
     * [com.aiassistant.core.network.federation.FailoverInterceptor].
     *
     * The implementation reads the current user's region from Room; see
     * [UserRegionProviderImpl] for details on the default fallback behaviour.
     */
    @Binds
    @Singleton
    abstract fun bindUserRegionProvider(impl: UserRegionProviderImpl): UserRegionProvider

    /**
     * Binds [UserRoleProviderImpl] to the [UserRoleProvider] interface consumed by
     * [com.aiassistant.core.network.federation.FailoverInterceptor].
     *
     * The implementation reads the current user's RBAC role from Room; falls back to
     * "user" when no authenticated user is present.
     */
    @Binds
    @Singleton
    abstract fun bindUserRoleProvider(impl: UserRoleProviderImpl): UserRoleProvider
}
