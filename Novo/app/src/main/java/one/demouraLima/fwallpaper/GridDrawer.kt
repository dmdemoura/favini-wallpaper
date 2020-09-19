package one.demouraLima.fwallpaper

import android.content.Context
import android.graphics.*
import android.text.StaticLayout
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toRectF
import androidx.core.graphics.withTranslation
import kotlinx.coroutines.isActive
import java.time.format.TextStyle
import kotlin.coroutines.coroutineContext

enum class ScaleType {
    CROP_START,
    CROP_CENTER,
    CROP_END,
    MATRIX_FILL,
    MATRIX_START,
    MATRIX_CENTER,
    MATRIX_END
}

private fun getGridRect(canvasSize: Size, gridPos: Point, gridSize: Size): Rect {
    val rectSize = Size(canvasSize.width / gridSize.width, canvasSize.height / gridSize.height)
    val rectStart = Point(rectSize.width * gridPos.x, rectSize.height * gridPos.y)

    return Rect(rectStart.x, rectStart.y, rectStart.x + rectSize.width, rectStart.y + rectSize.height)
}

private fun drawBitmap(canvas: Canvas, bitmap: Bitmap, gridPos: Point, gridSize: Size, scaleType: ScaleType) {
    val dstRect = getGridRect(Size(canvas.width, canvas.height), gridPos, gridSize)

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

private fun Canvas.drawTextCentered(ctx: Context, text: String, rect: Rect) {
    val textView = TextView(ctx)
    textView.height = rect.height()
    textView.width = rect.width()
    textView.gravity = Gravity.CENTER
    textView.textSize = 22f
    textView.text = text
    textView.setTextColor(Color.BLACK)
    textView.measure(rect.width(), rect.height())
    textView.layout(rect.left, rect.top, rect.right, rect.bottom)

    withTranslation(rect.left.toFloat(), rect.top.toFloat()) {
        textView.draw(this)
    }
}

suspend fun drawGrid(holder: SurfaceHolder, ctx: Context, bitmaps: List<Bitmap?>?, gridSize: Size, scaleType: ScaleType) {
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
    try {
        canvas.drawRGB(255, 255, 255)

        if (bitmaps == null) {
            val rect = Rect(0, 0, canvas.width, canvas.height)
            canvas.drawTextCentered(ctx, "No images: Set source folder", rect)
            return
        }

        for (i in 0 until gridSize.width) {
            for (j in 0 until gridSize.height) {
                if (coroutineContext.isActive) {
                    val index = i * gridSize.height + j
                    val bitmap = if (index < bitmaps.size) bitmaps[index] else null

                    if (bitmap != null) {
                        drawBitmap(canvas, bitmap, Point(i, j), gridSize, scaleType)
                    } else {
                        val rect = getGridRect(Size(canvas.width, canvas.height), Point(i, j), gridSize)
                        canvas.drawTextCentered(ctx, "Missing Image", rect)
                    }
                }
            }
        }
    } finally {
        holder.unlockCanvasAndPost(canvas)
    }
}
