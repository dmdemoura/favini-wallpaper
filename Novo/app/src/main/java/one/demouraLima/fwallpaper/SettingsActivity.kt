package one.demouraLima.fwallpaper

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
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
        private var openSourceFolder: ActivityResultLauncher<Uri>? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            openSourceFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
                if (uri != null)
                {
                    val file = DocumentFile.fromSingleUri(requireContext(), uri)

                    val prefStorage = PreferenceManager.getDefaultSharedPreferences(context)
                    val prefEditor = prefStorage.edit()
                    prefEditor.putString("source_folder", uri.toString())
                    prefEditor.putString("Source Folder Name", file?.name)
                    prefEditor.apply()

                    findPreference<Preference>("source_folder")?.summary = uri.toString()
                }
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
            val pref = findPreference<Preference>("source_folder")

            pref?.onPreferenceClickListener = Preference.OnPreferenceClickListener { _: Preference ->
                openSourceFolder?.launch(Uri.EMPTY)
                true
            }

            val prefStorage = PreferenceManager.getDefaultSharedPreferences(context)
            pref?.summary = prefStorage.getString("source_folder", "No folder selected")
        }
    }
}