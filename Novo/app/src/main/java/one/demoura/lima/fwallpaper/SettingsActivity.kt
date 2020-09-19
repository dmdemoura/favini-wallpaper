package one.demoura.lima.fwallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.documentfile.provider.DocumentFile
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import kotlinx.android.synthetic.main.settings_activity.*
import one.demoura.lima.fwallpaper.databinding.SettingsActivityBinding
import kotlin.time.ExperimentalTime

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = SettingsActivityBinding.inflate(layoutInflater)

        binding.lifecycleOwner = this
        setContentView(binding.root)

//        setContentView(R.layout.settings_activity)
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settings, SettingsFragment())
            .commit()
//        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    @ExperimentalTime
    override fun onStart() {
        super.onStart()

        setWallpaperButton.setOnClickListener {
            val intent = Intent().run {
                action = WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER

                val component = ComponentName(packageName, "$packageName.${FWallpaperService::class.simpleName}")
                putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
            }

            startActivity(intent)
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private var openSourceFolder: ActivityResultLauncher<Uri>? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            openSourceFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
                uri?.also {

                    val file = DocumentFile.fromSingleUri(requireContext(), it)

                    val prefStorage = PreferenceManager.getDefaultSharedPreferences(context)
                    val oldUri = prefStorage.getString("source_folder", null)
                    val prefEditor = prefStorage.edit()
                    prefEditor.putString("source_folder", it.toString())
                    prefEditor.putString("Source Folder Name", file?.name)
                    prefEditor.apply()

                    // Release persistent permission to old URI.
                    oldUri?.also {
                        kotlin.runCatching { Uri.parse(it) }.getOrNull()?.also { oldUri ->
                            requireContext()
                                .contentResolver
                                .releasePersistableUriPermission(oldUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    }

                    // Get a persistent permission to this URI, even if the device restarts.
                    requireContext()
                        .contentResolver
                        .takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)

                    findPreference<Preference>("source_folder")?.summary = it.toString()
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