package one.demouraLima.fwallpaper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.service.wallpaper.*
import android.util.Log
import android.view.SurfaceHolder
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import java.io.FileDescriptor
import java.lang.Exception

class FWallpaperService: WallpaperService() {

    override fun onCreateEngine(): Engine {
        val prefStorage = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val folder = prefStorage.getString("Source Folder", null) ?: return BlankEngine()

        val uri = try {
            Uri.parse(folder)
        } catch(e: Exception) {
            Log.w("FWallpaper", "Failed to parse uri due to ${e.message}")
           return BlankEngine()
        }

        return FEngine(uri)
    }

    // Used when there are no images to display.
    inner class BlankEngine() : Engine() {}

    inner class FEngine(private val sourceFolder: Uri): Engine() {
//        override fun onSurfaceCreated(holder: SurfaceHolder) {
//            super.onSurfaceCreated(holder)
//            draw(holder)
//        }
//
        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            draw(holder)
        }
//
//        override fun onSurfaceChanged(
//            holder: SurfaceHolder,
//            format: Int,
//            width: Int,
//            height: Int
//        ) {
//            super.onSurfaceChanged(holder, format, width, height)
//            draw(holder)
//        }
//
//        override fun onVisibilityChanged(visible: Boolean) {
//            super.onVisibilityChanged(visible)
//
//            if (this.visible != visible && visible)
//            {
//                this.visible = visible
//                draw(surfaceHolder)
//            }
//        }

        private fun getBitmapFromUri(uri: Uri): Bitmap? {
            val parcelFileDescriptor: ParcelFileDescriptor =
                applicationContext.contentResolver
                    .openFileDescriptor(uri, "r") ?: return null

            val fileDescriptor: FileDescriptor = parcelFileDescriptor.fileDescriptor
            val image: Bitmap = BitmapFactory.decodeFileDescriptor(fileDescriptor)
            parcelFileDescriptor.close()

            return image
        }

        private fun draw(holder: SurfaceHolder) {
            val sourceFolderDoc = DocumentFile.fromTreeUri(applicationContext, this.sourceFolder) ?: return
            val photoDocuments: Array<DocumentFile> = sourceFolderDoc.listFiles()

            Log.d("FWallpaper", "Found ${photoDocuments.size} photos.")
            if (photoDocuments.size > 1) {
                val photoDocument = photoDocuments[0]
                Log.d("FWallpaper", "Preparing to draw ${photoDocument.name}.")
                val bitmap = getBitmapFromUri(photoDocument.uri)
                if (bitmap == null) {
                    Log.d("FWallpaper", "Bitmap is null")
                    return
                }
                Log.d("FWallpaper", "Photo has size of ${bitmap.width}x${bitmap.height}")

                val canvas = try {
                    holder.lockCanvas()
                } catch (e: Exception) {
                    Log.e("FWallpaper", "Failed to lock canvas due to ${e.message}")
                    return
                }

                val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
                val dstRect = Rect(0, 0, canvas.width, canvas.height)

                canvas.drawBitmap(bitmap, srcRect, dstRect,null)

                holder.unlockCanvasAndPost(canvas)
            }
        }
    }
}
