package com.digitalcamerahd.camera4k.selfiecamera.ui.gallery

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.digitalcamerahd.camera4k.selfiecamera.R
import com.digitalcamerahd.camera4k.selfiecamera.databinding.FragmentGalleryBinding
import com.digitalcamerahd.camera4k.selfiecamera.databinding.ItemGallerySectionBinding
import com.digitalcamerahd.camera4k.selfiecamera.media.MediaFilter
import com.digitalcamerahd.camera4k.selfiecamera.media.MediaItem
import com.digitalcamerahd.camera4k.selfiecamera.media.MediaRepository
import com.digitalcamerahd.camera4k.selfiecamera.media.MediaSection
import com.digitalcamerahd.camera4k.selfiecamera.ui.applySystemBarPadding
import com.digitalcamerahd.camera4k.selfiecamera.ui.edit.EditFragment
import com.digitalcamerahd.camera4k.selfiecamera.ui.navigateBack
import com.digitalcamerahd.camera4k.selfiecamera.ui.navigateHome
import com.digitalcamerahd.camera4k.selfiecamera.ui.navigateTo
import com.digitalcamerahd.camera4k.selfiecamera.ui.settings.SettingsFragment
import kotlinx.coroutines.launch

/** Screen 09 · Gallery, listing what the app has written to DCIM/HDCamera. */
class GalleryFragment : Fragment(R.layout.fragment_gallery) {

    private var binding: FragmentGalleryBinding? = null
    private var filter = MediaFilter.ALL
    private var query = ""
    private var searching = false
    private var selecting = false
    private val selection = linkedSetOf<MediaItem>()

    private val confirmDelete = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            exitSelection()
            reload()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentGalleryBinding.bind(view).also { this.binding = it }
        binding.header.applySystemBarPadding(top = true)
        binding.bottomNav.applySystemBarPadding(bottom = true)

        binding.btnCamera.setOnClickListener { navigateBack() }
        binding.navSettings.setOnClickListener { navigateTo(SettingsFragment()) }
        binding.btnBack.setOnClickListener { navigateBack() }
        binding.btnSelect.setOnClickListener { toggleSelectionMode() }
        binding.btnSearch.setOnClickListener { toggleSearch() }
        binding.searchInput.doAfterTextChanged { text ->
            query = text?.toString().orEmpty()
            reload()
        }

        val chips = listOf(binding.chipAll, binding.chipPhotos, binding.chipVideo, binding.chipRaw)
        chips.forEachIndexed { index, chip ->
            chip.setOnClickListener {
                filter = MediaFilter.entries[index]
                selectChip(chips, index)
                reload()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    // ── Loading ────────────────────────────────────────────────────────────

    private fun reload() {
        val binding = binding ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val sections = applySearch(MediaRepository.load(requireContext(), filter))
            binding.sectionsContainer.removeAllViews()
            binding.tvEmpty.visibility = if (sections.isEmpty()) View.VISIBLE else View.GONE
            binding.tvEmpty.setText(
                if (query.isBlank()) R.string.gallery_empty else R.string.no_matches
            )

            val inflater = LayoutInflater.from(requireContext())
            val spacing = (6 * resources.displayMetrics.density).toInt()

            sections.forEach { section ->
                val sectionBinding = ItemGallerySectionBinding.inflate(
                    inflater, binding.sectionsContainer, false
                )
                sectionBinding.sectionTitle.text = section.title
                sectionBinding.sectionGrid.layoutManager =
                    GridLayoutManager(requireContext(), SPAN_COUNT)
                sectionBinding.sectionGrid.addItemDecoration(
                    GridSpacingItemDecoration(SPAN_COUNT, spacing)
                )
                sectionBinding.sectionGrid.adapter = GalleryAdapter(
                    items = section.items,
                    isSelected = { selection.contains(it) },
                    onClick = { item -> onItemClick(item) },
                    onLongClick = { item -> onItemLongClick(item) }
                )
                binding.sectionsContainer.addView(sectionBinding.root)
            }
        }
    }

    /** Matches the file name or the date heading, so "sep" or "VID" both work. */
    private fun applySearch(sections: List<MediaSection>): List<MediaSection> {
        val needle = query.trim()
        if (needle.isEmpty()) return sections
        return sections.mapNotNull { section ->
            if (section.label.contains(needle, ignoreCase = true)) {
                section
            } else {
                val matches = section.items.filter {
                    it.displayName.contains(needle, ignoreCase = true)
                }
                if (matches.isEmpty()) {
                    null
                } else {
                    // The heading carries counts, so a narrowed section needs a new one.
                    section.copy(
                        items = matches,
                        title = MediaRepository.sectionTitle(
                            requireContext(), section.label, matches
                        )
                    )
                }
            }
        }
    }

    private fun toggleSearch() {
        val binding = binding ?: return
        searching = !searching
        binding.searchInput.visibility = if (searching) View.VISIBLE else View.GONE

        val keyboard = requireContext().getSystemService<InputMethodManager>()
        if (searching) {
            binding.searchInput.requestFocus()
            keyboard?.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
        } else {
            keyboard?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
            binding.searchInput.setText("")
            query = ""
            reload()
        }
    }

    // ── Selection ──────────────────────────────────────────────────────────

    private fun onItemClick(item: MediaItem) {
        if (selecting) {
            if (!selection.add(item)) selection.remove(item)
            updateSelectionChrome()
            reload()
            return
        }
        if (item.isVideo) {
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(item.uri, item.mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        } else {
            navigateTo(EditFragment.of(item.uri))
        }
    }

    private fun onItemLongClick(item: MediaItem) {
        if (!selecting) toggleSelectionMode()
        selection.add(item)
        updateSelectionChrome()
        reload()
    }

    private fun toggleSelectionMode() {
        if (selecting) {
            if (selection.isNotEmpty()) {
                deleteSelection()
            } else {
                exitSelection()
                reload()
            }
            return
        }
        selecting = true
        updateSelectionChrome()
    }

    private fun exitSelection() {
        selecting = false
        selection.clear()
        updateSelectionChrome()
    }

    private fun updateSelectionChrome() {
        val binding = binding ?: return
        binding.btnSelect.text = when {
            !selecting -> getString(R.string.select)
            selection.isEmpty() -> getString(R.string.done)
            else -> getString(R.string.delete_selected, selection.size)
        }
        binding.btnSelect.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (selection.isEmpty()) R.color.dc_text_muted else R.color.dc_record
            )
        )
    }

    private fun deleteSelection() {
        val uris = selection.map { it.uri }
        if (uris.isEmpty()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Scoped storage: the system asks the user to confirm.
            val pending = MediaStore.createDeleteRequest(
                requireContext().contentResolver,
                uris
            )
            confirmDelete.launch(IntentSenderRequest.Builder(pending.intentSender).build())
        } else {
            uris.forEach { uri ->
                requireContext().contentResolver.delete(uri, null, null)
            }
            exitSelection()
            reload()
        }
    }

    // ── Filter chips ────────────────────────────────────

    private fun selectChip(chips: List<TextView>, selected: Int) {
        chips.forEachIndexed { index, chip ->
            val isSelected = index == selected
            chip.setBackgroundResource(
                if (isSelected) R.drawable.bg_pill_active else R.drawable.bg_pill_inactive
            )
            chip.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (isSelected) R.color.dc_on_accent else R.color.dc_text_muted
                )
            )
            chip.typeface = ResourcesCompat.getFont(
                requireContext(),
                if (isSelected) R.font.ibm_plex_sans_semibold else R.font.ibm_plex_sans_regular
            )
        }
    }

    private companion object {
        const val SPAN_COUNT = 3
    }
}
