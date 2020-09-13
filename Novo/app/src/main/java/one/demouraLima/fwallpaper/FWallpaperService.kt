package one.demouraLima.fwallpaper

import android.graphics.*
import android.net.Uri
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.util.Size
import android.view.SurfaceHolder
import androidx.core.graphics.toRectF
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.sendBlocking
import kotlin.coroutines.coroutineContext
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

class FWallpaperService : WallpaperService() {
    enum class ScaleType {
        CROP_START,
        CROP_CENTER,
        CROP_END,
        MATRIX_FILL,
        MATRIX_START,
        MATRIX_CENTER,
        MATRIX_END
    }

    enum class Orientation {
        Landscape,
        Portrait
    }

    override fun onCreateEngine(): Engine {
        val prefStorage = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val folder = prefStorage.getString("source_folder", null) ?: return BlankEngine()
        val width = prefStorage.getInt("grid_width", 2)
        val height = prefStorage.getInt("grid_height", 4)
        val delay = prefStorage.getInt("delay", 1)
        val scaleType = kotlin.runCatching { ScaleType.valueOf(prefStorage.getString("scale_type", null)!!) }
            .getOrDefault(ScaleType.MATRIX_CENTER)

        val uri = try {
            Uri.parse(folder)
        } catch (e: Exception) {
            Log.w("FWallpaper", "Failed to parse uri due to ${e.message}")
            return BlankEngine()
        }

        return FEngine(uri, Size(width, height), scaleType, delay)
    }

    // Used when there are no images to display.
    inner class BlankEngine() : Engine() {}

    inner class FEngine(
        sourceFolder: Uri,
        private val gridSize: Size,
        private val scaleType: ScaleType,
        private val delaySeconds: Int
    ) : Engine() {
        private val bitmapManager = BitmapManager(sourceFolder, contentResolver)
        private lateinit var engineScope: CoroutineScope
        private lateinit var surfaceScope: CoroutineScope
        private var initialLoadRoutine: Job? = null
        private var drawRoutine: Job? = null
        private val visibilityChange = Channel<Boolean>(Channel.CONFLATED)

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            engineScope = CoroutineScope(Dispatchers.Main)

            initialLoadRoutine = engineScope.launch(Dispatchers.IO) {
                bitmapManager.loadPhotoIds()
                bitmapManager.loadNextBitmaps(gridSize.width * gridSize.height, null)
            }
        }

        override fun onDestroy() {
            if (engineScope.isActive) {
                engineScope.cancel()
            }

            super.onDestroy()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            Log.d("FWallpaper", "onSurfaceCreated called")
            super.onSurfaceCreated(holder)
            surfaceScope = CoroutineScope(Dispatchers.Main)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            Log.d("FWallpaper", "onSurfaceDestroyed called")
            if (surfaceScope.isActive) {
                surfaceScope.cancel()
            }

            super.onSurfaceDestroyed(holder)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            Log.d(
                "FWallpaper",
                "onSurfaceChanged called with format: $format, width: $width, height: $height. Creating ${holder?.isCreating}"
            )
//            Log.d("FWallpaper", "Desired size: ${desiredMinimumHeight}x${desiredMinimumWidth}")
            super.onSurfaceChanged(holder, format, width, height)
        }

        @ExperimentalTime
        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder?) {
            Log.d("FWallpaper", "onSurfaceRedrawNeeded called with holder: $holder. Creating: ${holder?.isCreating}")
            super.onSurfaceRedrawNeeded(holder)
            if (holder == null) return

            if (initialLoadRoutine != null) {
                initialLoadRoutine?.invokeOnCompletion {
                    drawRoutine = surfaceScope.launch {
                        draw(holder)
                        drawingLoop(holder)
                    }
                }
                initialLoadRoutine = null
            } else {
                drawRoutine?.cancel()

                runBlocking {
                    draw(holder)
                }

                drawRoutine = surfaceScope.launch { drawingLoop(holder) }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            Log.d("FWallpaper", "onVisibilityChanged called with $visible")
            super.onVisibilityChanged(visible)
            visibilityChange.sendBlocking(visible)
        }

        private suspend fun suspendWhileInvisible() {
            if (visibilityChange.poll() == false) {
                do {
                    Log.d("FWallpaper", "Receiving visible false, will wait for true")
                    val visible = visibilityChange.receive()
                } while (!visible)
            }
        }

        @ExperimentalTime
        private suspend fun drawingLoop(holder: SurfaceHolder) {
            do {
                suspendWhileInvisible()
                Log.d("FWallpaper", "Preparing to load next bitmaps.")
                bitmapManager.loadNextBitmaps(gridSize.width * gridSize.height, null)

                delay(delaySeconds.seconds)

                suspendWhileInvisible()
                draw(holder)
            } while (coroutineContext.isActive)
        }

        private fun getGridRect(canvasSize: Size, gridPos: Point): Rect {
            val rectSize = Size(canvasSize.width / gridSize.width, canvasSize.height / gridSize.height)
            val rectStart = Point(rectSize.width * gridPos.x, rectSize.height * gridPos.y)

            return Rect(rectStart.x, rectStart.y, rectStart.x + rectSize.width, rectStart.y + rectSize.height)
        }

        private fun drawBitmap(canvas: Canvas, bitmap: Bitmap, gridPos: Point) {
            val dstRect = getGridRect(Size(canvas.width, canvas.height), gridPos)

            val crop = { cropFocus: (Int, Int) -> Int ->
                val dstAspectRatio = dstRect.width().toFloat() / dstRect.height().toFloat()
                val bitmapAspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()

                val srcRect = if (dstAspectRatio > bitmapAspectRatio) {
                    // If destination is wider than bitmap, recalculate height to "zoom in".
                    val newSrcHeight = (bitmap.width / dstAspectRatio).toInt()
                    val newTop = cropFocus(bitmap.height, newSrcHeight)

                    Rect(0, newTop, bitmap.width, newTop + newSrcHeight)
                } else {
                    // If destination is taller than bitmap, recalculate width "zoom in".
                    val newSrcWidth = (bitmap.height * dstAspectRatio).toInt()
                    val newLeft = cropFocus(bitmap.width, newSrcWidth)

                    Rect(newLeft, 0, newLeft + newSrcWidth, bitmap.height)
                }

                canvas.drawBitmap(bitmap, srcRect, dstRect, null)
            }

            val drawWithMatrix = { scaleToFit: Matrix.ScaleToFit ->
                val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
                val scaleMatrix = Matrix().apply { setRectToRect(srcRect.toRectF(), dstRect.toRectF(), scaleToFit) }
                canvas.drawBitmap(bitmap, scaleMatrix, null)
            }

            when (scaleType) {
                ScaleType.CROP_START -> crop() { _, _ -> 0 }
                ScaleType.CROP_CENTER -> crop() { oldVal, newVal -> (oldVal - newVal) / 2 }
                ScaleType.CROP_END -> crop() { oldVal, newVal -> oldVal - newVal }
                ScaleType.MATRIX_START -> drawWithMatrix(Matrix.ScaleToFit.START)
                ScaleType.MATRIX_CENTER -> drawWithMatrix(Matrix.ScaleToFit.CENTER)
                ScaleType.MATRIX_END -> drawWithMatrix(Matrix.ScaleToFit.END)
                ScaleType.MATRIX_FILL -> drawWithMatrix(Matrix.ScaleToFit.FILL)
            }
        }

        private suspend fun draw(holder: SurfaceHolder) {
            Log.d("FWallpaper", "draw called")

            val canvas = try {
                val c = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    holder.lockHardwareCanvas()
                } else {
                    holder.lockCanvas()
                }

                if (c == null) {
                    Log.d("FWallpaper", "Couldn't lock canvas")
                    return
                }
                c
            } catch (e: Exception) {
                Log.e("FWallpaper", "Failed to lock canvas due to ${e.message}")
                return
            }

            Log.d("FWallpaper", "Drawing with canvas ${canvas.width}x${canvas.height}")
            val bitmaps = bitmapManager.getBitmaps();
            try {
                canvas.drawRGB(255, 255, 255)

                for (i in 0 until gridSize.width) {
                    for (j in 0 until gridSize.height) {
                        if (coroutineContext.isActive) {
                            val index = i * gridSize.height + j
                            val bitmap = if (index < bitmaps.size) bitmaps[index] else null

                            if (bitmap != null) {
                                drawBitmap(canvas, bitmap, Point(i, j))
                            } else {
                                val rect = getGridRect(Size(canvas.width, canvas.height), Point(i, j))
                                canvas.drawText("Missing Image", rect.left.toFloat(), rect.exactCenterY(), Paint())
                            }
                        }
                    }
                }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }
}
