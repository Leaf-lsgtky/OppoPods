package moe.chenxy.oppopods.ui

import android.content.Context
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.TextureView
import android.widget.ImageView
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.google.android.filament.IndirectLight
import com.google.android.filament.Camera
import com.google.android.filament.Renderer
import com.google.android.filament.Texture
import com.google.android.filament.View
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.Float3
import com.google.android.filament.utils.KTX1Loader
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import com.google.android.filament.utils.scale
import com.google.android.filament.utils.translation
import com.google.android.filament.utils.transpose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.pods.HeyMelodyArtworkClient
import moe.chenxy.oppopods.pods.VfxModel
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.sin

private sealed interface ProductAsset {
    data class Vfx(val model: VfxModel) : ProductAsset
    data class Animated(val bytes: ByteArray) : ProductAsset
}

private sealed interface ProductLoad {
    data object Loading : ProductLoad
    data class Ready(val asset: ProductAsset) : ProductLoad
    data object Failed : ProductLoad
}

/**
 * 官方模型资源的统一入口：
 * - `.vfxms`（X3 等）交给 Filament，完整使用容器内 config/GLB/IBL；
 * - `.webp`（X4 的模块 14 实际是 `modelWebp` 动画）交给 ImageDecoder 逐帧播放。
 */
@Composable
fun PodModelViewer(
    modelFile: File,
    modifier: Modifier = Modifier,
    // 页面不可见时只暂停渲染，不销毁引擎：3D 模型和已上屏的纹理都保留，
    // 回到主页立即显示，不需要重新解析/重新上传 GPU 资源。
    isActive: Boolean = true,
    // 未连接时让模型停在当前角度，不再自转。
    autoRotate: Boolean = true,
    fallback: (() -> Unit)? = null,
) {
    val load by produceState<ProductLoad>(initialValue = ProductLoad.Loading, modelFile.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = modelFile.readBytes()
                if (HeyMelodyArtworkClient.isWebp(bytes)) {
                    ProductLoad.Ready(ProductAsset.Animated(bytes))
                } else {
                    val model = VfxModel.parse(bytes)
                    if (model == null) ProductLoad.Failed
                    else ProductLoad.Ready(ProductAsset.Vfx(model))
                }
            }.getOrDefault(ProductLoad.Failed)
        }
    }
    when (val state = load) {
        ProductLoad.Loading -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            InfiniteProgressIndicator()
        }
        // 模型不可用时交给调用方回退成图片，不再显示报错文案。
        ProductLoad.Failed -> if (fallback != null) {
            LaunchedEffect(Unit) { fallback() }
        } else {
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.official_artwork_3d_failed),
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        is ProductLoad.Ready -> when (val asset = state.asset) {
            is ProductAsset.Animated -> PodAnimatedWebp(asset.bytes, modifier)
            is ProductAsset.Vfx -> PodFilamentModel(
                asset.model,
                modelFile.path,
                modifier,
                isActive,
                autoRotate,
                fallback,
            )
        }
    }
}

@Composable
private fun PodFilamentModel(
    model: VfxModel,
    pathKey: String,
    modifier: Modifier,
    isActive: Boolean,
    autoRotate: Boolean,
    fallback: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val controller = remember(pathKey) {
        runCatching { PodModelController(context, model) }
            .onFailure { Log.e("OppoPods-3D", "Filament controller init failed", it) }
            .getOrNull()
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    if (controller == null) {
        if (fallback != null) {
            LaunchedEffect(Unit) { fallback() }
        } else {
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.official_artwork_3d_unavailable),
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        return
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { controller.view },
            modifier = Modifier.matchParentSize(),
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                // 触摸热区略窄于视图，避免滑动页面时误吃手势。
                .fillMaxWidth(0.8f)
                .fillMaxHeight(0.85f)
                .pointerInput(controller) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 防误触：手指落在模型区域后立刻消费 DOWN，LazyColumn 不会再抢成页面滚动。
                        down.consume()
                        controller.beginTouch()
                        var zooming = false
                        var lastDistance = 0f
                        var lastPosition = down.position
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                zooming = true
                                val distance = (pressed[0].position - pressed[1].position).getDistance()
                                if (lastDistance > 0f && distance > 0f) {
                                    controller.zoom(distance / lastDistance)
                                }
                                lastDistance = distance
                                pressed.forEach { it.consume() }
                            } else {
                                lastDistance = 0f
                                val change = pressed.first()
                                val delta = change.position - lastPosition
                                lastPosition = change.position
                                if (!zooming) {
                                    controller.drag(delta.x, delta.y)
                                    change.consume()
                                }
                            }
                        }
                        controller.endDrag()
                    }
                },
        )
    }

    DisposableEffect(controller, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.start()
                Lifecycle.Event.ON_PAUSE -> controller.stop()
                // Engine 的生命周期由 controller.destroy() 统一收口，
                // 这里不再重复触发，避免与 onDispose 竞争。
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            controller.start()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // 先停帧再销毁，确保销毁后不会再有任何渲染回调进入 native。
            controller.stop()
            controller.destroy()
        }
    }

    // 页面不可见时暂停帧回调，但**不销毁**控制器：引擎、场景、纹理都留着，
    // 切回主页即可立即继续渲染。
    LaunchedEffect(controller, isActive) {
        if (isActive) controller.start() else controller.stop()
    }
    LaunchedEffect(controller, autoRotate) {
        controller.setAutoRotate(autoRotate)
    }
}

@Composable
private fun PodAnimatedWebp(
    bytes: ByteArray,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val drawable by produceState<Drawable?>(initialValue = null, bytes) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeDrawable(
                    ImageDecoder.createSource(ByteBuffer.wrap(bytes)),
                )
            }.getOrNull()
        }
    }
    val imageView = remember {
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.TRANSPARENT)
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { imageView },
            modifier = Modifier.matchParentSize(),
            update = { view ->
                view.setImageDrawable(drawable)
                (drawable as? AnimatedImageDrawable)?.apply {
                    repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    start()
                }
            },
        )
        if (drawable == null) InfiniteProgressIndicator()
    }

    DisposableEffect(imageView) {
        onDispose { (imageView.drawable as? AnimatedImageDrawable)?.stop() }
    }
}

private class PodModelController(
    context: Context,
    model: VfxModel,
) {
    val view = TextureView(context).apply {
        isOpaque = false
    }

    private val engine = createEngine()
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply {
        // 让 UiHelper 给 SwapChain 加 CONFIG_TRANSPARENT，页面背景才能透出来。
        isOpaque = false
    }
    private val viewerDelegate = lazy {
        ModelViewer(view, engine, uiHelper, null)
    }
    private val viewer by viewerDelegate
    private val choreographer = Choreographer.getInstance()

    private var indirectLight: IndirectLight? = null
    private var indirectLightTexture: Texture? = null
    private var destroyed = false
    private var viewDetached = false
    private var running = false

    // 相机注视点。X/Z 与官方一致（模型中心在这里），Y 略微下压：
    // 注视点低于模型中心，模型就会稳定地显示在画面中心偏上，且旋转时不会抖动。
    private val target = floatArrayOf(0f, -0.26f, -4f)
    // 与官方 CameraController 一致：mCenterPos=(0,0,-4)、mStartEyePos=(0,0,1)，
    // 即相机在 +Z 一侧、面向 -Z 观察。
    //
    // 距离与视图高度成正比（PodDetailPage 里是 240dp，对应原来的 320dp↔5.0）。
    // 固定 45° 垂直 FOV 下，模型占视图高度的比例 ≈ 模型尺寸 /(2·距离·tan22.5°)，
    // 所以「高度 ×0.75 + 距离 ×0.75」时模型像素尺寸不变，裁掉的只是上下多余的空白。
    // 只压高度而不动距离，模型会跟着等比变小，留白并不会真的减少。
    private var currentDistance = 3.75f
    private var targetDistance = 3.75f
    private val defaultDistance = 3.75f
    // 官方 refreshMinMaxDis：near = 0.75×默认, far = 1.5×默认。
    private val minDistance = defaultDistance * 0.75f
    private val maxDistance = defaultDistance * 1.5f
    private var yaw = 0f
    private var pitch = 0f
    private var autoRotate = true
    private var recovering = false
    private var recoverStart = 0L
    private var recoverHoldUntil = 0L
    private var recoverFromDistance = 3.75f
    private var recoverFromPitch = 0f
    private var rotateSpeed = 20f
    private var currentSlideX = 0f
    private var currentSlideY = 0f
    private var targetSlideX = 0f
    private var targetSlideY = 0f
    private var lastFrameNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            // 双重校验：destroy() 可能在本帧回调已经入队之后才执行，
            // 此时 Engine 已销毁，继续 render 会直接踩空 native 指针。
            if (destroyed || !running) return
            val delta = if (lastFrameNanos == 0L) 16.6f
            else ((frameTimeNanos - lastFrameNanos) / 1_000_000f).coerceIn(0f, 50f)
            lastFrameNanos = frameTimeNanos
            val now = SystemClock.uptimeMillis()
            val dt = delta / 1000f
            // 与欢律 CameraController.updateCameraMatrix 一致：
            // 手指 delta 先以 7.2/s 阻尼逼近，再乘 -21°/s 积分；松手后 target=0，
            // 残余速度自然衰减，就是官方“扒拉后还会滑一下”的手感。
            val damp = (7.2f * dt).coerceIn(0f, 1f)
            currentSlideX += (targetSlideX - currentSlideX) * damp
            currentSlideY += (targetSlideY - currentSlideY) * damp
            val magnitude = currentSlideX * currentSlideX + currentSlideY * currentSlideY
            if (!recovering || magnitude >= 0.01f) {
                currentDistance += (targetDistance - currentDistance) * damp
            }
            yaw += currentSlideX * -21f * dt
            pitch = (pitch - currentSlideY * -21f * dt).coerceIn(-85f, 85f)

            if (autoRotate) {
                if (recovering) {
                    if (magnitude < 0.01f) {
                        if (recoverHoldUntil == 0L) {
                            // 欢律 ControlOver 会先等滑动/距离稳定；这里再多停 2 秒。
                            recoverHoldUntil = now + 2_000L
                            recoverFromDistance = currentDistance
                            recoverFromPitch = pitch
                        }
                        if (now >= recoverHoldUntil) {
                            if (recoverStart == 0L) {
                                recoverStart = now
                            }
                            val t = ((now - recoverStart) / 1_200f).coerceIn(0f, 1f)
                            val ease = easeInOutCubic(t)
                            currentDistance = recoverFromDistance +
                                    (defaultDistance - recoverFromDistance) * ease
                            // 只把俯仰角回正，保留当前水平朝向；不要重置到背面。
                            pitch = recoverFromPitch * (1f - ease)
                            if (t >= 1f) {
                                recovering = false
                                pitch = 0f
                                rotateSpeed = 20f
                            }
                        }
                    }
                } else {
                    yaw += 20f * dt
                }
            }
            updateCamera()
            // destroy() 与本帧可能交错（主线程），访问 native 前后再确认一次。
            if (destroyed) return
            runCatching { viewer.render(frameTimeNanos) }
                .onFailure { Log.e("OppoPods-3D", "render failed", it) }
            if (!destroyed && running) {
                choreographer.postFrameCallback(this)
            }
        }
    }

    init {
        // ModelViewer 内部注册的 detach 监听器会自行 destroy 整个 Engine
        // （栈：onViewDetachedFromWindow → ModelViewer.destroy → destroyModel）。
        // 我们只需要在那之前停掉帧回调，绝不能重复调用 viewer.destroy()。
        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) = Unit

            override fun onViewDetachedFromWindow(v: android.view.View) {
                viewDetached = true
                stop()
                destroyed = true
                choreographer.removeFrameCallback(frameCallback)
                // 此时 Engine 即将/已被 ModelViewer 释放，不能再触碰 scene/engine。
                indirectLight = null
                indirectLightTexture = null
            }
        })
        viewer.view.blendMode = View.BlendMode.TRANSLUCENT
        viewer.view.ambientOcclusion = View.AmbientOcclusion.SSAO
        viewer.view.ambientOcclusionOptions = viewer.view.ambientOcclusionOptions.apply {
            enabled = model.config.enableSSAO
        }
        viewer.view.isShadowingEnabled = model.config.enableShadow
        viewer.view.bloomOptions = viewer.view.bloomOptions.apply {
            enabled = model.config.enableBloom
        }
        viewer.renderer.clearOptions = Renderer.ClearOptions().apply {
            clear = true
            setClearColor(0.0, 0.0, 0.0, 0.0)
        }
        viewer.loadModelGlb(directBuffer(model.opaqueGlb()))
        applyUnitCubeTransform(model.config.modelScale)
        model.ibl?.let { applyIndirectLight(it, model.config) }
        applyMaterials(model.config)
        applyLight(model.config)
    }

    /** 外部控制自转开关：未连接时停在当前角度。 */
    fun setAutoRotate(enabled: Boolean) {
        autoRotate = enabled
    }

    fun beginTouch() {
        autoRotate = false
        recovering = false
        recoverStart = 0L
        recoverHoldUntil = 0L
        targetSlideX = 0f
        targetSlideY = 0f
    }

    fun startDrag() {
        autoRotate = false
        recovering = false
        recoverStart = 0L
        recoverHoldUntil = 0L
    }

    fun drag(dx: Float, dy: Float) {
        // 欢律把每个 MotionEvent 的像素 delta 作为 targetSlide，
        // 水平 360° 环绕，垂直钳制在 ±85°。
        targetSlideX = dx
        targetSlideY = dy
    }

    fun zoom(scale: Float) {
        if (scale <= 0f) return
        targetDistance = (targetDistance / scale).coerceIn(minDistance, maxDistance)
    }

    fun endDrag() {
        targetSlideX = 0f
        targetSlideY = 0f
        targetDistance = currentDistance
        recoverFromDistance = currentDistance
        recovering = true
        recoverStart = 0L
        recoverHoldUntil = 0L
        autoRotate = true
    }

    fun cancelTouch() {
        targetSlideX = 0f
        targetSlideY = 0f
        recovering = true
        recoverStart = 0L
        recoverHoldUntil = 0L
        autoRotate = true
    }

    fun start() {
        if (running || destroyed) return
        running = true
        choreographer.postFrameCallback(frameCallback)
    }

    fun stop() {
        running = false
        choreographer.removeFrameCallback(frameCallback)
    }

    fun destroy() {
        // 崩溃根因（tombstone 已确认）：
        //   gltfio::ResourceLoader::asyncCancelLoad()
        //     ← ModelViewer.destroyModel ← ModelViewer.destroy
        //     ← ModelViewer$addDetachListener$1.onViewDetachedFromWindow
        // ModelViewer 自己在 TextureView detach 时就调用了 destroy()，而
        // Filament 1.77.3 的 destroy() 没有幂等保护；我们再调一次，第二次会
        // 访问已经释放的 ResourceLoader/Engine，于是 SIGSEGV(SEGV_MAPERR)。
        //
        // 所以这里**绝不**调用 viewer.destroy()/engine.destroy()，只负责
        // 停帧回调 + 收掉我们自己创建的 IBL，剩下的资源交给 ModelViewer 自己回收。
        if (destroyed) return
        destroyed = true
        stop()
        choreographer.removeFrameCallback(frameCallback)
        destroyIndirectLight()
    }

    private fun destroyIndirectLight() {
        if (!viewerDelegate.isInitialized()) return
        // IBL 是我们自己创建的，必须在 Engine 还活着时收回。
        // 但 ModelViewer 在 detach 时会先销毁整个 Engine——那时再碰
        // engine.destroyIndirectLight / scene 都会踩到已释放的 native 对象，
        // 所以窗口已 detach 的情况下直接放弃清理（Engine 一起没了，无需回收）。
        if (viewDetached) {
            indirectLight = null
            indirectLightTexture = null
            return
        }
        runCatching { viewer.scene.indirectLight = null }
        indirectLight?.let { runCatching { engine.destroyIndirectLight(it) } }
        indirectLightTexture?.let { runCatching { engine.destroyTexture(it) } }
        indirectLight = null
        indirectLightTexture = null
    }

    private fun updateCamera() {
        if (view.width <= 0 || view.height <= 0) return
        val aspect = view.width.toDouble() / view.height.toDouble()
        viewer.camera.setProjection(45.0, aspect, 0.5, 100.0, Camera.Fov.VERTICAL)
        val yawRad = Math.toRadians(yaw.toDouble())
        val pitchRad = Math.toRadians(pitch.toDouble())
        val eyeX = target[0] + currentDistance * kotlin.math.cos(pitchRad) * kotlin.math.sin(yawRad)
        val eyeY = target[1] + currentDistance * kotlin.math.sin(pitchRad)
        val eyeZ = target[2] + currentDistance * kotlin.math.cos(pitchRad) * kotlin.math.cos(yawRad)
        viewer.camera.lookAt(
            eyeX.toDouble(),
            eyeY.toDouble(),
            eyeZ.toDouble(),
            target[0].toDouble(),
            target[1].toDouble(),
            target[2].toDouble(),
            0.0,
            1.0,
            0.0,
        )
    }

    private fun applyUnitCubeTransform(modelScale: Float) {
        val asset = viewer.asset ?: return
        val centerPoint = Float3(0f, 0f, -4f)
        val bbox = asset.boundingBox
        val center = Float3(bbox.center[0], bbox.center[1], bbox.center[2])
        val halfExtent = Float3(bbox.halfExtent[0], bbox.halfExtent[1], bbox.halfExtent[2])
        val maxExtent = 2f * maxOf(halfExtent.x, halfExtent.y, halfExtent.z)
        // 官方把模型塞进单位立方体再乘 config 的 modelScale，这里保持同样的换算；
        // 观感上的大小由相机距离（官方默认 5.0）决定，不再额外缩放。
        val scaleFactor = (2f / maxExtent) * modelScale.coerceAtLeast(0.01f)
        val adjustedCenter = center - centerPoint / scaleFactor
        val transform = scale(Float3(scaleFactor)) * translation(-adjustedCenter)
        val manager = engine.transformManager
        manager.setTransform(
            manager.getInstance(asset.root),
            transpose(transform).toFloatArray(),
        )
    }

    private fun applyIndirectLight(bytes: ByteArray, config: VfxModel.Config) {
        val bundle = runCatching {
            KTX1Loader.createIndirectLight(engine, directBuffer(bytes))
        }.getOrNull() ?: return
        val light = bundle.indirectLight ?: return
        light.intensity = config.iblIntensity
        if (config.iblAngle.isNotEmpty()) {
            light.setRotation(rotationY(config.iblAngle.getOrElse(1) { 0f }))
        }
        viewer.scene.indirectLight = light
        indirectLight = light
        indirectLightTexture = bundle.cubemap
    }

    private fun applyMaterials(config: VfxModel.Config) {
        val asset = viewer.asset ?: return
        val instances = asset.instance.materialInstances
        for (material in config.materials) {
            for (instance in instances) {
                if (instance.name != material.name) continue
                for (property in material.properties) {
                    when (property.type) {
                        4 -> runCatching { instance.setParameter(property.name, property.value) }
                        7 -> runCatching {
                            instance.setParameter(
                                property.name,
                                property.color[0],
                                property.color[1],
                                property.color[2],
                                property.color[3],
                            )
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun applyLight(config: VfxModel.Config) {
        val manager = engine.lightManager
        val instance = manager.getInstance(viewer.light)
        manager.setIntensity(instance, config.lightIntensity)
        if (config.lightDirection.size >= 3 && config.lightDirection.any { it != 0f }) {
            manager.setDirection(
                instance,
                config.lightDirection[0],
                config.lightDirection[1],
                config.lightDirection[2],
            )
        }
    }

    private fun shortestAngleDelta(from: Float, to: Float): Float {
        var delta = (to - from) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return delta
    }

    private fun easeInOutCubic(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return if (t < 0.5f) {
            4f * t * t * t
        } else {
            val u = -2f * t + 2f
            1f - (u * u * u) / 2f
        }
    }

    private fun rotationY(degrees: Float): FloatArray {
        val radians = Math.toRadians(degrees.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        return floatArrayOf(
            c, 0f, s,
            0f, 1f, 0f,
            -s, 0f, c,
        )
    }

    private fun directBuffer(bytes: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            flip()
        }

    private fun createEngine(): Engine {
        // Filament 的 JNI 库不会在 Engine 类加载时自动装载：Filament.init() 只装
        // filament-jni，gltfio/Utils 各自还有独立 .so。漏掉任何一步都会在
        // Engine.nCreateBuilder 处报 No implementation found。
        Filament.init()
        Gltfio.init()
        Utils.init()
        return Engine.create()
    }
}
