package com.yfuse.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yfuse.core.designsystem.ThemeMode
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
    val logoAlpha = androidx.compose.runtime.remember { Animatable(0f) }
    val logoScale = androidx.compose.runtime.remember { Animatable(0.78f) }
    val logoOffset = androidx.compose.runtime.remember { Animatable(18f) }

    LaunchedEffect(Unit) {
        launch {
            logoAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 320),
            )
        }
        launch {
            logoScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.58f,
                    stiffness = 240f,
                ),
            )
        }
        launch {
            logoOffset.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = 620,
                    easing = FastOutSlowInEasing,
                ),
            )
        }

        delay(1_050)
        onFinished()
    }

    val background = if (dark) {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFF1B1F27),
                Color(0xFF0F1216),
            ),
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFFF6F7F9),
                Color(0xFFE6EBF1),
            ),
        )
    }
    val titleColor = if (dark) Color(0xFFEEF0F3) else Color(0xFF27324A)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(Res.drawable.logo),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(148.dp)
                    .graphicsLayer {
                        alpha = logoAlpha.value
                        scaleX = logoScale.value
                        scaleY = logoScale.value
                        translationY = logoOffset.value.dp.toPx()
                    },
            )
            Spacer(Modifier.height(18.dp))
            androidx.compose.material3.Text(
                text = "Yfuse",
                color = titleColor,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp,
                modifier = Modifier.graphicsLayer {
                    alpha = logoAlpha.value
                    translationY = logoOffset.value.dp.toPx()
                },
            )
        }
    }
}
