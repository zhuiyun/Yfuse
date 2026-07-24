package com.yfuse.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yfuse.core.designsystem.ThemeMode
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.resources.Res
import com.yfuse.resources.logo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

/**
 * Keeps the real app composing behind the splash so startup work and the entrance
 * animation happen in parallel. The saved flag prevents replay after configuration
 * changes while still showing the animation for each fresh launch.
 */
@Composable
fun AnimatedSplashApp(root: RootComponent) {
    var splashVisible by rememberSaveable { mutableStateOf(true) }
    val themeMode by root.themePreferences.mode.collectAsState()
    val dark = when (themeMode) {
        ThemeMode.System -> androidx.compose.foundation.isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }

    Box(Modifier.fillMaxSize()) {
        App(root)

        AnimatedVisibility(
            visible = splashVisible,
            modifier = Modifier.fillMaxSize(),
            exit = fadeOut(animationSpec = tween(durationMillis = 300)),
        ) {
            AnimatedSplashScreen(
                dark = dark,
                onFinished = { splashVisible = false },
            )
        }
    }
}

@Composable
private fun AnimatedSplashScreen(
    dark: Boolean,
    onFinished: () -> Unit,
) {
    StatusBarIconStyle(darkIcons = false)

    val entrance = remember { Animatable(0f) }
    val energy = remember { Animatable(0f) }
    val brand = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch {
            entrance.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.78f,
                    stiffness = 260f,
                ),
            )
        }
        launch {
            energy.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 1_050,
                    easing = LinearOutSlowInEasing,
                ),
            )
        }
        launch {
            delay(280)
            brand.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 460,
                    easing = FastOutSlowInEasing,
                ),
            )
        }

        delay(1_400)
        onFinished()
    }

    val background = Brush.radialGradient(
        colors = listOf(
            if (dark) Color(0xFF101A35) else Color(0xFF132044),
            Color(0xFF070B17),
            Color(0xFF03050B),
        ),
    )
    val electricBlue = Color(0xFF4D83FF)
    val cyan = Color(0xFF5DE7FF)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val p = energy.value
            val center = Offset(size.width / 2f, size.height / 2f - 24.dp.toPx())
            val frame = 108.dp.toPx()
            val grid = 42.dp.toPx()

            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        electricBlue.copy(alpha = 0.20f * entrance.value),
                        electricBlue.copy(alpha = 0.05f * entrance.value),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = frame * 2.2f,
                ),
            )

            var x = center.x % grid
            while (x < size.width) {
                drawLine(
                    Color(0xFF6EA8FF).copy(alpha = 0.035f * p),
                    Offset(x, 0f),
                    Offset(x, size.height),
                    strokeWidth = 0.6.dp.toPx(),
                )
                x += grid
            }
            var y = center.y % grid
            while (y < size.height) {
                drawLine(
                    Color(0xFF6EA8FF).copy(alpha = 0.028f * p),
                    Offset(0f, y),
                    Offset(size.width, y),
                    strokeWidth = 0.6.dp.toPx(),
                )
                y += grid
            }

            val bracket = 21.dp.toPx() * entrance.value
            val left = center.x - frame
            val right = center.x + frame
            val top = center.y - frame
            val bottom = center.y + frame
            val bracketColor = cyan.copy(alpha = 0.72f * entrance.value)
            listOf(
                Offset(left, top) to Offset(left + bracket, top),
                Offset(left, top) to Offset(left, top + bracket),
                Offset(right, top) to Offset(right - bracket, top),
                Offset(right, top) to Offset(right, top + bracket),
                Offset(left, bottom) to Offset(left + bracket, bottom),
                Offset(left, bottom) to Offset(left, bottom - bracket),
                Offset(right, bottom) to Offset(right - bracket, bottom),
                Offset(right, bottom) to Offset(right, bottom - bracket),
            ).forEach { (start, end) ->
                drawLine(
                    color = bracketColor,
                    start = start,
                    end = end,
                    strokeWidth = 1.2.dp.toPx(),
                )
            }

            val scanY = top + (bottom - top) * p
            drawLine(
                color = cyan.copy(alpha = 0.10f),
                start = Offset(left, scanY),
                end = Offset(right, scanY),
                strokeWidth = 8.dp.toPx(),
            )
            drawLine(
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, cyan, Color.White, cyan, Color.Transparent),
                    startX = left,
                    endX = right,
                ),
                start = Offset(left, scanY),
                end = Offset(right, scanY),
                strokeWidth = 1.dp.toPx(),
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(Res.drawable.logo),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(156.dp)
                    .graphicsLayer {
                        alpha = entrance.value
                        val scale = 0.86f + entrance.value * 0.14f
                        scaleX = scale
                        scaleY = scale
                        translationY = (1f - entrance.value) * 10.dp.toPx()
                        shadowElevation = 16.dp.toPx() * entrance.value
                    },
            )
            Spacer(Modifier.height(30.dp))
            androidx.compose.material3.Text(
                text = "YFUSE",
                color = Color(0xFFF4F8FF),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 7.sp,
                modifier = Modifier.graphicsLayer {
                    alpha = brand.value
                    translationY = (1f - brand.value) * 8.dp.toPx()
                },
            )
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .width((64f * brand.value).dp)
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, cyan, Color.White, cyan, Color.Transparent),
                        ),
                    ),
            )
        }
    }
}
