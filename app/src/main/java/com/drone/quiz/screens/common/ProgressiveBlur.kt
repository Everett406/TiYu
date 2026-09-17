package com.drone.quiz.screens.common

import android.graphics.RenderEffect as AndroidRenderEffect
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asAndroidRuntimeShader
import com.kyant.backdrop.createRuntimeShader
import kotlin.math.roundToInt

/**
 * 渐进式模糊（Progressive Blur）——标题栏与正文交界处，模糊半径沿 y 轴从满值
 * 渐变到 0：内容上滚经过交界时逐渐虚化、溶进标题区（Apple Music / iOS 滚动
 * 边缘同款效果），而不是旧方案的一条硬截断边。
 *
 * v2.15.0 全新实现。旧顶部柔化方案（v2.6.0–v2.7.0 五轮迭代后废弃为空实现）的
 * 病根：alpha 蒙版 / 雾条 / saveLayer 都在「滚动容器上盖东西」，与玻璃卡片
 * 离屏渲染互作产生伪影与闪烁。新方案换掉机制本身——AGSL RuntimeShader 双 pass
 * 可变半径高斯（水平+垂直链式 RenderEffect），与 Haze 1.x 及 Compose 1.13
 * 官方 Modifier.blur { BlurRadiusSpec } 同机制族；RenderEffect 系在本 APP
 * 已被弹窗全屏模糊（AppRoot overlayBlur）长年验证安全。
 *
 * 实现要点（对照业界已知坑位逐条处理）：
 * - 半径剖面 r(y) = maxR × intensity × t²，t 从交界 1 渐到 zone 下缘 0——
 *   分离式近似（行内同半径）在水平/垂直两趟间严格一致；
 * - 线性采样优化：一次 eval 同时承担相邻两像素的高斯权重（rastergrid 法），
 *   常量循环上界 96 + 运行时 break 早退，实际开销 ∝ 当前半径；
 * - 全程 float（half 在部分三星机型上精度裁剪，Haze #520）；
 * - 采样坐标 clamp 到图层内（eval 越界返回透明导致边缘渗色发黑）；
 * - RuntimeShader 进程级仅 2 实例（H/V），AGSL 只编译一次；uniform 变化只重建
 *   轻量 RenderEffect 包装，强度按滚动距离连续渐变并量化 24 步限频；
 * - 卸载不走 null 硬切换：强度随滚动像素连续回落到 0 后才移除，无闪烁窗口；
 * - 端点熔化：交界顶端 18% zone 内 premultiplied alpha 渐隐（随强度同步缩放），
 *   内容先虚化再溶入标题区；
 * - API < 33 无 RuntimeShader → 原样返回（硬裁切，与旧行为一致，minSdk 31/32）。
 *
 * 挂载位置：滚动容器外层（BounceContainer / BounceLazyColumn / LazyColumn 的
 * modifier 链），图层顶缘即交界线，滚动裁切发生在图层之内——模糊永不溢进标题区。
 */
fun Modifier.progressiveTopBlur(
    zoneHeight: Dp = 36.dp,
    maxRadius: Dp = Dp.Unspecified,
    scrolledPx: () -> Float
): Modifier {
    if (Build.VERSION.SDK_INT < 33) return this
    val state = ProgressiveBlurState()
    return this.graphicsLayer {
        // 滚动像素 → 强度：滚出 1px 起效，56dp 内升满（连续驱动，跟手无延迟）
        val intensity = ((scrolledPx() - 1f) / 56.dp.toPx()).coerceIn(0f, 1f)
        val step = (intensity * 24f).roundToInt()
        val key = step.toLong() * 1_000_003L + size.width.toLong() * 8192L + size.height.toLong()
        if (key == state.key) return@graphicsLayer
        state.key = key

        renderEffect = if (step <= 0) {
            null
        } else {
            val q = step / 24f
            val zonePx = zoneHeight.toPx()
            val radiusPx =
                (if (maxRadius == Dp.Unspecified) (zoneHeight.value * 0.42f).dp else maxRadius).toPx()
            val w = size.width.toFloat()
            val hgt = size.height.toFloat()

            val h = obtainHorizontalShader()
            h.setFloatUniform("uSize", w, hgt)
            h.setFloatUniform("uZonePx", zonePx)
            h.setFloatUniform("uRadiusPx", radiusPx)
            h.setFloatUniform("uIntensity", q)
            h.setFloatUniform("uMeltPx", 0f)
            val v = obtainVerticalShader()
            v.setFloatUniform("uSize", w, hgt)
            v.setFloatUniform("uZonePx", zonePx)
            v.setFloatUniform("uRadiusPx", radiusPx)
            v.setFloatUniform("uIntensity", q)
            v.setFloatUniform("uMeltPx", zonePx * 0.18f * q)

            val hEffect =
                AndroidRenderEffect.createRuntimeShaderEffect(h.asAndroidRuntimeShader(), "uContent")
            val vEffect =
                AndroidRenderEffect.createRuntimeShaderEffect(v.asAndroidRuntimeShader(), "uContent")
            // 先水平后垂直（熔化渐隐在末趟垂直 pass 上）
            AndroidRenderEffect.createChainEffect(vEffect, hEffect).asComposeRenderEffect()
        }
    }
}

private class ProgressiveBlurState {
    var key: Long = Long.MIN_VALUE
}

@Volatile
private var sharedH: RuntimeShader? = null

@Volatile
private var sharedV: RuntimeShader? = null

/** uDirection=0：水平 pass（uMeltPx 恒 0，熔化只在末趟垂直 pass 做）。 */
@RequiresApi(33)
private fun obtainHorizontalShader(): RuntimeShader =
    sharedH ?: createRuntimeShader(BLUR_SKSL).also {
        it.setFloatUniform("uDirection", 0f)
        sharedH = it
    }

/** uDirection=1：垂直 pass + 顶端熔化渐隐。 */
@RequiresApi(33)
private fun obtainVerticalShader(): RuntimeShader =
    sharedV ?: createRuntimeShader(BLUR_SKSL).also {
        it.setFloatUniform("uDirection", 1f)
        sharedV = it
    }

/**
 * AGSL 可变半径高斯 pass。
 * 半径剖面由 uZonePx / uIntensity 决定（交界最强、zone 下缘归零）；
 * 线性采样：eval 落在 i+0.5-w2/wl 处一次同时覆盖像素 i 与 i+1 的权重。
 */
private const val BLUR_SKSL = """
uniform shader uContent;
uniform float2 uSize;
uniform float uZonePx;
uniform float uRadiusPx;
uniform float uIntensity;
uniform float uDirection;
uniform float uMeltPx;

float4 pSample(float2 p) {
    float x = clamp(p.x, 0.0, uSize.x - 1.0);
    float y = clamp(p.y, 0.0, uSize.y - 1.0);
    return float4(uContent.eval(float2(x, y)));
}

half4 main(float2 xy) {
    float t = clamp(1.0 - xy.y / uZonePx, 0.0, 1.0);
    float radius = uRadiusPx * uIntensity * t * t;
    if (radius < 0.5) {
        return uContent.eval(xy);
    }
    float sigma = max(radius / 2.0, 1.0);
    float r = floor(radius + 0.5);
    float gden = 2.0 * sigma * sigma;
    float2 dir = uDirection < 0.5 ? float2(1.0, 0.0) : float2(0.0, 1.0);
    float4 acc = pSample(xy);
    float wsum = 1.0;
    for (float i = 1.0; i < 96.0; i += 2.0) {
        if (i >= r) break;
        float w1 = exp(-(i * i) / gden);
        float w2 = exp(-((i + 1.0) * (i + 1.0)) / gden);
        float wl = w1 + w2;
        float off = i + 0.5 - w2 / wl;
        acc += pSample(xy + dir * off) * wl;
        acc += pSample(xy - dir * off) * wl;
        wsum += 2.0 * wl;
    }
    acc /= wsum;
    if (uDirection > 0.5 && uMeltPx > 0.0) {
        acc *= clamp(xy.y / uMeltPx, 0.0, 1.0);
    }
    return half4(acc);
}
"""
