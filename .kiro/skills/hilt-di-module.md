# Skill: Hilt Dependency Injection Module

## Purpose
Write correct Hilt `@Module` objects for the Android AI Assistant project, covering the
three most common DI scenarios: providing third-party/framework objects, binding
interfaces to implementations, and scoping singletons vs ViewModel-scoped dependencies.

## When to Use
- Adding a new singleton service (e.g. a new API client, a new manager class)
- Binding a new interface to its `Impl` (Repository, Engine, Storage, etc.)
- Adding ViewModel-scoped dependencies that must not be singletons
- Providing `@Named` qualifiers when multiple bindings share the same type

---

## Project Hilt Setup

| Item | Value |
|---|---|
| Hilt version | 2.52 |
| KSP codegen | `ksp(libs.hilt.android.compiler)` |
| Application class | `AIAssistantApplication` (`@HiltAndroidApp`) |
| Activity | `MainActivity` (`@AndroidEntryPoint`) |
| ViewModel annotation | `@HiltViewModel` + `@Inject constructor(...)` |
| Test runner | `HiltTestRunner` |

---

## Component Hierarchy (choose the right `@InstallIn`)

```
SingletonComponent          ← app-wide singletons (OkHttpClient, AppDatabase, SecureStorage)
    └── ActivityRetainedComponent
            └── ViewModelComponent       ← per-ViewModel; destroyed with ViewModel
                    └── ActivityComponent
                            └── FragmentComponent
                                    └── ViewComponent
```

**Rule of thumb:**
- Network clients, databases, repositories → `SingletonComponent`
- Use cases, ViewModel-specific helpers → `ViewModelComponent`
- UI helpers that need `Context` but must not be singletons → `ActivityComponent`

---

## Pattern 1 — `@Provides` for third-party / framework objects

Use when you cannot annotate the class constructor with `@Inject` (e.g. `OkHttpClient`,
`Retrofit`, `AppDatabase`, `SharedPreferences`).

```kotlin
// core-network/src/main/kotlin/com/aiassistant/core/network/di/NetworkModule.kt
package com.aiassistant.core.network.di

import com.aiassistant.core.network.AuthInterceptor
import com.aiassistant.core.network.CertificatePinningInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        certPinningInterceptor: CertificatePinningInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(certPinningInterceptor)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                    else HttpLoggingInterceptor.Level.NONE
        })
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
}
```

---

## Pattern 2 — `@Binds` for interface → implementation

Use when you own the implementation class and it already has `@Inject constructor(...)`.
`@Binds` generates zero boilerplate at compile time (preferred over `@Provides` for
interface binding).

```kotlin
// core-security/src/main/kotlin/com/aiassistant/core/security/di/SecurityModule.kt
package com.aiassistant.core.security.di

import com.aiassistant.core.security.SecureStorage
import com.aiassistant.core.security.SecureStorageImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {

    // @Binds must be in an abstract class (not object).
    // The function is abstract — Hilt generates the body.
    @Binds
    @Singleton
    abstract fun bindSecureStorage(impl: SecureStorageImpl): SecureStorage
}
```

> The same pattern is used for `AIStreamClient` → `AIStreamClientImpl`,
> `OnDeviceEngine` → `StubOnDeviceEngine`, and all 17 Repository interfaces.

---

## Pattern 3 — `@Binds` + `@Named` qualifier

When two bindings share the same return type (e.g. two `OkHttpClient` instances —
one with TLS pinning, one without for image loading):

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object HttpModule {

    @Provides
    @Singleton
    @Named("pinned")
    fun providePinnedClient(certPin: CertificatePinningInterceptor): OkHttpClient = ...

    @Provides
    @Singleton
    @Named("image")
    fun provideImageClient(): OkHttpClient = OkHttpClient.Builder().build()
}

// Consumption at injection site:
class SomeRepo @Inject constructor(
    @Named("pinned") private val httpClient: OkHttpClient,
)
```

---

## Pattern 4 — ViewModel-scoped `@Provides`

Dependencies that must be recreated per ViewModel (e.g. a stateful helper) should be
installed in `ViewModelComponent`. They are automatically destroyed when the ViewModel
is cleared.

```kotlin
@Module
@InstallIn(ViewModelComponent::class)
object ChatViewModelModule {

    @Provides
    @ViewModelScoped
    fun provideStreamingBuffer(): StreamingBuffer = StreamingBuffer()
}
```

---

## Pattern 5 — `@EntryPoint` for non-Hilt injection sites

Use when you need to inject into a class that Hilt doesn't manage (e.g. a
`FirebaseMessagingService`, a `BroadcastReceiver`, or a custom `ContentProvider`).

```kotlin
@EntryPoint
@InstallIn(SingletonComponent::class)
interface FcmEntryPoint {
    fun secureStorage(): SecureStorage
}

// In your non-Hilt class:
class AiAssistantFcmService : FirebaseMessagingService() {
    private val secureStorage by lazy {
        EntryPointAccessors
            .fromApplication(applicationContext, FcmEntryPoint::class.java)
            .secureStorage()
    }
}
```

---

## Where Each Module Lives

| Module file location | Installed in | Provides |
|---|---|---|
| `core-network/di/NetworkModule.kt` | `SingletonComponent` | OkHttp, Retrofit, JSON |
| `core-network/di/NetworkModule.kt` | `SingletonComponent` | `CertificatePinningInterceptor` |
| `core-database/di/DatabaseModule.kt` | `SingletonComponent` | `AppDatabase`, all DAOs |
| `core-ai/di/AiModule.kt` | `SingletonComponent` | `AIStreamClient` binding |
| `core-security/di/SecurityModule.kt` | `SingletonComponent` | `SecureStorage` binding |
| `core-common/di/DispatcherModule.kt` | `SingletonComponent` | `DispatcherProvider` binding |
| `data/di/RepositoryModule.kt` | `SingletonComponent` | all 17 Repository bindings |
| `feature-*/di/*Module.kt` | `ViewModelComponent` | feature-specific helpers |

---

## Common Mistakes

| Mistake | Fix |
|---|---|
| Using `@Provides` in an abstract class | `@Provides` needs a concrete `object`; `@Binds` needs an `abstract class` |
| Using `object` with `@Binds` | Change `object` to `abstract class` |
| Not scoping a `@Provides` that should be a singleton | Add `@Singleton` (or appropriate scope annotation) |
| Injecting a `@Singleton` into a `@ViewModelScoped` module | The injection point must be scoped ≥ the dependency — widen the scope or create a `@ViewModelScoped` wrapper |
| Cross-module Hilt errors at KSP time | Run `./gradlew :app:kspDebugKotlin` to see the full Hilt error tree |
| Circular dependency | Extract the circular slice into a third class/interface |

---

## Checklist

- [ ] `@Module` + `@InstallIn` present on every module
- [ ] `@Singleton` (or correct scope) on every `@Provides` / `@Binds` that should not be recreated
- [ ] `@Binds` used (not `@Provides`) for interface → concrete class bindings you own
- [ ] Abstract class used when `@Binds` is present
- [ ] `@Named` qualifier added when the same type is provided more than once
- [ ] Module placed in the correct Gradle module (not in `:app` unless it truly is app-level)
- [ ] KSP generates without errors (`./gradlew :app:kspDebugKotlin`)
- [ ] No `@Inject` on a class that also has a `@Provides` for the same type
