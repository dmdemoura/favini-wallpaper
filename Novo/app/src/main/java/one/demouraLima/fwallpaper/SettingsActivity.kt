package one.demouraLima.fwallpaper

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settings, SettingsFragment())
            .commit()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
            val pref = findPreference<Preference>("test")

            pref?.onPreferenceClickListener = Preference.OnPreferenceClickListener { preference: Preference ->
                registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
                    if (uri != null)
                    {
                        val prefStorage = PreferenceManager.getDefaultSharedPreferences(preference.context)
                        val prefEditor = prefStorage.edit()
                        prefEditor.putString(preference.key, uri.toString())
                        prefEditor.apply()
                    }
                }

                true
            }
        }
    }
}