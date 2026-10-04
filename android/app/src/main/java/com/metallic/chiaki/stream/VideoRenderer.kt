// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.app.ActivityManager
import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GLSurfaceView.Renderer that draws the video through shaders: debanding ("Smooth gradients") and super
 * resolution, which upscales it to the view's size with Snapdragon Game Super Resolution 1 (SGSR).
 * Frames from MediaCodec arrive in a SurfaceTexture and go through up to three passes:
 * 1. the decoder's external texture is copied to a texture of the video's size, with the luma in alpha
 * 2. debanding, at the video's size: to the screen, or to a second texture when upscaling
 * 3. when upscaling: SGSR to the view's size, or bilinear scaling where the view isn't bigger than the video
 * Without upscaling, the surface must have the video's size, and the system scales it to the view.
 * Meant to be used with RENDERMODE_WHEN_DIRTY: [requestRender] is called for every new video frame.
 */
class VideoRenderer(
    private val videoWidth: Int,
    private val videoHeight: Int,
    private val deband: Boolean,
    private val sharpness: Float,
    /** Source of SGSR's fragment shader ([UPSCALE_SHADER_ASSET]), or null to not upscale */
    private val upscaleShader: String?,
    private val requestRender: () -> Unit,
    private val onSurfaceReady: (Surface) -> Unit
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    companion object {
        private const val TAG = "VideoRenderer"

        /** SGSR's fragment shader in the assets, Qualcomm's with the changes listed in its header */
        const val UPSCALE_SHADER_ASSET = "sgsr1_shader_mobile_edge_direction.frag"

        /** SGSR needs textureGather, which came with OpenGL ES 3.1 */
        fun isUpscalingSupported(context: Context) =
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .deviceConfigurationInfo.reqGlEsVersion >= 0x30001

        // 1. Copy pass (OES -> FBO)
        private const val COPY_VERTEX_SHADER = """
            #version 300 es
            in vec4 a_Position;
            in vec2 a_TexCoord;
            out highp vec2 v_TexCoord;
            uniform mat4 u_STMatrix;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = (u_STMatrix * vec4(a_TexCoord, 0.0, 1.0)).xy;
            }
        """

        // The luma goes in alpha, where SGSR looks for edges (its RGBY mode): with only green, as in
        // its RGBA mode, it would miss edges between colors of the same green, such as red on black
        private const val COPY_FRAGMENT_SHADER = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            in highp vec2 v_TexCoord;
            out vec4 outColor;
            uniform samplerExternalOES u_Texture;
            void main() {
                vec3 color = texture(u_Texture, v_TexCoord).rgb;
                outColor = vec4(color, dot(color, vec3(0.2126, 0.7152, 0.0722)));
            }
        """

        // 2. Debanding pass (FBO -> screen, or -> FBO before upscaling), and the bilinear scaling
        private const val EFFECT_VERTEX_SHADER = """
            #version 300 es
            in vec4 a_Position;
            in vec2 a_TexCoord;
            out highp vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                // FBO texture is usually upside down relative to OES coordinates
                v_TexCoord = a_TexCoord;
            }
        """

        private const val DEBAND_FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;

            in highp vec2 v_TexCoord;
            out vec4 outColor;

            uniform sampler2D u_Texture;
            uniform highp float u_Time;
            uniform highp vec2 u_ScreenSize;
            uniform highp float u_Sharpness;
            // 1 when SGSR comes next and needs the luma in alpha, 0 on the screen
            uniform float u_LumaInAlpha;

            // Stochastic Debanding v2 Parameters
            const float DEBAND_THRESHOLD = 0.02;   // Sensitivity to banding steps (lower = safer for textures)
            // 12 jittered samples on a golden angle spiral smooth gradients as well as 24 did,
            // at half the cost, which keeps 60 fps on throttled phones (e.g. in Samsung DeX)
            const int NUM_SAMPLES = 12;
            const float MAX_RADIUS = 24.0;         // Effective sampling radius
            const float GRAIN_STRENGTH = 0.003;    // Slightly reduced grain

            // Interleaved Gradient Noise
            highp float ign(vec2 v) {
                v = floor(v * u_ScreenSize);
                return fract(52.9829189 * fract(dot(v, vec2(0.06711056, 0.00583715))));
            }

            // High quality fast PRNG
            highp float rand(vec2 co, float seed) {
                return fract(sin(dot(co + seed, vec2(12.9898, 78.233))) * 43758.5453);
            }

            void main() {
                highp vec2 texelSize = 1.0 / u_ScreenSize;
                vec3 original = texture(u_Texture, v_TexCoord).rgb;

                // ═══════════════════════════════════════════════════════════════
                // STOCHASTIC DEBANDING (libplacebo-inspired)
                // ═══════════════════════════════════════════════════════════════

                vec3 sum = original;
                float totalW = 1.0;

                // Use IGN + Time for jittered sampling
                float noise = ign(v_TexCoord);
                float timeSeed = fract(u_Time * 0.1);

                // The spiral is rotated per pixel instead of computing sin/cos for every sample:
                // one rotation by noise * golden angle, then a fixed golden angle step per sample
                float startAngle = noise * 2.3999632;
                vec2 dir = vec2(cos(startAngle), sin(startAngle));
                const vec2 goldenStep = vec2(-0.7373688, 0.6754903); // cos/sin of the golden angle
                for (int i = 0; i < NUM_SAMPLES; i++) {
                    float fi = float(i);
                    float r = sqrt((fi + 0.5) / float(NUM_SAMPLES)) * MAX_RADIUS;
                    vec2 offset = dir * r * texelSize;
                    dir = vec2(dir.x * goldenStep.x - dir.y * goldenStep.y, dir.x * goldenStep.y + dir.y * goldenStep.x);
                    vec3 s = texture(u_Texture, clamp(v_TexCoord + offset, 0.0, 1.0)).rgb;

                    // Difference check: only average pixels that could be part of the same gradient
                    float diff = max(max(abs(original.r - s.r), abs(original.g - s.g)), abs(original.b - s.b));

                    // Soft threshold: skip edges, keep gradients.
                    // Lower threshold means we only blend very similar colors.
                    float w = 1.0 - smoothstep(0.0, DEBAND_THRESHOLD, diff);
                    sum += s * w;
                    totalW += w;
                }

                vec3 debanded = sum / totalW;
                vec3 color = debanded;

                // ═══════════════════════════════════════════════════════════════
                // RCAS (Robust Contrast Adaptive Sharpening)
                // ═══════════════════════════════════════════════════════════════

                if (u_Sharpness > 0.0) {
                    // Sample neighbors from the original texture for better edge detection
                    vec3 b = texture(u_Texture, v_TexCoord + vec2(0.0, -texelSize.y)).rgb;
                    vec3 d = texture(u_Texture, v_TexCoord + vec2(-texelSize.x, 0.0)).rgb;
                    vec3 f = texture(u_Texture, v_TexCoord + vec2(texelSize.x, 0.0)).rgb;
                    vec3 h = texture(u_Texture, v_TexCoord + vec2(0.0, texelSize.y)).rgb;

                    // Increased peak for more "bite"
                    float peak = -1.0 / mix(8.0, 4.0, u_Sharpness);
                    vec3 e = color;

                    vec3 minRGB = min(min(min(min(b, d), f), h), e);
                    vec3 maxRGB = max(max(max(max(b, d), f), h), e);

                    // Reduced contrast protection (0.01 instead of 0.03) to sharpen darker details better
                    vec3 amp = clamp((min(minRGB, 1.0 - maxRGB) - 0.01) / max(maxRGB, 0.01), 0.0, 1.0);
                    amp = sqrt(amp);
                    float w = peak * amp.r;

                    color = clamp(((b + d + f + h) * w + e) / (4.0 * w + 1.0), 0.0, 1.0);
                }

                // ═══════════════════════════════════════════════════════════════
                // FINAL DITHER
                // ═══════════════════════════════════════════════════════════════
                float dither = (ign(v_TexCoord + timeSeed) - 0.5) * 0.005;
                color += vec3(dither + GRAIN_STRENGTH * (rand(v_TexCoord, timeSeed) - 0.5));

                float luma = dot(clamp(color, 0.0, 1.0), vec3(0.2126, 0.7152, 0.0722));
                outColor = vec4(color, mix(1.0, luma, u_LumaInAlpha));
            }
        """

        // 3. Bilinear scaling to the screen, where SGSR isn't used (FBO -> screen)
        private const val BLIT_FRAGMENT_SHADER = """
            #version 300 es
            precision mediump float;
            in highp vec2 v_TexCoord;
            out vec4 outColor;
            uniform sampler2D u_Texture;
            void main() {
                outColor = vec4(texture(u_Texture, v_TexCoord).rgb, 1.0);
            }
        """

        // 3. SGSR (FBO -> screen). Its fragment shader is GLSL ES 3.10, and OpenGL ES only links shaders of
        // the same version
        private const val UPSCALE_VERTEX_SHADER = """
            #version 310 es
            in vec4 a_Position;
            in vec2 a_TexCoord;
            out highp vec2 in_TEXCOORD0;
            void main() {
                gl_Position = a_Position;
                in_TEXCOORD0 = a_TexCoord;
            }
        """

        // Fullscreen quad vertices (position + texcoord)
        private val QUAD_VERTICES = floatArrayOf(
            // X,    Y,    U,    V
            -1.0f, -1.0f, 0.0f, 0.0f,
             1.0f, -1.0f, 1.0f, 0.0f,
            -1.0f,  1.0f, 0.0f, 1.0f,
             1.0f,  1.0f, 1.0f, 1.0f
        )
        private const val COORDS_PER_VERTEX = 4
        private const val VERTEX_STRIDE = COORDS_PER_VERTEX * 4 // 4 bytes per float
    }

    /** What the screen showed in the last frame, for the statistics */
    enum class Upscaling { ACTIVE, NOT_NEEDED, OFF, UNSUPPORTED }

    /** Whether this renderer was made to upscale; see [upscalingAvailable] for whether it can */
    val upscaling get() = upscaleShader != null

    /** SGSR's shader works on this device; known once the GL surface exists */
    @Volatile var upscalingAvailable = false
        private set

    /** Turns super resolution off and on during the stream, to compare it with plain scaling */
    @Volatile var upscalingEnabled = true

    /** What the last frame's last pass did and the size it drew at, or null before the first frame */
    @Volatile var lastUpscaling: Pair<Upscaling, Size>? = null
        private set

    /** A linked program and where its quad attributes are */
    private class Program(val id: Int) {
        val positionLoc = GLES30.glGetAttribLocation(id, "a_Position")
        val texCoordLoc = GLES30.glGetAttribLocation(id, "a_TexCoord")
        fun uniformLoc(name: String) = GLES30.glGetUniformLocation(id, name)
    }

    /** A texture of the video's size and the framebuffer that draws into it */
    private class RenderTarget(val textureId: Int, val fboId: Int)

    private lateinit var copyProgram: Program
    private var debandProgram: Program? = null
    private var blitProgram: Program? = null
    private var upscaleProgram: Program? = null

    private var uCopySTMatrixLoc = 0
    private var uDebandTimeLoc = 0

    private var oesTextureId = 0
    /** The decoder's frame, copied */
    private lateinit var videoTarget: RenderTarget
    /** The debanded frame, when it's upscaled after */
    private var debandedTarget: RenderTarget? = null

    private var vertexBuffer: FloatBuffer? = null

    // SurfaceTexture and Surface for MediaCodec output
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null

    // Texture transform matrix
    private val stMatrix = FloatArray(16)

    // Size of the GL surface: the view's when upscaling, else the video's
    private var surfaceWidth = 1
    private var surfaceHeight = 1

    // Frames queued by the decoder that have not been latched yet
    private val pendingFrames = AtomicInteger(0)
    private var frameCount = 0f

    init {
        android.opengl.Matrix.setIdentityM(stMatrix, 0)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // New GL context: everything is created again
        copyProgram = Program(createProgram(COPY_VERTEX_SHADER, COPY_FRAGMENT_SHADER)).also {
            GLES30.glUseProgram(it.id)
            GLES30.glUniform1i(it.uniformLoc("u_Texture"), 0)
            uCopySTMatrixLoc = it.uniformLoc("u_STMatrix")
        }
        debandProgram = if (deband) {
            Program(createProgram(EFFECT_VERTEX_SHADER, DEBAND_FRAGMENT_SHADER)).also {
                GLES30.glUseProgram(it.id)
                GLES30.glUniform1i(it.uniformLoc("u_Texture"), 0)
                // Debanding works on the video's pixels, even when it's drawn to a bigger view later
                GLES30.glUniform2f(it.uniformLoc("u_ScreenSize"), videoWidth.toFloat(), videoHeight.toFloat())
                GLES30.glUniform1f(it.uniformLoc("u_Sharpness"), sharpness)
                GLES30.glUniform1f(it.uniformLoc("u_LumaInAlpha"), if (upscaling) 1f else 0f)
                uDebandTimeLoc = it.uniformLoc("u_Time")
            }
        } else null
        blitProgram = if (upscaling) {
            Program(createProgram(EFFECT_VERTEX_SHADER, BLIT_FRAGMENT_SHADER)).also {
                GLES30.glUseProgram(it.id)
                GLES30.glUniform1i(it.uniformLoc("u_Texture"), 0)
            }
        } else null
        upscaleProgram = upscaleShader?.let { createUpscaleProgram(it) }
        upscalingAvailable = upscaleProgram != null

        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        oesTextureId = textures[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        videoTarget = createRenderTarget()
        debandedTarget = if (deband && upscaling) createRenderTarget() else null

        pendingFrames.set(0)
        lastUpscaling = null
        surfaceTexture = SurfaceTexture(oesTextureId).also {
            // Buffers must have the video's size, not the screen's, and must not change while decoding
            it.setDefaultBufferSize(videoWidth, videoHeight)
            it.setOnFrameAvailableListener(this)
            surface = Surface(it)
            onSurfaceReady(surface!!)
        }

        // Create vertex buffer
        vertexBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(QUAD_VERTICES)
            .also { it.position(0) }

        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
    }

    /** SGSR's program, or null where it can't run: the video is then scaled bilinearly */
    private fun createUpscaleProgram(fragmentShader: String): Program? {
        val version = IntArray(2)
        GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, version, 0)
        GLES30.glGetIntegerv(GLES30.GL_MINOR_VERSION, version, 1)
        if (version[0] < 3 || (version[0] == 3 && version[1] < 1)) {
            Log.w(TAG, "No super resolution: SGSR needs OpenGL ES 3.1, this context is ${version[0]}.${version[1]}")
            return null
        }
        val id = try {
            createProgram(UPSCALE_VERTEX_SHADER, fragmentShader)
        } catch (e: RuntimeException) {
            Log.e(TAG, "No super resolution: SGSR's shader doesn't work here", e)
            return null
        }
        if (id == 0)
            return null
        return Program(id).also {
            GLES30.glUseProgram(it.id)
            GLES30.glUniform1i(it.uniformLoc("ps0"), 0)
            // Texel size and size of the input, as SGSR expects them
            GLES30.glUniform4f(it.uniformLoc("ViewportInfo"),
                1f / videoWidth, 1f / videoHeight, videoWidth.toFloat(), videoHeight.toFloat())
        }
    }

    private fun createRenderTarget(): RenderTarget {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val textureId = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, videoWidth, videoHeight, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glGenFramebuffers(1, ids, 0)
        val fboId = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, textureId, 0)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE)
            Log.e(TAG, "Framebuffer incomplete: 0x${Integer.toHexString(status)}")
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return RenderTarget(textureId, fboId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        // The render targets have the video's size, so a resize or rotation only changes the last pass
        surfaceWidth = width
        surfaceHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        // Latch every frame queued since the last draw, so the newest one is shown and all
        // older buffers go back to the decoder. Latching only one per draw lets a backlog build
        // up until the decoder runs out of buffers and the video stalls.
        val pending = pendingFrames.getAndSet(0)
        if (pending > 0) {
            surfaceTexture?.let {
                repeat(pending) { _ -> it.updateTexImage() }
                it.getTransformMatrix(stMatrix)
            }
        }

        // --- PASS 1: OES to FBO ---
        drawPass(copyProgram, videoTarget.fboId, videoWidth, videoHeight, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId) {
            GLES30.glUniformMatrix4fv(uCopySTMatrixLoc, 1, false, stMatrix, 0)
        }
        var frame = videoTarget.textureId

        // --- PASS 2: debanding, to the screen (at the video's size) or to the FBO that is upscaled ---
        debandProgram?.let { program ->
            val target = debandedTarget
            drawPass(program, target?.fboId ?: 0,
                if (target != null) videoWidth else surfaceWidth, if (target != null) videoHeight else surfaceHeight,
                GLES30.GL_TEXTURE_2D, frame) {
                GLES30.glUniform1f(uDebandTimeLoc, frameCount)
            }
            if (target != null)
                frame = target.textureId
        }

        // --- PASS 3: upscaling to the screen ---
        blitProgram?.let { blit ->
            val upscale = upscaleProgram
            val state = when {
                upscale == null -> Upscaling.UNSUPPORTED
                !upscalingEnabled -> Upscaling.OFF
                surfaceWidth <= videoWidth && surfaceHeight <= videoHeight -> Upscaling.NOT_NEEDED
                else -> Upscaling.ACTIVE
            }
            drawPass(if (state == Upscaling.ACTIVE && upscale != null) upscale else blit,
                0, surfaceWidth, surfaceHeight, GLES30.GL_TEXTURE_2D, frame)
            val last = lastUpscaling
            if (last == null || last.first != state || last.second.width != surfaceWidth || last.second.height != surfaceHeight)
                lastUpscaling = state to Size(surfaceWidth, surfaceHeight)
        }

        frameCount += 1.0f
        if (frameCount > 1000000f) frameCount = 0f
    }

    /** Draws the quad with [program] into [framebuffer] (0 is the screen), reading [texture] */
    private inline fun drawPass(program: Program, framebuffer: Int, width: Int, height: Int,
                                textureTarget: Int, texture: Int, setUniforms: () -> Unit = {}) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program.id)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(textureTarget, texture)
        setUniforms()

        val vertices = vertexBuffer ?: return
        vertices.position(0)
        GLES30.glVertexAttribPointer(program.positionLoc, 2, GLES30.GL_FLOAT, false, VERTEX_STRIDE, vertices)
        GLES30.glEnableVertexAttribArray(program.positionLoc)
        vertices.position(2)
        GLES30.glVertexAttribPointer(program.texCoordLoc, 2, GLES30.GL_FLOAT, false, VERTEX_STRIDE, vertices)
        GLES30.glEnableVertexAttribArray(program.texCoordLoc)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(program.positionLoc)
        GLES30.glDisableVertexAttribArray(program.texCoordLoc)
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        pendingFrames.incrementAndGet()
        requestRender()
    }

    /**
     * Releases the decoder's surface. The GL objects belong to the GL context, which GLSurfaceView
     * destroys with its thread, and this isn't called on that thread.
     */
    fun release() {
        surface?.release()
        surfaceTexture?.release()
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) return 0

        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragmentShader == 0) return 0

        val program = GLES30.glCreateProgram()
        if (program == 0) return 0

        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)
        // The program keeps what it needs, so the shaders go away with it
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES30.GL_TRUE) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw RuntimeException("Program link failed: $log")
        }

        return program
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        if (shader == 0) return 0

        // #version must be the very first thing in the source for strict compilers like Mali's
        GLES30.glShaderSource(shader, source.trimStart())
        GLES30.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] != GLES30.GL_TRUE) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $log")
        }

        return shader
    }
}
