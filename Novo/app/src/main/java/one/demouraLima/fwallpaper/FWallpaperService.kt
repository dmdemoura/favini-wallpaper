package one.demouraLima.fwallpaper

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.util.Size
import android.view.SurfaceHolder
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.sendBlocking
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

@ExperimentalTime
class FWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return FEngine()
    }

    @ExperimentalTime
    private fun getPrefDelay(preferences: SharedPreferences): Duration {
        return preferences.getInt("delay",
            applicationContext.resources.getInteger(R.integer.default_prefs_delay)
        ).seconds
    }

    private fun getPrefSize(preferences: SharedPreferences): Size {
        return Size(
            preferences.getInt("grid_width",
                applicationContext.resources.getInteger(R.integer.default_prefs_grid_height)),
            preferences.getInt("grid_height",
                applicationContext.resources.getInteger(R.integer.default_prefs_grid_height)))
    }

    private fun getPrefScaleType(preferences: SharedPreferences): ScaleType {
        return ScaleType.valueOf(preferences.getString("scale_type",
            applicationContext.resources.getString(R.string.default_prefs_scale_type))!!)
    }

    private fun getPrefSourceFolder(preferences: SharedPreferences): Uri? {
        val folder = preferences.getString("source_folder", null)

        return try {
            Uri.parse(folder)
        } catch (e: Exception) {
            Log.e("FWallpaper", "Failed to parse uri due to ${e.message}")
            return null
        }
    }

    abstract class ReloadType {
        class None : ReloadType() {}
        class LoadMoreBitmaps(val count: Int) : ReloadType() {}
        class RewindBitmaps(val count: Int) : ReloadType() {}
        class Full : ReloadType() {}
    }

    inner class FEngine : Engine() {
        private var gridSize: Size
        private var scaleType: ScaleType
        private var delay: Duration
        private var bitmapManager: BitmapManager? = null

        private lateinit var surfaceScope: CoroutineScope
        private var initialLoadRoutine: Deferred<List<Bitmap?>>? = null
        private var drawRoutine: Job? = null
        private val visibilityChange = Channel<Boolean>(Channel.CONFLATED)

        private val coroutineExceptionHandler = CoroutineExceptionHandler { _, ex ->
            Log.e("FWallpaper", "Coroutine exception: ${ex.message}", ex)
        }

        init {
            val preferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            gridSize = getPrefSize(preferences)
            delay = getPrefDelay(preferences)
            scaleType = getPrefScaleType(preferences)
            getPrefSourceFolder(preferences)?.also {
                bitmapManager = BitmapManager(it , contentResolver)
            }
        }

        private fun reloadPreferences(): ReloadType {
            val preferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            delay = getPrefDelay(preferences)
            scaleType = getPrefScaleType(preferences)

            var reloadType: ReloadType = ReloadType.None()
            gridSize = getPrefSize(preferences).also {
                // If grid changed to a smaller size no need to reload
                val oldCount = gridSize.width * gridSize.height
                val newCount = it.width * it.height
                if (newCount > oldCount) {
                    reloadType = ReloadType.LoadMoreBitmaps(newCount - oldCount)
                } else if (newCount < oldCount) {
                    reloadType = ReloadType.RewindBitmaps(oldCount - newCount)
                }
            }

            getPrefSourceFolder(preferences)?.also {
                if (bitmapManager?.sourceFolder != it) {
                    bitmapManager = BitmapManager(it , contentResolver)
                    reloadType = ReloadType.Full()
                }
            }

            return reloadType
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            Log.d("FWallpaper", "onSurfaceCreated called")
            super.onSurfaceCreated(holder)
            surfaceScope = CoroutineScope(Dispatchers.Main)

            initialLoadRoutine = bitmapManager?.run {
                surfaceScope.async(Dispatchers.IO) {
                    loadPhotoIds()
                    loadNextBitmaps(gridSize.width * gridSize.height,
                        getGridRectSize(holder.surfaceFrame, gridSize),
                        null)
                }
            }
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

            if (initialLoadRoutine != null && drawRoutine == null) {
                drawRoutine = surfaceScope.launch(coroutineExceptionHandler) {
                    val bitmaps = initialLoadRoutine!!.await()
                    initialLoadRoutine = null
                    drawGrid(holder, applicationContext, bitmaps, gridSize, scaleType)
                    drawingLoop(holder)
                }
            } else if (initialLoadRoutine == null) {
                drawRoutine?.let {
                    it.cancel()
                    drawRoutine = null
                    Log.d("FWallpaper", "Cancelled draw routine");
                }

                runBlocking {
                    drawGrid(holder, applicationContext, bitmapManager?.getBitmaps(), gridSize, scaleType)
                }

                drawRoutine = surfaceScope.launch(coroutineExceptionHandler) { drawingLoop(holder) }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            Log.d("FWallpaper", "onVisibilityChanged called with $visible")
            super.onVisibilityChanged(visible)
            visibilityChange.sendBlocking(visible)
        }

        private suspend fun suspendWhileInvisible(): Boolean {
            if (visibilityChange.poll() == false) {
                do {
                    Log.d("FWallpaper", "Receiving visible false, will wait for true")
                    val visible = visibilityChange.receive()
                    Log.d("FWallpaper", " Got visible: $visible")
                } while (!visible)
                Log.d("FWallpaper", "Resuming")
                return true
            }
            return false
        }

        @ExperimentalTime
        private suspend fun drawingLoop(holder: SurfaceHolder) {
            do {
                if (suspendWhileInvisible()) {
                    if (reloadPreferences() is ReloadType.Full) {
                        Log.d("FWallpaper", "Full reload.")
                        bitmapManager?.loadPhotoIds()
                    }
                }

                Log.d("FWallpaper", "Preparing to load next bitmaps.")

                // Keep a local, as the member variable might change between loading and drawing.
                var gridSize = this.gridSize
                var bitmaps = bitmapManager?.loadNextBitmaps(
                    gridSize.width * gridSize.height,
                    getGridRectSize(surfaceHolder.surfaceFrame, gridSize),
                    null
                )

                Log.d("FWallpaper", "Loaded next bitmaps.")
                delay(delay)

                if (suspendWhileInvisible()) { // If we go invisible.
                    val reloadType = reloadPreferences() // Reload prefs, and check what needs to be fixed.

                    // Update grid size
                    gridSize = this.gridSize
                    when (reloadType) {
                        is ReloadType.Full -> {
                            bitmapManager?.loadPhotoIds()
                            bitmapManager?.loadNextBitmaps(
                                gridSize.width * gridSize.height,
                                getGridRectSize(surfaceHolder.surfaceFrame, gridSize),
                                null)
                        }
                        is ReloadType.LoadMoreBitmaps -> {
                            // Grid size was increased.
                            bitmaps = bitmapManager?.loadMoreBitmaps(
                                reloadType.count,
                                getGridRectSize(surfaceHolder.surfaceFrame, gridSize),
                                null)
                        }
                        is ReloadType.RewindBitmaps -> {
                            // Grid size was reduced, re add some of the loaded bitmaps to nextBitmaps.
                            bitmapManager?.rewind(reloadType.count)
                        }
                    }
                }
                drawGrid(holder, applicationContext, bitmaps, gridSize, scaleType)
            } while (coroutineContext.isActive)
        }
    }
}

private fun getGridRectSize(canvasSize: Rect, gridSize: Size): Size {
    return Size(canvasSize.width() / gridSize.width, canvasSize.height() / gridSize.height)
}
