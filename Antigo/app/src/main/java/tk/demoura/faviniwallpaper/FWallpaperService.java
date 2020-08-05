package tk.demoura.faviniwallpaper;
import android.graphics.*;
import android.os.*;
import android.preference.*;
import android.service.wallpaper.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import android.util.*;

public class FWallpaperService extends WallpaperService
{
	@Override
    public void onCreate(){
        super.onCreate();
    }
    @Override
    public void onDestroy(){
        super.onDestroy();
    }
	@Override
	public WallpaperService.Engine onCreateEngine()
	{
		File f = new File(PreferenceManager.getDefaultSharedPreferences(this).getString("folder",""));
		int d = Integer.parseInt(PreferenceManager.getDefaultSharedPreferences(this).getString("delay",""));
		return new FWallpaperEngine(f, d);
	}
	private class FWallpaperEngine extends Engine {
		private File folder;
		private int delay;
		private int cycles =0;
		private boolean mVisible = true;
		private Handler mHandler = new Handler();
		private Runnable mDrawRunner = new Runnable() {
            @Override
            public void run() {
                draw();
            }
        };
		public FWallpaperEngine(File folder, int delay){
			this.folder = folder;
			this.delay = delay;
		}
		@Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
        }
		@Override
		public void onDestroy() {
			super.onDestroy();
			mHandler.removeCallbacks(mDrawRunner);
		}
		@Override
		public void onVisibilityChanged(boolean visible)
		{
			mVisible = visible;
			if(visible){
				draw();
			}else{
				mHandler.removeCallbacks(mDrawRunner);
			}
	    }
		@Override
        public void onSurfaceDestroyed(SurfaceHolder holder){
            super.onSurfaceDestroyed(holder);
            mVisible = false;
            mHandler.removeCallbacks(mDrawRunner);
        }
		void draw(){
			SurfaceHolder holder = getSurfaceHolder();
			Canvas canvas = holder.lockCanvas();
			try {
				int rWidth = canvas.getWidth()/2;
				int rHeight = (int)(rWidth*0.71D);
				Log.d("tk.demoura",""+canvas.getHeight()+" "+canvas.getWidth());
				List<Rect> rects = new ArrayList<>();
				int rAssembled = 0;
				while(rAssembled<10){
					int c;
					int r;
					if(rAssembled<5){
						c = 0;
						r = rAssembled;
					}else{
						c = 1;
						r = rAssembled - 5;
					}
					int left = c*rWidth;
					int top = r*rHeight;
					int right = left + rWidth;
					int bottom = top + rHeight;
					rects.add(new Rect(left,top,right,bottom));
					rAssembled++;
					Log.d("tk.demoura","assembled"+rects.toString());
				}
				int rFilled = 0;
				for(Rect r:rects){
					int i = 9*cycles + rFilled;
					if(i + 1> folder.listFiles().length){
						cycles = 0;
						i = rFilled;
						Log.d("tk.demoura","cycle reset");
					}
					Bitmap b =BitmapFactory.decodeFile(folder.listFiles()[i].getAbsolutePath());
					Rect s;
					if((double)b.getWidth()/(double)b.getHeight()>=1.4D){
				    	int hSize = (int)(b.getHeight()*1.4D);
						int left = (b.getWidth()-hSize)/2;
						int top = 0;
						int right = left + hSize;
						int bottom = top + b.getHeight();
						s = new Rect(left,top,right,bottom);
					}else{
						int vSize = (int)(b.getWidth()*0.7D);
						int left = 0;
						int top = (b.getHeight()-vSize)/2;
						int right = left + b.getWidth();
						int bottom = top + vSize;
						s = new Rect(left,top,right,bottom);
					}				
					//Log.d("tk.demoura", s.toString());
					canvas.drawBitmap(b,s,r,null);
					rFilled++;
					Log.d("tk.demoura","Filled "+i);
				}
				cycles++;
				Log.d("tk.demoura","cycled");
				Log.d("tk.demoura", Environment.getExternalStorageState() + " ");
			    Log.d("tk.demoura", folder.canExecute() + " " + folder.canRead() + " " + folder.canWrite());
			}finally{
				if(canvas != null){
					holder.unlockCanvasAndPost(canvas);
				}
			}
			mHandler.removeCallbacks(mDrawRunner);
			if(mVisible){
				mHandler.postDelayed(mDrawRunner, delay*1000);
			}
		}
	}
}
