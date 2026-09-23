package com.example.hd_camera.ui.intro

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.example.hd_camera.R
import com.example.hd_camera.databinding.FragmentIntroBinding
import com.example.hd_camera.ui.AlwaysDark
import com.example.hd_camera.ui.darkInflater
import com.example.hd_camera.ui.language.LanguageFragment
import com.example.hd_camera.ui.navigateTo

/** Screens 01–03 · the three-page intro carousel. */
class IntroFragment : Fragment(R.layout.fragment_intro), AlwaysDark {

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater =
        darkInflater(super.onGetLayoutInflater(savedInstanceState))

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentIntroBinding.bind(view)
        val adapter = IntroPagerAdapter(
            onNext = {
                binding.introPager.currentItem = binding.introPager.currentItem + 1
            },
            // Skip and "Get started" both land on the language picker, which hands over
            // to the permission screen.
            onFinish = { navigateTo(LanguageFragment.onboarding()) }
        )
        binding.introPager.adapter = adapter

        ViewCompat.setOnApplyWindowInsetsListener(binding.introRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            adapter.setSystemBarInsets(bars.top, bars.bottom)
            insets
        }
    }
}
