# Yfuse 计划一:基础骨架 + 服务器连接 + 登录 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 搭起 Compose Multiplatform(Android target)工程骨架,实现「输入服务器地址 → 校验 → 登录 → 进入首页并显示媒体库名列表」的完整纵向切片。

**Architecture:** 三层——UI(Compose + Decompose 组件)/ 表现层(MVIKotlin Store)/ 数据层(EmbyRepository + Ktor)。单向数据流 MVI。第一版仅 Android target;播放器等平台实现留待后续计划。

**Tech Stack:** Kotlin, Compose Multiplatform, Decompose, MVIKotlin, Ktor Client, kotlinx.serialization, Koin, multiplatform-settings, Turbine, Ktor MockEngine。

## Global Constraints

- Kotlin `2.1.21`;AGP `8.7.3`;JDK 17;`compileSdk 35` / `minSdk 26` / `targetSdk 35`。
- 依赖版本(集中在 `gradle/libs.versions.toml`,可整体上调但需同步):Compose Multiplatform `1.7.3`,Decompose `3.2.2`,MVIKotlin `4.2.0`,Ktor `3.0.3`,kotlinx-serialization `1.7.3`,kotlinx-coroutines `1.9.0`,Koin `4.0.0`,multiplatform-settings `1.2.0`,Turbine `1.2.0`,kotlin `test` 与 coroutines-test 匹配上述版本。
- 包名根:`com.yfuse`。所有共享代码放 `composeApp/src/commonMain`;Android 专有放 `androidMain`;测试放 `commonTest`。
- 网络层必须安装 Ktor `ContentEncoding`(gzip),否则 Emby 响应为乱码。
- Emby 认证头格式:`X-Emby-Authorization: MediaBrowser Client="Yfuse", Device="<model>", DeviceId="<uuid>", Version="<appVer>"`;后续请求带 `X-Emby-Token: <AccessToken>`。
- 服务器地址与凭据禁止硬编码进版本库;测试用 MockEngine,不打真实服务器。
- 每个可测试单元遵循 TDD;每个 Task 结束提交一次。

---

## 文件结构

```
composeApp/src/commonMain/kotlin/com/yfuse/
  core/model/        Server.kt, User.kt, MediaLibrary.kt
  core/network/      EmbyAuth.kt(认证头构造), EmbyError.kt, HttpClientFactory.kt
  core/data/         dto/AuthDto.kt, dto/ViewsDto.kt,
                     SessionManager.kt, EmbyRepository.kt
  feature/server/    ServerStore.kt, ServerComponent.kt, ServerScreen.kt
  feature/login/     LoginStore.kt, LoginComponent.kt, LoginScreen.kt
  feature/home/      HomeStore.kt, HomeComponent.kt, HomeScreen.kt(本计划仅显示库名)
  app/               RootComponent.kt, App.kt
  di/                AppModule.kt
composeApp/src/androidMain/kotlin/com/yfuse/
  MainActivity.kt, Platform.android.kt(deviceModel/deviceId 实现)
composeApp/src/commonMain/kotlin/com/yfuse/
  Platform.kt(expect: deviceModel(), deviceId())
composeApp/src/commonTest/kotlin/com/yfuse/
  core/data/EmbyRepositoryTest.kt, core/data/SessionManagerTest.kt
  feature/login/LoginStoreTest.kt, feature/server/ServerStoreTest.kt
```

---

## Task 1: 工程脚手架与版本目录

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`
- Create: `composeApp/build.gradle.kts`
- Create: `composeApp/src/androidMain/AndroidManifest.xml`
- Create: `composeApp/src/androidMain/kotlin/com/yfuse/MainActivity.kt`
- Create: `composeApp/src/commonMain/kotlin/com/yfuse/app/App.kt`(临时占位 `Text("Yfuse")`)

**Interfaces:**
- Produces: 可编译运行的空 Compose Multiplatform Android 应用;`libs` 版本目录别名供后续任务引用。

- [ ] **Step 1: 生成骨架**

用 Kotlin Multiplatform Wizard 的等价结构手写。`settings.gradle.kts`:

```kotlin
rootProject.name = "Yfuse"
include(":composeApp")
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
```

`gradle/libs.versions.toml`(节选核心别名,版本见 Global Constraints):

```toml
[versions]
kotlin = "2.1.21"
agp = "8.7.3"
compose = "1.7.3"
decompose = "3.2.2"
mvikotlin = "4.2.0"
ktor = "3.0.3"
serialization = "1.7.3"
coroutines = "1.9.0"
koin = "4.0.0"
settings = "1.2.0"
turbine = "1.2.0"

[libraries]
decompose = { module = "com.arkivanov.decompose:decompose", version.ref = "decompose" }
decompose-compose = { module = "com.arkivanov.decompose:extensions-compose", version.ref = "decompose" }
mvikotlin = { module = "com.arkivanov.mvikotlin:mvikotlin", version.ref = "mvikotlin" }
mvikotlin-main = { module = "com.arkivanov.mvikotlin:mvikotlin-main", version.ref = "mvikotlin" }
mvikotlin-coroutines = { module = "com.arkivanov.mvikotlin:mvikotlin-extensions-coroutines", version.ref = "mvikotlin" }
ktor-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-cio = { module = "io.ktor:ktor-client-cio", version.ref = "ktor" }
ktor-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-encoding = { module = "io.ktor:ktor-client-encoding", version.ref = "ktor" }
ktor-mock = { module = "io.ktor:ktor-client-mock", version.ref = "ktor" }
serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
koin-core = { module = "io.insert-koin:koin-core", version.ref = "koin" }
settings = { module = "com.russhwolf:multiplatform-settings", version.ref = "settings" }
settings-test = { module = "com.russhwolf:multiplatform-settings-test", version.ref = "settings" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }

[plugins]
multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
android-application = { id = "com.android.application", version.ref = "agp" }
compose = { id = "org.jetbrains.compose", version.ref = "compose" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

`composeApp/build.gradle.kts` 配置 `androidTarget()`、compose、依赖引用上述别名(commonMain 加 decompose/mvikotlin/ktor-core/ktor-content-negotiation/ktor-json/ktor-encoding/serialization-json/coroutines-core/koin-core/settings;androidMain 加 ktor-cio + androidx.activity.compose;commonTest 加 kotlin-test/coroutines-test/turbine/ktor-mock/settings-test)。

- [ ] **Step 2: 验证编译**

Run: `./gradlew :composeApp:assembleDebug`
Expected: BUILD SUCCESSFUL,生成 APK。

- [ ] **Step 3: 提交**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradle/ composeApp/
git commit -m "chore: scaffold Compose Multiplatform Android project"
```

---

## Task 2: 领域模型与平台 expect/actual

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/yfuse/core/model/Server.kt`
- Create: `.../core/model/User.kt`
- Create: `.../core/model/MediaLibrary.kt`
- Create: `.../Platform.kt`(expect)
- Create: `composeApp/src/androidMain/kotlin/com/yfuse/Platform.android.kt`(actual)

**Interfaces:**
- Produces:
  - `data class Server(val baseUrl: String)`
  - `data class User(val id: String, val name: String, val accessToken: String)`
  - `data class MediaLibrary(val id: String, val name: String, val collectionType: String?)`
  - `expect fun deviceModel(): String` / `expect fun deviceId(): String`

- [ ] **Step 1: 写模型与 expect**

```kotlin
// Server.kt
package com.yfuse.core.model
data class Server(val baseUrl: String)

// User.kt
package com.yfuse.core.model
data class User(val id: String, val name: String, val accessToken: String)

// MediaLibrary.kt
package com.yfuse.core.model
data class MediaLibrary(val id: String, val name: String, val collectionType: String?)

// Platform.kt
package com.yfuse
expect fun deviceModel(): String
expect fun deviceId(): String
```

- [ ] **Step 2: 写 Android actual**

```kotlin
// Platform.android.kt
package com.yfuse
import android.os.Build
import java.util.UUID
actual fun deviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}"
private val stableId = UUID.randomUUID().toString()  // 计划三前用内存值;后续可持久化
actual fun deviceId(): String = stableId
```

- [ ] **Step 3: 验证编译**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交**

```bash
git add composeApp/src
git commit -m "feat: add core domain models and platform expect/actual"
```

---

## Task 3: EmbyError 与认证头构造

**Files:**
- Create: `.../core/network/EmbyError.kt`
- Create: `.../core/network/EmbyAuth.kt`
- Test: `composeApp/src/commonTest/kotlin/com/yfuse/core/network/EmbyAuthTest.kt`

**Interfaces:**
- Consumes: `deviceModel()`, `deviceId()`(Task 2)。
- Produces:
  - `sealed interface EmbyError { data object Network; data object Unauthorized; data class Server(val code: Int); data class Unknown(val message: String) }`
  - `fun buildAuthHeader(appVersion: String = "0.1.0"): String`

- [ ] **Step 1: 写失败测试**

```kotlin
// EmbyAuthTest.kt
package com.yfuse.core.network
import kotlin.test.Test
import kotlin.test.assertTrue

class EmbyAuthTest {
    @Test fun header_contains_required_fields() {
        val h = buildAuthHeader("1.2.3")
        assertTrue(h.startsWith("MediaBrowser "))
        assertTrue(h.contains("Client=\"Yfuse\""))
        assertTrue(h.contains("DeviceId="))
        assertTrue(h.contains("Version=\"1.2.3\""))
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.yfuse.core.network.EmbyAuthTest"`
Expected: FAIL —— `buildAuthHeader` 未定义。

- [ ] **Step 3: 实现**

```kotlin
// EmbyError.kt
package com.yfuse.core.network
sealed interface EmbyError {
    data object Network : EmbyError
    data object Unauthorized : EmbyError
    data class Server(val code: Int) : EmbyError
    data class Unknown(val message: String) : EmbyError
}

// EmbyAuth.kt
package com.yfuse.core.network
import com.yfuse.deviceId
import com.yfuse.deviceModel
fun buildAuthHeader(appVersion: String = "0.1.0"): String =
    "MediaBrowser Client=\"Yfuse\", Device=\"${deviceModel()}\", " +
        "DeviceId=\"${deviceId()}\", Version=\"$appVersion\""
```

- [ ] **Step 4: 运行验证通过**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.yfuse.core.network.EmbyAuthTest"`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add composeApp/src
git commit -m "feat: add EmbyError and auth header builder"
```

---

## Task 4: HttpClient 工厂(gzip + JSON + token 注入)

**Files:**
- Create: `.../core/network/HttpClientFactory.kt`

**Interfaces:**
- Consumes: `buildAuthHeader()`(Task 3)。
- Produces: `fun createEmbyClient(engine: HttpClientEngine? = null, tokenProvider: () -> String?): HttpClient`
  —— 安装 `ContentEncoding(gzip)`、`ContentNegotiation(json{ignoreUnknownKeys=true})`;`defaultRequest` 注入 `X-Emby-Authorization` 头,若 `tokenProvider()` 非空则注入 `X-Emby-Token`。

- [ ] **Step 1: 实现(无独立单测,由 Task 6 的 Repository 测试间接覆盖)**

```kotlin
// HttpClientFactory.kt
package com.yfuse.core.network
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

fun createEmbyClient(
    engine: HttpClientEngine? = null,
    tokenProvider: () -> String?,
): HttpClient {
    val config: HttpClient.() -> Unit = {}
    val block: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
        install(ContentEncoding) { gzip() }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
        defaultRequest {
            header("X-Emby-Authorization", buildAuthHeader())
            tokenProvider()?.let { header("X-Emby-Token", it) }
        }
    }
    return if (engine != null) HttpClient(engine, block) else HttpClient(CIO, block)
}
```

- [ ] **Step 2: 验证编译**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 提交**

```bash
git add composeApp/src
git commit -m "feat: add Ktor HttpClient factory with gzip and token injection"
```

---

## Task 5: SessionManager(会话持久化)

**Files:**
- Create: `.../core/data/SessionManager.kt`
- Test: `.../commonTest/.../core/data/SessionManagerTest.kt`

**Interfaces:**
- Produces: `class SessionManager(private val settings: Settings)`,方法:
  - `fun save(baseUrl: String, token: String, userId: String)`
  - `fun baseUrl(): String?` / `fun token(): String?` / `fun userId(): String?`
  - `fun hasSession(): Boolean`(三者皆非空)
  - `fun clear()`

- [ ] **Step 1: 写失败测试**

```kotlin
// SessionManagerTest.kt
package com.yfuse.core.data
import com.russhwolf.settings.MapSettings
import kotlin.test.*

class SessionManagerTest {
    @Test fun save_then_read_roundtrip() {
        val sm = SessionManager(MapSettings())
        assertFalse(sm.hasSession())
        sm.save("http://h:1", "tok", "uid")
        assertTrue(sm.hasSession())
        assertEquals("http://h:1", sm.baseUrl())
        assertEquals("tok", sm.token())
        assertEquals("uid", sm.userId())
    }
    @Test fun clear_removes_session() {
        val sm = SessionManager(MapSettings())
        sm.save("http://h:1", "tok", "uid")
        sm.clear()
        assertFalse(sm.hasSession())
        assertNull(sm.token())
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.yfuse.core.data.SessionManagerTest"`
Expected: FAIL —— `SessionManager` 未定义。

- [ ] **Step 3: 实现**

```kotlin
// SessionManager.kt
package com.yfuse.core.data
import com.russhwolf.settings.Settings

class SessionManager(private val settings: Settings) {
    private companion object { const val URL="url"; const val TOK="token"; const val UID="uid" }
    fun save(baseUrl: String, token: String, userId: String) {
        settings.putString(URL, baseUrl); settings.putString(TOK, token); settings.putString(UID, userId)
    }
    fun baseUrl() = settings.getStringOrNull(URL)
    fun token() = settings.getStringOrNull(TOK)
    fun userId() = settings.getStringOrNull(UID)
    fun hasSession() = baseUrl() != null && token() != null && userId() != null
    fun clear() { settings.remove(URL); settings.remove(TOK); settings.remove(UID) }
}
```

- [ ] **Step 4: 运行验证通过**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.yfuse.core.data.SessionManagerTest"`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add composeApp/src
git commit -m "feat: add SessionManager with multiplatform-settings"
```

---

## Task 6: EmbyRepository(DTO + 连接校验 + 登录 + 库列表)

**Files:**
- Create: `.../core/data/dto/AuthDto.kt`, `.../core/data/dto/ViewsDto.kt`
- Create: `.../core/data/EmbyRepository.kt`
- Test: `.../commonTest/.../core/data/EmbyRepositoryTest.kt`

**Interfaces:**
- Consumes: `createEmbyClient`(Task 4)、`EmbyError`(Task 3)、`SessionManager`(Task 5)、模型(Task 2)。
- Produces: `class EmbyRepository(...)` 方法(均返回 `Result<T>`,失败携带 `EmbyError`):
  - `suspend fun checkServer(baseUrl: String): Result<String>`（返回 ServerName;打 `/System/Info/Public`)
  - `suspend fun login(baseUrl: String, username: String, password: String): Result<User>`
  - `suspend fun libraries(): Result<List<MediaLibrary>>`（用已存会话打 `/Users/{uid}/Views`)

- [ ] **Step 1: 写 DTO**

```kotlin
// AuthDto.kt
package com.yfuse.core.data.dto
import kotlinx.serialization.Serializable
@Serializable data class PublicInfoDto(val ServerName: String? = null, val Version: String? = null)
@Serializable data class AuthRequestDto(val Username: String, val Pw: String)
@Serializable data class AuthResultDto(val AccessToken: String, val User: AuthUserDto)
@Serializable data class AuthUserDto(val Id: String, val Name: String)

// ViewsDto.kt
package com.yfuse.core.data.dto
import kotlinx.serialization.Serializable
@Serializable data class ViewsDto(val Items: List<ViewItemDto> = emptyList())
@Serializable data class ViewItemDto(val Id: String, val Name: String, val CollectionType: String? = null)
```

- [ ] **Step 2: 写失败测试(MockEngine 驱动)**

```kotlin
// EmbyRepositoryTest.kt
package com.yfuse.core.data
import com.russhwolf.settings.MapSettings
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.createEmbyClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class EmbyRepositoryTest {
    private fun repo(handler: MockRequestHandler): Pair<EmbyRepository, SessionManager> {
        val session = SessionManager(MapSettings())
        val engine = MockEngine(handler)
        val client = createEmbyClient(engine) { session.token() }
        return EmbyRepository(client, session) to session
    }
    private fun ok(json: String) = json

    @Test fun login_success_saves_session() = runTest {
        val (r, s) = repo { req ->
            respond(
                content = ByteReadChannel("""{"AccessToken":"tok","User":{"Id":"u1","Name":"zhuiyun"}}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val res = r.login("http://h:1", "zhuiyun", "123456")
        assertTrue(res.isSuccess)
        assertEquals("tok", res.getOrThrow().accessToken)
        assertTrue(s.hasSession())
    }

    @Test fun login_401_returns_unauthorized() = runTest {
        val (r, _) = repo { respond("", HttpStatusCode.Unauthorized) }
        val res = r.login("http://h:1", "x", "y")
        assertTrue(res.isFailure)
        assertEquals(EmbyError.Unauthorized, res.exceptionOrNull()?.let { (it as EmbyErrorException).error })
    }

    @Test fun libraries_parses_items() = runTest {
        val (r, s) = repo {
            respond(
                content = ByteReadChannel("""{"Items":[{"Id":"1","Name":"电影","CollectionType":"movies"}]}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        s.save("http://h:1", "tok", "u1")
        val res = r.libraries()
        assertTrue(res.isSuccess)
        assertEquals("电影", res.getOrThrow().first().name)
    }
}
```

- [ ] **Step 3: 运行验证失败**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.yfuse.core.data.EmbyRepositoryTest"`
Expected: FAIL —— `EmbyRepository` / `EmbyErrorException` 未定义。

- [ ] **Step 4: 实现**

```kotlin
// EmbyRepository.kt
package com.yfuse.core.data
import com.yfuse.core.data.dto.*
import com.yfuse.core.model.MediaLibrary
import com.yfuse.core.model.User
import com.yfuse.core.network.EmbyError
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.*
import io.ktor.http.*

class EmbyErrorException(val error: EmbyError) : Exception()

class EmbyRepository(
    private val client: HttpClient,
    private val session: SessionManager,
) {
    private fun map(e: Throwable): EmbyErrorException = when (e) {
        is ResponseException -> when (e.response.status.value) {
            401 -> EmbyErrorException(EmbyError.Unauthorized)
            in 500..599 -> EmbyErrorException(EmbyError.Server(e.response.status.value))
            else -> EmbyErrorException(EmbyError.Unknown(e.message ?: "http"))
        }
        else -> EmbyErrorException(EmbyError.Network)
    }

    suspend fun checkServer(baseUrl: String): Result<String> = runCatching {
        val info: PublicInfoDto = client.get("$baseUrl/System/Info/Public").body()
        info.ServerName ?: "Emby"
    }.recoverError()

    suspend fun login(baseUrl: String, username: String, password: String): Result<User> = runCatching {
        val res: AuthResultDto = client.post("$baseUrl/Users/AuthenticateByName") {
            contentType(ContentType.Application.Json)
            setBody(AuthRequestDto(username, password))
        }.body()
        session.save(baseUrl, res.AccessToken, res.User.Id)
        User(res.User.Id, res.User.Name, res.AccessToken)
    }.recoverError()

    suspend fun libraries(): Result<List<MediaLibrary>> = runCatching {
        val base = session.baseUrl(); val uid = session.userId()
        val dto: ViewsDto = client.get("$base/Users/$uid/Views").body()
        dto.Items.map { MediaLibrary(it.Id, it.Name, it.CollectionType) }
    }.recoverError()

    private fun <T> Result<T>.recoverError(): Result<T> =
        fold({ Result.success(it) }, { Result.failure(map(it)) })
}
```

> 注意:Ktor 默认不对非 2xx 抛异常。在 `HttpClientFactory` 的 client 配置里加 `expectSuccess = true`,使 401/5xx 抛 `ResponseException`。请回到 Task 4 的 `block` 中补一行 `this.expectSuccess = true`(在 install 之前)。

- [ ] **Step 5: 运行验证通过**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.yfuse.core.data.EmbyRepositoryTest"`
Expected: PASS(3 个测试)。

- [ ] **Step 6: 提交**

```bash
git add composeApp/src
git commit -m "feat: add EmbyRepository with checkServer, login, libraries"
```

---

## Task 7: LoginStore(MVIKotlin)

**Files:**
- Create: `.../feature/login/LoginStore.kt`
- Test: `.../commonTest/.../feature/login/LoginStoreTest.kt`

**Interfaces:**
- Consumes: `EmbyRepository.login`(Task 6)。
- Produces:
  - `LoginState(username, password, loading, error)`
  - `sealed interface LoginIntent { UsernameChanged(v); PasswordChanged(v); Submit }`
  - `sealed interface LoginLabel { data object NavigateHome }`
  - `class LoginStoreFactory(storeFactory, repo, baseUrl).create(): Store<LoginIntent, LoginState, LoginLabel>`

- [ ] **Step 1: 写失败测试**

```kotlin
// LoginStoreTest.kt
package com.yfuse.feature.login
import app.cash.turbine.test
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.SessionManager
import com.yfuse.core.network.createEmbyClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LoginStoreTest {
    private fun store(handler: MockRequestHandler): com.arkivanov.mvikotlin.core.store.Store<LoginIntent, LoginState, LoginLabel> {
        val s = SessionManager(MapSettings())
        val repo = EmbyRepository(createEmbyClient(MockEngine(handler)) { s.token() }, s)
        return LoginStoreFactory(DefaultStoreFactory(), repo, "http://h:1").create()
    }

    @Test fun submit_success_emits_navigate_label() = runTest {
        val st = store {
            respond(ByteReadChannel("""{"AccessToken":"t","User":{"Id":"u","Name":"n"}}"""),
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        st.labels.test {
            st.accept(LoginIntent.UsernameChanged("zhuiyun"))
            st.accept(LoginIntent.PasswordChanged("123456"))
            st.accept(LoginIntent.Submit)
            assertEquals(LoginLabel.NavigateHome, awaitItem())
        }
    }

    @Test fun submit_failure_sets_error_state() = runTest {
        val st = store { respond("", HttpStatusCode.Unauthorized) }
        st.accept(LoginIntent.UsernameChanged("x"))
        st.accept(LoginIntent.PasswordChanged("y"))
        st.accept(LoginIntent.Submit)
        assertNotNull(st.state.error)
        assertFalse(st.state.loading)
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.yfuse.feature.login.LoginStoreTest"`
Expected: FAIL —— 类未定义。

- [ ] **Step 3: 实现**

```kotlin
// LoginStore.kt
package com.yfuse.feature.login
import com.arkivanov.mvikotlin.core.store.*
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.EmbyErrorException

data class LoginState(
    val username: String = "", val password: String = "",
    val loading: Boolean = false, val error: String? = null,
)
sealed interface LoginIntent {
    data class UsernameChanged(val v: String) : LoginIntent
    data class PasswordChanged(val v: String) : LoginIntent
    data object Submit : LoginIntent
}
sealed interface LoginLabel { data object NavigateHome : LoginLabel }

private sealed interface Msg {
    data class User(val v: String) : Msg
    data class Pwd(val v: String) : Msg
    data object Loading : Msg
    data class Error(val m: String) : Msg
    data object Done : Msg
}

class LoginStoreFactory(
    private val storeFactory: StoreFactory,
    private val repo: EmbyRepository,
    private val baseUrl: String,
) {
    fun create(): Store<LoginIntent, LoginState, LoginLabel> =
        object : Store<LoginIntent, LoginState, LoginLabel> by storeFactory.create<LoginIntent, Nothing, Msg, LoginState, LoginLabel>(
            name = "LoginStore",
            initialState = LoginState(),
            executorFactory = ::Executor,
            reducer = { msg -> when (msg) {
                is Msg.User -> copy(username = msg.v)
                is Msg.Pwd -> copy(password = msg.v)
                Msg.Loading -> copy(loading = true, error = null)
                is Msg.Error -> copy(loading = false, error = msg.m)
                Msg.Done -> copy(loading = false)
            } },
        ) {}

    private inner class Executor : CoroutineExecutor<LoginIntent, Nothing, LoginState, Msg, LoginLabel>() {
        override fun executeIntent(intent: LoginIntent) {
            when (intent) {
                is LoginIntent.UsernameChanged -> dispatch(Msg.User(intent.v))
                is LoginIntent.PasswordChanged -> dispatch(Msg.Pwd(intent.v))
                LoginIntent.Submit -> submit()
            }
        }
        private fun submit() {
            val s = state()
            dispatch(Msg.Loading)
            scope.launchResult(
                block = { repo.login(baseUrl, s.username, s.password) },
                onSuccess = { dispatch(Msg.Done); publish(LoginLabel.NavigateHome) },
                onError = { e ->
                    val m = if (e is EmbyErrorException) e.error.toString() else "登录失败"
                    dispatch(Msg.Error(m))
                },
            )
        }
    }
}
```

补一个小工具(放同文件或 core):

```kotlin
// 在 CoroutineExecutor 作用域内展开 Result
private fun <T> kotlinx.coroutines.CoroutineScope.launchResult(
    block: suspend () -> Result<T>, onSuccess: (T) -> Unit, onError: (Throwable) -> Unit,
) { kotlinx.coroutines.launch { block().fold(onSuccess) { onError(it) } } }
```

- [ ] **Step 4: 运行验证通过**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.yfuse.feature.login.LoginStoreTest"`
Expected: PASS(2 个测试)。

- [ ] **Step 5: 提交**

```bash
git add composeApp/src
git commit -m "feat: add LoginStore with MVIKotlin"
```

---

## Task 8: ServerStore(连接校验)

**Files:**
- Create: `.../feature/server/ServerStore.kt`
- Test: `.../commonTest/.../feature/server/ServerStoreTest.kt`

**Interfaces:**
- Consumes: `EmbyRepository.checkServer`(Task 6)。
- Produces:
  - `ServerState(url, loading, error)`
  - `sealed interface ServerIntent { UrlChanged(v); Connect }`
  - `sealed interface ServerLabel { data class Connected(val baseUrl: String) }`
  - `class ServerStoreFactory(storeFactory, repo).create()`

- [ ] **Step 1: 写失败测试**

```kotlin
// ServerStoreTest.kt
package com.yfuse.feature.server
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.SessionManager
import com.yfuse.core.network.createEmbyClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ServerStoreTest {
    @Test fun connect_success_emits_connected_label() = runTest {
        val s = SessionManager(MapSettings())
        val repo = EmbyRepository(createEmbyClient(MockEngine {
            respond(ByteReadChannel("""{"ServerName":"zhuiyun","Version":"4.9"}"""),
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { s.token() }, s)
        val store = ServerStoreFactory(DefaultStoreFactory(), repo).create()
        store.labels.test {
            store.accept(ServerIntent.UrlChanged("http://h:1"))
            store.accept(ServerIntent.Connect)
            assertEquals(ServerLabel.Connected("http://h:1"), awaitItem())
        }
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.yfuse.feature.server.ServerStoreTest"`
Expected: FAIL。

- [ ] **Step 3: 实现**(结构与 LoginStore 同构)

```kotlin
// ServerStore.kt
package com.yfuse.feature.server
import com.arkivanov.mvikotlin.core.store.*
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.yfuse.core.data.EmbyRepository
import kotlinx.coroutines.launch

data class ServerState(val url: String = "", val loading: Boolean = false, val error: String? = null)
sealed interface ServerIntent {
    data class UrlChanged(val v: String) : ServerIntent
    data object Connect : ServerIntent
}
sealed interface ServerLabel { data class Connected(val baseUrl: String) : ServerLabel }

private sealed interface Msg {
    data class Url(val v: String) : Msg
    data object Loading : Msg
    data class Error(val m: String) : Msg
    data object Done : Msg
}

class ServerStoreFactory(private val storeFactory: StoreFactory, private val repo: EmbyRepository) {
    fun create(): Store<ServerIntent, ServerState, ServerLabel> =
        object : Store<ServerIntent, ServerState, ServerLabel> by storeFactory.create<ServerIntent, Nothing, Msg, ServerState, ServerLabel>(
            name = "ServerStore",
            initialState = ServerState(),
            executorFactory = ::Executor,
            reducer = { msg -> when (msg) {
                is Msg.Url -> copy(url = msg.v)
                Msg.Loading -> copy(loading = true, error = null)
                is Msg.Error -> copy(loading = false, error = msg.m)
                Msg.Done -> copy(loading = false)
            } },
        ) {}

    private inner class Executor : CoroutineExecutor<ServerIntent, Nothing, ServerState, Msg, ServerLabel>() {
        override fun executeIntent(intent: ServerIntent) = when (intent) {
            is ServerIntent.UrlChanged -> dispatch(Msg.Url(intent.v))
            ServerIntent.Connect -> connect()
        }
        private fun connect() {
            val url = state().url
            dispatch(Msg.Loading)
            scope.launch {
                repo.checkServer(url).fold(
                    { dispatch(Msg.Done); publish(ServerLabel.Connected(url)) },
                    { dispatch(Msg.Error("无法连接服务器")) },
                )
            }
        }
    }
}
```

- [ ] **Step 4: 运行验证通过**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.yfuse.feature.server.ServerStoreTest"`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add composeApp/src
git commit -m "feat: add ServerStore with connection check"
```

---

## Task 9: HomeStore(加载库列表)

**Files:**
- Create: `.../feature/home/HomeStore.kt`
- Test: `.../commonTest/.../feature/home/HomeStoreTest.kt`

**Interfaces:**
- Consumes: `EmbyRepository.libraries`(Task 6)。
- Produces:
  - `HomeState(loading, libraries: List<MediaLibrary>, error)`
  - `sealed interface HomeIntent { data object Load; data object Retry }`
  - `class HomeStoreFactory(storeFactory, repo).create()`(创建后自动 `accept(Load)`)

- [ ] **Step 1: 写失败测试**

```kotlin
// HomeStoreTest.kt
package com.yfuse.feature.home
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.SessionManager
import com.yfuse.core.network.createEmbyClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class HomeStoreTest {
    @Test fun load_populates_libraries() = runTest {
        val s = SessionManager(MapSettings()); s.save("http://h:1", "t", "u")
        val repo = EmbyRepository(createEmbyClient(MockEngine {
            respond(ByteReadChannel("""{"Items":[{"Id":"1","Name":"电影","CollectionType":"movies"},{"Id":"2","Name":"综艺","CollectionType":"tvshows"}]}"""),
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { s.token() }, s)
        val store = HomeStoreFactory(DefaultStoreFactory(), repo).create()
        store.accept(HomeIntent.Load)
        assertEquals(2, store.state.libraries.size)
        assertEquals("电影", store.state.libraries.first().name)
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.yfuse.feature.home.HomeStoreTest"`
Expected: FAIL。

- [ ] **Step 3: 实现**

```kotlin
// HomeStore.kt
package com.yfuse.feature.home
import com.arkivanov.mvikotlin.core.store.*
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.model.MediaLibrary
import kotlinx.coroutines.launch

data class HomeState(
    val loading: Boolean = false,
    val libraries: List<MediaLibrary> = emptyList(),
    val error: String? = null,
)
sealed interface HomeIntent { data object Load : HomeIntent; data object Retry : HomeIntent }
private sealed interface Msg {
    data object Loading : Msg
    data class Loaded(val libs: List<MediaLibrary>) : Msg
    data class Error(val m: String) : Msg
}

class HomeStoreFactory(private val storeFactory: StoreFactory, private val repo: EmbyRepository) {
    fun create(): Store<HomeIntent, HomeState, Nothing> =
        object : Store<HomeIntent, HomeState, Nothing> by storeFactory.create<HomeIntent, Nothing, Msg, HomeState, Nothing>(
            name = "HomeStore",
            initialState = HomeState(),
            executorFactory = ::Executor,
            reducer = { msg -> when (msg) {
                Msg.Loading -> copy(loading = true, error = null)
                is Msg.Loaded -> copy(loading = false, libraries = msg.libs)
                is Msg.Error -> copy(loading = false, error = msg.m)
            } },
        ) {}

    private inner class Executor : CoroutineExecutor<HomeIntent, Nothing, HomeState, Msg, Nothing>() {
        override fun executeIntent(intent: HomeIntent) { load() }
        private fun load() {
            dispatch(Msg.Loading)
            scope.launch {
                repo.libraries().fold(
                    { dispatch(Msg.Loaded(it)) },
                    { dispatch(Msg.Error("加载失败")) },
                )
            }
        }
    }
}
```

- [ ] **Step 4: 运行验证通过**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.yfuse.feature.home.HomeStoreTest"`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add composeApp/src
git commit -m "feat: add HomeStore loading libraries"
```

---

## Task 10: Decompose 组件与根导航

**Files:**
- Create: `.../feature/server/ServerComponent.kt`, `.../feature/login/LoginComponent.kt`, `.../feature/home/HomeComponent.kt`
- Create: `.../app/RootComponent.kt`
- Create: `.../di/AppModule.kt`

**Interfaces:**
- Consumes: 各 StoreFactory(Task 7-9)、`SessionManager`(Task 5)。
- Produces:
  - 每个 Component 暴露 `val store` 与其 `onXxx` 回调(如 `ServerComponent.onConnected(baseUrl)`)。
  - `RootComponent` 暴露 `val stack: Value<ChildStack<*, Child>>`,`sealed interface Child { Server; Login; Home }`。
  - `AppModule`:Koin module 提供 `Settings`、`SessionManager`、`HttpClient`、`EmbyRepository`、`StoreFactory`。

- [ ] **Step 1: 实现 Component(以 Server 为例,Login/Home 同构)**

```kotlin
// ServerComponent.kt
package com.yfuse.feature.server
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.core.data.EmbyRepository

class ServerComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    repo: EmbyRepository,
    private val onConnected: (String) -> Unit,
) : ComponentContext by componentContext {
    val store = ServerStoreFactory(storeFactory, repo).create()
    init {
        // 收集 Connected label → 触发导航
        store.labelFlow(this) { if (it is ServerLabel.Connected) onConnected(it.baseUrl) }
    }
}
```

> `labelFlow` 为一个小扩展:用 `com.arkivanov.mvikotlin.extensions.coroutines.labels` 在组件生命周期内收集 labels。Login/Home 组件同理(Login 收集 `NavigateHome`;Home 在 init 内 `store.accept(HomeIntent.Load)`)。

- [ ] **Step 2: 实现 RootComponent**

```kotlin
// RootComponent.kt
package com.yfuse.app
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.*
import com.arkivanov.decompose.value.Value
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.SessionManager
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.feature.server.ServerComponent
import com.yfuse.feature.login.LoginComponent
import com.yfuse.feature.home.HomeComponent
import kotlinx.serialization.Serializable

class RootComponent(
    componentContext: ComponentContext,
    private val storeFactory: StoreFactory,
    private val repo: EmbyRepository,
    private val session: SessionManager,
) : ComponentContext by componentContext {

    private val nav = StackNavigation<Config>()
    val stack: Value<ChildStack<Config, Child>> = childStack(
        source = nav, serializer = Config.serializer(),
        initialConfiguration = if (session.hasSession()) Config.Home else Config.Server,
        handleBackButton = true, childFactory = ::child,
    )

    sealed interface Child {
        class Server(val c: ServerComponent) : Child
        class Login(val c: LoginComponent) : Child
        class Home(val c: HomeComponent) : Child
    }
    @Serializable sealed interface Config {
        @Serializable data object Server : Config
        @Serializable data class Login(val baseUrl: String) : Config
        @Serializable data object Home : Config
    }

    private fun child(config: Config, ctx: ComponentContext): Child = when (config) {
        Config.Server -> Child.Server(ServerComponent(ctx, storeFactory, repo) {
            nav.push(Config.Login(it))
        })
        is Config.Login -> Child.Login(LoginComponent(ctx, storeFactory, repo, config.baseUrl) {
            nav.replaceAll(Config.Home)
        })
        Config.Home -> Child.Home(HomeComponent(ctx, storeFactory, repo))
    }
}
```

- [ ] **Step 3: 实现 Koin AppModule**

```kotlin
// AppModule.kt
package com.yfuse.di
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.russhwolf.settings.Settings
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.SessionManager
import com.yfuse.core.network.createEmbyClient
import org.koin.dsl.module

fun appModule(settings: Settings) = module {
    single { settings }
    single { SessionManager(get()) }
    single<StoreFactory> { DefaultStoreFactory() }
    single { createEmbyClient { get<SessionManager>().token() } }
    single { EmbyRepository(get(), get()) }
}
```

- [ ] **Step 4: 验证编译**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```bash
git add composeApp/src
git commit -m "feat: add Decompose components, RootComponent navigation, Koin module"
```

---

## Task 11: Compose UI(三屏)与 App 装配

**Files:**
- Create: `.../feature/server/ServerScreen.kt`, `.../feature/login/LoginScreen.kt`, `.../feature/home/HomeScreen.kt`
- Modify: `.../app/App.kt`
- Modify: `.../androidMain/.../MainActivity.kt`

**Interfaces:**
- Consumes: 各 Component 的 `store`;`RootComponent.stack`。
- Produces: 可运行 App:输入地址→连接→登录→显示库名列表。

- [ ] **Step 1: 实现三个 Screen(以 Server 为例)**

```kotlin
// ServerScreen.kt
package com.yfuse.feature.server
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import kotlinx.coroutines.flow.collectAsState  // 用官方 states 扩展 + collectAsState

@Composable
fun ServerScreen(component: ServerComponent) {
    val state by component.store.states.collectAsState(ServerState())
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("连接 Emby 服务器", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = state.url, onValueChange = { component.store.accept(ServerIntent.UrlChanged(it)) },
            label = { Text("服务器地址,如 http://host:port") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { component.store.accept(ServerIntent.Connect) },
            enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
            if (state.loading) CircularProgressIndicator(Modifier.size(20.dp)) else Text("连接")
        }
    }
}
```

> `LoginScreen`:两个输入框(用户名/密码,密码用 `PasswordVisualTransformation`)+ 登录按钮 + error。`HomeScreen`:`LazyColumn` 遍历 `state.libraries` 显示 `Text(lib.name)`,loading 时显示进度圈,error 时显示重试按钮 `component.store.accept(HomeIntent.Retry)`。三者都用 `component.store.states.collectAsState(初始State)`。
>
> 注意:`states` 来自 `mvikotlin-extensions-coroutines`,返回 `Flow<State>`,需在 build.gradle 的 commonMain 里确保该依赖存在(Task 1 已加 `mvikotlin-coroutines`)。

- [ ] **Step 2: 实现 App.kt(渲染导航栈)**

```kotlin
// App.kt
package com.yfuse.app
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.yfuse.feature.home.HomeScreen
import com.yfuse.feature.login.LoginScreen
import com.yfuse.feature.server.ServerScreen

@Composable
fun App(root: RootComponent) {
    MaterialTheme {
        Children(stack = root.stack) { child ->
            when (val c = child.instance) {
                is RootComponent.Child.Server -> ServerScreen(c.c)
                is RootComponent.Child.Login -> LoginScreen(c.c)
                is RootComponent.Child.Home -> HomeScreen(c.c)
            }
        }
    }
}
```

- [ ] **Step 3: 实现 MainActivity(创建 Koin + retainedComponent)**

```kotlin
// MainActivity.kt
package com.yfuse
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.arkivanov.decompose.retainedComponent
import com.russhwolf.settings.SharedPreferencesSettings
import com.yfuse.app.App
import com.yfuse.app.RootComponent
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.SessionManager
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.yfuse.core.network.createEmbyClient
import org.koin.core.context.startKoin
import com.yfuse.di.appModule

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("yfuse", MODE_PRIVATE)
        val settings = SharedPreferencesSettings(prefs)
        val session = SessionManager(settings)
        val repo = EmbyRepository(createEmbyClient { session.token() }, session)
        val storeFactory = DefaultStoreFactory()
        val root = retainedComponent { ctx ->
            RootComponent(ctx, storeFactory, repo, session)
        }
        setContent { App(root) }
    }
}
```

> `AndroidManifest.xml` 需声明 `<uses-permission android:name="android.permission.INTERNET"/>`,并允许明文 HTTP(Emby 是 http):在 `<application>` 上加 `android:usesCleartextTraffic="true"`(仅开发期;生产建议 HTTPS)。

- [ ] **Step 4: 冒烟测试(真实服务器)**

Run: `./gradlew :composeApp:installDebug`,在设备/模拟器打开 App。
手动步骤:输入你的服务器地址 → 连接 → 输入账号密码 → 登录 → 应看到 11 个库名列表。
Expected: 库名列表正确显示(国产电影/剧集、欧美电影、动漫、综艺、纪录片 等)。

- [ ] **Step 5: 提交**

```bash
git add composeApp/src
git commit -m "feat: add Compose screens and wire up app navigation"
```

---

## 完成标准

- 全部单元测试通过:`./gradlew :composeApp:testDebugUnitTest`。
- 冒烟测试通过:真机跑通「连接→登录→看到库列表」。
- 无凭据进入版本库。

下一步:计划二(首页海报墙 + 详情页)。
