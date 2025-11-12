package com.jphat.pgsharpchecker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    
    private lateinit var tvInstalledVersion: TextView
    private lateinit var tvLatestVersion: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnCheckNow: Button
    private lateinit var btnEnableAutoCheck: Button
    
    private var isAutoCheckEnabled = false
    
    companion object {
        private const val NOTIFICATION_PERMISSION_CODE = 100
        private const val QUERY_PACKAGES_PERMISSION_CODE = 101
        private const val PREFS_NAME = "PGSharpCheckerPrefs"
        private const val KEY_AUTO_CHECK_ENABLED = "auto_check_enabled"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Initialize views
        tvInstalledVersion = findViewById(R.id.tvInstalledVersion)
        tvLatestVersion = findViewById(R.id.tvLatestVersion)
        tvStatus = findViewById(R.id.tvStatus)
        btnCheckNow = findViewById(R.id.btnCheckNow)
        btnEnableAutoCheck = findViewById(R.id.btnEnableAutoCheck)
        
        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_CODE
                )
            }
        }
        
        // Load saved auto-check state
        loadAutoCheckState()
        
        // Display installed PGSharp version
        displayInstalledVersion()
        
        // Update button appearance based on state
        updateAutoCheckButton()
        
        // Manual check button
        btnCheckNow.setOnClickListener {
            performManualCheck()
        }
        
        // Enable/disable automatic periodic checking
        btnEnableAutoCheck.setOnClickListener {
            if (isAutoCheckEnabled) {
                disablePeriodicVersionCheck()
            } else {
                schedulePeriodicVersionCheck()
            }
        }
    }
    
    private fun displayInstalledVersion() {
        val packages = listOf(
            "com.nianticlabs.pokemongo",
            "com.pgsharp.pokemongo",
            "com.nianticproject.holoholo"
        )
        
        for (packageName in packages) {
            try {
                val packageInfo = packageManager.getPackageInfo(packageName, 0)
                val versionName = packageInfo.versionName
                tvInstalledVersion.text = getString(R.string.installed_pokemon_go, versionName)
                return
            } catch (e: PackageManager.NameNotFoundException) {
                // Try next package
            }
        }
        
        // If we get here, no package was found - let's search for it
        tvInstalledVersion.text = getString(R.string.pokemon_go_not_found)
        searchForPokemonGoPackage()
    }
    
    private fun performManualCheck() {
        tvStatus.text = getString(R.string.checking_updates)
        
        // Create one-time work request
        val checkRequest = OneTimeWorkRequestBuilder<VersionCheckWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        
        WorkManager.getInstance(this).enqueue(checkRequest)
        
        // Observe the work status
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(checkRequest.id)
            .observe(this) { workInfo ->
                if (workInfo != null) {
                    when (workInfo.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            val latestVersion = workInfo.outputData.getString("latest_version")
                            val installedVersion = workInfo.outputData.getString("installed_version")
                            val updateAvailable = workInfo.outputData.getBoolean("update_available", false)
                            
                            tvLatestVersion.text = getString(R.string.latest_on_pgsharp, latestVersion)
                            
                            if (updateAvailable) {
                                tvStatus.text = getString(R.string.newer_version_available, installedVersion, latestVersion)
                            } else {
                                tvStatus.text = getString(R.string.version_matches)
                            }
                        }
                        WorkInfo.State.FAILED -> {
                            tvStatus.text = getString(R.string.check_failed)
                        }
                        WorkInfo.State.RUNNING -> {
                            tvStatus.text = getString(R.string.checking)
                        }
                        else -> {}
                    }
                }
            }
    }
    
    private fun searchForPokemonGoPackage() {
        // Search all installed packages for anything containing "pokemon" or "niantic"
        val pm = packageManager
        val packages = pm.getInstalledApplications(0)
        
        val foundPackages = packages.filter { 
            (it.packageName.contains("pokemon", ignoreCase = true) ||
            it.packageName.contains("niantic", ignoreCase = true) ||
            it.packageName.contains("pgsharp", ignoreCase = true) ||
            it.packageName.contains("pogo", ignoreCase = true)) &&
            it.packageName != packageName  // Exclude this app itself
        }
        
        if (foundPackages.isNotEmpty()) {
            val packageNames = foundPackages.joinToString("\n") { 
                "${it.packageName} - ${pm.getApplicationLabel(it)}"
            }
            tvInstalledVersion.text = getString(R.string.found_packages_text, packageNames)
            Toast.makeText(
                this, 
                getString(R.string.found_packages, foundPackages.size), 
                Toast.LENGTH_LONG
            ).show()
        } else {
            tvInstalledVersion.text = getString(R.string.no_pokemon_go_found)
            Toast.makeText(this, getString(R.string.pokemon_go_not_found_toast), Toast.LENGTH_LONG).show()
        }
    }
    
    private fun loadAutoCheckState() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        isAutoCheckEnabled = prefs.getBoolean(KEY_AUTO_CHECK_ENABLED, false)
    }
    
    private fun saveAutoCheckState(enabled: Boolean) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_CHECK_ENABLED, enabled).apply()
        isAutoCheckEnabled = enabled
    }
    
    private fun updateAutoCheckButton() {
        if (isAutoCheckEnabled) {
            btnEnableAutoCheck.text = getString(R.string.btn_auto_enabled)
            btnEnableAutoCheck.setBackgroundColor(getColor(R.color.button_enabled))
        } else {
            btnEnableAutoCheck.text = getString(R.string.btn_enable_auto)
            btnEnableAutoCheck.setBackgroundColor(getColor(R.color.button_disabled))
        }
    }
    
    private fun schedulePeriodicVersionCheck() {
        // Schedule periodic work every 12 hours
        val periodicWorkRequest = PeriodicWorkRequestBuilder<VersionCheckWorker>(
            12, TimeUnit.HOURS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
        
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "pokemon_go_version_check",
            ExistingPeriodicWorkPolicy.REPLACE,
            periodicWorkRequest
        )
        
        saveAutoCheckState(true)
        updateAutoCheckButton()
        
        Toast.makeText(
            this, 
            getString(R.string.auto_check_enabled_toast), 
            Toast.LENGTH_LONG
        ).show()
        
        tvStatus.text = getString(R.string.auto_check_enabled_status)
    }
    
    private fun disablePeriodicVersionCheck() {
        WorkManager.getInstance(this).cancelUniqueWork("pokemon_go_version_check")
        
        saveAutoCheckState(false)
        updateAutoCheckButton()
        
        Toast.makeText(
            this, 
            getString(R.string.auto_check_disabled_toast), 
            Toast.LENGTH_LONG
        ).show()
        
        tvStatus.text = getString(R.string.auto_check_disabled_status)
    }
}
