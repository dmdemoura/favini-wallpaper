package tk.demoura.faviniwallpaper;

import android.content.*;
import android.os.*;
import android.preference.*;
import java.io.*;
import android.widget.*;

public class SettingsActivity extends PreferenceActivity implements SharedPreferences.OnSharedPreferenceChangeListener{
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.pref);
		checkAll();
    }

	@Override
	public void onSharedPreferenceChanged(SharedPreferences p1, String key)
	{
		if(key.equals("folder")){
			checkFolder();
		}else if(key.equals("delay")){
			checkDelay();
		}
	}
	public void checkAll(){
		checkDelay();
		checkFolder();
	}
	private void checkFolder(){
		String s = PreferenceManager.getDefaultSharedPreferences(this).getString("folder","");
		File f = new File(s);
		if (!(f.canRead())||(!f.isDirectory())){
			PreferenceScreen screen = (PreferenceScreen) findPreference("pscreen");
			int pos = findPreference("folder").getOrder();
			screen.onItemClick( null, null, pos, 0 );
		}
	}
	private void checkDelay(){
		String s = PreferenceManager.getDefaultSharedPreferences(this).getString("delay","");
		try{
			Integer.parseInt(s);
		}catch(NumberFormatException e){
			PreferenceScreen screen = (PreferenceScreen) findPreference("pscreen");
			int pos = findPreference("delay").getOrder();
			screen.onItemClick( null, null, pos, 0 );
		}
	}
}
