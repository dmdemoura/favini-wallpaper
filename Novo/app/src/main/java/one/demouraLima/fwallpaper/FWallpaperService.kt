package one.demouraLima.fwallpaper
import android.service.wallpaper.*
import java.io.File

class FWallpaperService: WallpaperService() {

    override fun onCreateEngine(): Engine {
        return FEngine();
    }

    inner class FEngine: Engine(){

    }
}
