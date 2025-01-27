package com.example.lightsensorlogger_2

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.lightsensorlogger_2.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set default fragment
        replaceFragment(HomeFragment())

        // Set navigation view
        binding.bottomNavigationView.setOnItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_home -> replaceFragment(HomeFragment())
                R.id.nav_current_light_levels -> replaceFragment(CurrentLightLevelsFragment())
                R.id.nav_export_csv -> replaceFragment(ExportCSVFragment())
                R.id.nav_charts -> replaceFragment(ChartsFragment())
                R.id.nav_map -> replaceFragment(MapFragment()) // Add the map fragment
            }
            true
        }
    }

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }
}
