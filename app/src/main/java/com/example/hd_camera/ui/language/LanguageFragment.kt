package com.example.hd_camera.ui.language

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.hd_camera.R
import com.example.hd_camera.data.AppLanguage
import com.example.hd_camera.data.LocalePrefs
import com.example.hd_camera.databinding.FragmentLanguageBinding
import com.example.hd_camera.databinding.ItemLanguageBinding
import com.example.hd_camera.ui.applySystemBarPadding
import com.example.hd_camera.ui.navigateBack
import com.example.hd_camera.ui.navigateTo
import com.example.hd_camera.ui.permissions.PermissionsFragment

/**
 * Screen 03b · Language.
 *
 * Two ways in. During onboarding it sits between the intro and the permission hand-off and
 * ends in a Continue button; opened from Settings it is an ordinary screen with a back
 * arrow, already showing what is in force.
 */
class LanguageFragment : Fragment(R.layout.fragment_language) {

    private var binding: FragmentLanguageBinding? = null
    private var selected: AppLanguage = AppLanguage.DEFAULT

    private val onboarding: Boolean
        get() = arguments?.getBoolean(ARG_ONBOARDING, false) ?: false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentLanguageBinding.bind(view).also { this.binding = it }
        binding.header.applySystemBarPadding(top = true)
        binding.languageFooter.applySystemBarPadding(bottom = true)

        selected = LocalePrefs.current(requireContext())

        // Onboarding has nowhere to go back to, and Settings needs no second Continue.
        binding.btnBack.visibility = if (onboarding) View.GONE else View.VISIBLE
        binding.tvLanguageBody.visibility = if (onboarding) View.VISIBLE else View.GONE
        binding.languageFooter.visibility = if (onboarding) View.VISIBLE else View.GONE

        binding.btnBack.setOnClickListener { navigateBack() }
        binding.btnContinue.setOnClickListener { navigateTo(PermissionsFragment()) }

        bindList()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun bindList() {
        val binding = binding ?: return
        val list = binding.languageList
        list.removeAllViews()

        val inflater = LayoutInflater.from(list.context)
        val gap = (10 * resources.displayMetrics.density).toInt()

        AppLanguage.entries.forEachIndexed { index, language ->
            val item = ItemLanguageBinding.inflate(inflater, list, false)
            item.languageFlag.setImageResource(language.flag)
            item.languageName.setText(language.displayName)
            item.root.setOnClickListener { pick(language) }
            (item.root.layoutParams as LinearLayout.LayoutParams).topMargin =
                if (index == 0) 0 else gap
            style(item, language == selected)
            list.addView(item.root)
        }
    }

    private fun style(item: ItemLanguageBinding, isSelected: Boolean) {
        item.root.setBackgroundResource(
            if (isSelected) R.drawable.bg_language_row_selected else R.drawable.bg_language_row
        )
        item.languageName.setTextColor(
            ContextCompat.getColor(
                item.root.context,
                if (isSelected) R.color.dc_on_accent else R.color.dc_text
            )
        )
        item.languageRadio.setBackgroundResource(
            if (isSelected) R.drawable.bg_radio_on else R.drawable.bg_radio_off
        )
        item.languageRadio.contentDescription =
            if (isSelected) getString(R.string.cd_language_selected) else null
    }

    /**
     * Applying the locale recreates this activity, which rebuilds the fragment from the
     * back stack — so the list is only restyled for the frame before that happens, and
     * nothing here navigates.
     */
    private fun pick(language: AppLanguage) {
        if (language == selected) return
        selected = language
        bindList()
        LocalePrefs.apply(requireContext(), language)
    }

    companion object {
        private const val ARG_ONBOARDING = "onboarding"

        /** The onboarding variant: no back arrow, a Continue button at the bottom. */
        fun onboarding(): LanguageFragment = LanguageFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_ONBOARDING, true) }
        }
    }
}
