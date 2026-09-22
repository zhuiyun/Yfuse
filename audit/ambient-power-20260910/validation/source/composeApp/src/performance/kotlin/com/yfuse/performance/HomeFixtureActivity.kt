package com.yfuse.performance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.intercept.Interceptor
import coil3.request.SuccessResult
import com.yfuse.BuildConfig
import com.yfuse.R
import com.yfuse.core.designsystem.ArtworkPageTheme
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.core.designsystem.rememberCarouselPageColor
import com.yfuse.core.model.TmdbHome
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.model.TmdbRow
import com.yfuse.feature.home.HomeCalendarState
import com.yfuse.feature.home.HomeContentBody
import com.yfuse.feature.home.HomeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Only benchmark/profile variants contain this activity. The UI below is the production home renderer. */
@OptIn(DelicateCoilApi::class)
class HomeFixtureActivity : ComponentActivity() {
    private companion object {
        // Repeated activity launches must not append a second URL-rewriting interceptor.
        var productionLoader: ImageLoader? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.APPLICATION_ID.endsWith(".benchmark"))
        enableEdgeToEdge()
        val decoded = MutableStateFlow(0)
        val imagePattern = Regex("/fixture-(\\d+)\\.png")
        val originalLoader = productionLoader ?: SingletonImageLoader.get(this).also { productionLoader = it }
        val posters =
            listOf(
                R.drawable.perf_poster_0,
                R.drawable.perf_poster_1,
                R.drawable.perf_poster_2,
                R.drawable.perf_poster_3,
                R.drawable.perf_poster_4,
                R.drawable.perf_poster_5,
                R.drawable.perf_poster_6,
                R.drawable.perf_poster_7,
            )
        SingletonImageLoader.setUnsafe(
            originalLoader
                .newBuilder()
                .components {
                    add(
                        Interceptor { chain ->
                            val data = chain.request.data.toString()
                            val index =
                                imagePattern
                                    .find(data)
                                    ?.groupValues
                                    ?.get(1)
                                    ?.toInt()
                                    ?: error("Fixture requested an unregistered image")
                            val local =
                                chain.request
                                    .newBuilder()
                                    .data(posters[index % posters.size])
                                    .build()
                            chain.withRequest(local).proceed().also {
                                if (it is SuccessResult) {
                                    decoded.update { count ->
                                        count +
                                            1
                                    }
                                }
                            }
                        },
                    )
                }.build(),
        )
        val titles =
            (0 until 96).map { index ->
                TmdbItem(
                    id = index + 1,
                    title = "本地影片 ${index.toString().padStart(2, '0')}",
                    overview = "固定本地片库，用于复现生产首页图片解码、排版和列表滚动。",
                    posterPath = "/fixture-$index.png",
                    backdropPath = "/fixture-$index.png",
                    year = "2026",
                    mediaType = "movie",
                    rating = 8.2,
                    runtimeMinutes = 108,
                )
            }
        val state =
            HomeState(
                loading = false,
                today = "2026-09-08",
                content =
                    TmdbHome(
                        featured = titles.take(8),
                        rows = titles.chunked(12).mapIndexed { index, items -> TmdbRow("本地片架 ${index + 1}", items) },
                    ),
            )
        setContent {
            YfuseTheme(dark = true) {
                val listState = rememberLazyListState()
                var color by remember { mutableStateOf(Color(0xFF18212C)) }
                val pageColor = rememberCarouselPageColor(color)
                var accent by remember { mutableStateOf<Color?>(null) }
                val imageCount by decoded.collectAsState()
                var drawn by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    withFrameNanos {}
                    withFrameNanos {}
                    drawn = true
                }
                Box(
                    Modifier.fillMaxSize().semantics {
                        testTagsAsResourceId = true
                        if (drawn && imageCount > 0) contentDescription = "home-fixture-v1-ready"
                    },
                ) {
                    ArtworkPageTheme(background = color, artworkAccent = accent) {
                        HomeContentBody(
                            state = state,
                            calendarState = HomeCalendarState(loading = false),
                            listState = listState,
                            heroPageColor = pageColor,
                            onHeroAccent = { accent = it },
                            onHeroPageColor = { color = it },
                            onIntent = {},
                            onRefreshCalendar = {},
                            onOpenProfile = {},
                            onOpenCalendar = {},
                            onOpenLibrary = {},
                            onOpenCalendarEntry = {},
                        )
                    }
                }
            }
        }
    }
}
