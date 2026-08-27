package org.joinmastodon.android.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.joinmastodon.android.BuildConfig
import org.joinmastodon.android.MainActivity
import org.joinmastodon.android.R
import org.joinmastodon.android.ui.compose.AppState
import org.joinmastodon.android.ui.compose.LocalAppState
import org.joinmastodon.android.ui.compose.MiuixAppTheme
import org.joinmastodon.android.ui.compose.component.effect.BgEffectBackground
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 启动页 — BgEffectBackground 全屏动态背景 + 居中 App 图标（白色圆角块）+
 * “ABDL Space” + 版本号，深浅色模式自适应。展示 [SPLASH_SECONDS] 秒后路由到主页。
 *
 * 稳定性约束：此页面运行在冷启动首帧场景，严禁引入 layerBackdrop/textureBlur
 * 采样链路（与系统 starting window 退出动画并发出栈时会导致 RenderNode 嵌套录制崩溃，
 * HyperOS 实测）；文字为纯色渲染，颜色全部取自 Miuix 主题以适配深浅色。
 */
class SplashActivity : ComponentActivity() {

    companion object {
        private const val SPLASH_SECONDS = 3
    }

    private var routed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        makeEdgeToEdge()

        setContent { SplashScreenContent(totalSeconds = SPLASH_SECONDS, onSkip = ::routeToMain) }

        // 总时长兜底计时（与 Compose 内每秒倒计时独立）
        window.decorView.postDelayed({ routeToMain() }, SPLASH_SECONDS * 1000L)
    }

    override fun onBackPressed() {
        finish()
    }

    private fun routeToMain() {
        if (routed) return
        routed = true
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        overridePendingTransition(0, 0)
        finish()
    }

    private fun makeEdgeToEdge() {
        val window: Window = window
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }
}

@Composable
private fun SplashScreenContent(totalSeconds: Int, onSkip: () -> Unit) {
    var remainingSeconds by remember { mutableIntStateOf(totalSeconds) }
    LaunchedEffect(Unit) {
        repeat(totalSeconds) {
            delay(1000)
            remainingSeconds--
        }
    }
    CompositionLocalProvider(LocalAppState provides AppState()) {
        MiuixAppTheme {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MiuixTheme.colorScheme.background),
            ) {
                BgEffectBackground(
                    dynamicBackground = isRuntimeShaderSupported(),
                    isOs3Effect = true,
                    isFullSize = false,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(88.dp)
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(Color.White),
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.ic_ntf_logo),
                                    contentDescription = null,
                                    modifier = Modifier.size(74.dp),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                            Text(
                                text = "ABDL Space",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 35.sp,
                                modifier = Modifier.padding(top = 12.dp, bottom = 5.dp),
                            )
                            Text(
                                text = "v${BuildConfig.VERSION_NAME}",
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 14.sp,
                            )
                        }
                        if (remainingSeconds > 0) {
                            val statusBarsTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                            SkipButton(
                                secondsLeft = remainingSeconds,
                                onClick = onSkip,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = statusBarsTop + 10.dp, end = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkipButton(secondsLeft: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = "跳过(${secondsLeft}s)",
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
