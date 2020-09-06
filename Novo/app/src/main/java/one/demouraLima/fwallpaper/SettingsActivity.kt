package one.demouraLima.fwallpaper

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

class SettingsActivity : AppCompatActivity() {
    companion object {
        const val SRC_FOLDER = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settings, SettingsFragment())
            .commit()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onActivityResult(
        requestCode: Int, resultCode: Int, resultData: Intent?
    ) {
        if (requestCode == SRC_FOLDER
            && resultCode == Activity.RESULT_OK
        ) {
            // The result data contains a URI for the document or directory that
            // the user selected.
            resultData?.data?.also { uri ->
                { val prefs = PrefenceManager }
                // Perform operations on the document using its URI.
            }
        }
    }


    class Listener : Preference.OnPreferenceClickListener {
        override fun onPreferenceClick(preference: Preference): Boolean {
            var activity = preference.context as Activity
            // Choose a directory using the system's file picker.
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                // Provide read access to files and sub-directories in the user-selected
                // directory.
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION

                // Optionally, specify a URI for the directory that should be opened in
                // the system file picker when it loads.
                //putExtra(DocumentsContract.EXTRA_INITIAL_URI, pickerInitialUri)
            }
            activity.startActivityForResult(intent, SRC_FOLDER)
            return true
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
            findPreference<Preference>("Source Folder")?.onPreferenceClickListener = Listener()
        }
    }


    /*
    fun openDirectory(pickerInitialUri: Uri) {
        // Choose a directory using the system's file picker.
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            // Provide read access to files and sub-directories in the user-selected
            // directory.
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION

            // Optionally, specify a URI for the directory that should be opened in
            // the system file picker when it loads.
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, pickerInitialUri)
        }

        startActivityForResult(intent, your-request-code)
    }

     */


}