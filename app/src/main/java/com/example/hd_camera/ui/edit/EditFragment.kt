package com.example.hd_camera.ui.edit

import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.hd_camera.R
import com.example.hd_camera.databinding.FragmentEditBinding
import com.example.hd_camera.databinding.ItemAdjustmentPillBinding
import com.example.hd_camera.databinding.ItemEditToolBinding
import com.example.hd_camera.edit.Adjustments
import com.example.hd_camera.edit.Geometry
import com.example.hd_camera.edit.ImageEditor
import com.example.hd_camera.media.MediaOutput
import com.example.hd_camera.ui.applySystemBarPadding
import com.example.hd_camera.ui.navigateBack
import kotlinx.coroutines.launch

/** Screen 11 · Edit. Loads the picked photo and writes a new file on save. */
class EditFragment : Fragment(R.layout.fragment_edit) {

    private enum class Tool(@StringRes val label: Int, @DrawableRes val icon: Int) {
        LIGHT(R.string.tool_light, R.drawable.ic_light),
        CROP(R.string.tool_crop, R.drawable.ic_crop),
        COLOR(R.string.tool_color, R.drawable.ic_color),
        BEAUTY(R.string.tool_beauty, R.drawable.ic_beauty),
        MARKUP(R.string.tool_markup, R.drawable.ic_markup)
    }

    private sealed interface Pill {
        val label: Int

        /** A pill that hands the slider a value to edit. */
        data class Param(
            @StringRes override val label: Int,
            @StringRes val title: Int,
            val read: (Adjustments) -> Int,
            val write: (Adjustments, Int) -> Adjustments,
            val from: Float = -100f,
            val to: Float = 100f
        ) : Pill

        /** A pill that just does something. */
        data class Action(
            @StringRes override val label: Int,
            val run: () -> Unit
        ) : Pill
    }

    private var binding: FragmentEditBinding? = null
    private var source: Bitmap? = null
    private var preview: Bitmap? = null

    private var tool = Tool.LIGHT
    private var pills: List<Pill> = emptyList()
    private var selectedPill = 0
    private var adjustments = Adjustments()
    private var geometry = Geometry()
    private var saving = false

    private val mediaUri: Uri?
        get() = arguments?.getString(ARG_URI)?.toUri()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentEditBinding.bind(view).also { this.binding = it }
        binding.header.applySystemBarPadding(top = true)
        binding.editControls.applySystemBarPadding(bottom = true)

        binding.btnCancel.setOnClickListener { navigateBack() }
        binding.btnSave.setOnClickListener { save() }
        binding.markupOverlay.onStrokesChanged = { refreshPills() }
        binding.cropOverlay.onCropChanged = { updateSliderForSelection() }

        binding.sliderAdjustment.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            onSliderChanged(value.toInt())
        }

        bindTools()
        selectTool(Tool.LIGHT)
        loadImage()
    }

    override fun onDestroyView() {
        source = null
        preview = null
        binding = null
        super.onDestroyView()
    }

    // ── Loading ────────────────────────────────────────────────────────────

    private fun loadImage() {
        val binding = binding ?: return
        val uri = mediaUri ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = ImageEditor.load(requireContext(), uri, PREVIEW_EDGE)
            if (bitmap == null) {
                binding.tvEditPlaceholder.setText(R.string.edit_load_failed)
                return@launch
            }
            source = bitmap
            binding.tvEditPlaceholder.visibility = View.GONE
            renderPreview()
        }
    }

    /** Geometry and smoothing are baked into the preview; tone is a cheap colour filter. */
    private fun renderPreview() {
        val binding = binding ?: return
        val source = source ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            val rendered = ImageEditor.render(
                source = source,
                adjustments = Adjustments(smoothing = adjustments.smoothing),
                geometry = geometry
            )
            preview = rendered
            binding.imgEdit.setImageBitmap(rendered)
            applyToneFilter()
            if (tool == Tool.CROP) updateCropBounds()
        }
    }

    private fun applyToneFilter() {
        val binding = binding ?: return
        val tone = adjustments.copy(smoothing = 0)
        binding.imgEdit.colorFilter =
            if (tone.isIdentity) null else ColorMatrixColorFilter(tone.toColorMatrix())
    }

    // ── Tools and pills ────────────────────────────────────────────────────

    private fun bindTools() {
        val binding = binding ?: return
        val row = binding.toolRow
        row.removeAllViews()
        val inflater = LayoutInflater.from(row.context)
        val gap = (10 * resources.displayMetrics.density).toInt()

        Tool.entries.forEachIndexed { index, entry ->
            val item = ItemEditToolBinding.inflate(inflater, row, false)
            val isSelected = entry == tool
            item.toolLabel.setText(entry.label)
            item.toolIcon.setImageResource(entry.icon)
            item.toolIcon.setBackgroundResource(
                if (isSelected) R.drawable.bg_tool_tile_active else R.drawable.bg_tool_tile
            )
            item.toolIcon.setColorFilter(
                ContextCompat.getColor(
                    row.context,
                    if (isSelected) R.color.dc_accent else R.color.dc_text_muted
                )
            )
            item.toolLabel.setTextColor(
                ContextCompat.getColor(
                    row.context,
                    if (isSelected) R.color.dc_text else R.color.dc_text_muted
                )
            )
            item.toolLabel.typeface = ResourcesCompat.getFont(
                row.context,
                if (isSelected) R.font.ibm_plex_sans_semibold else R.font.ibm_plex_sans_regular
            )
            item.root.setOnClickListener { selectTool(entry) }
            (item.root.layoutParams as LinearLayout.LayoutParams).marginStart =
                if (index == 0) 0 else gap
            row.addView(item.root)
        }
    }

    private fun selectTool(next: Tool) {
        tool = next
        selectedPill = 0
        pills = pillsFor(next)
        binding?.markupOverlay?.visibility =
            if (next == Tool.MARKUP) View.VISIBLE else View.GONE
        binding?.cropOverlay?.visibility =
            if (next == Tool.CROP) View.VISIBLE else View.GONE
        if (next == Tool.CROP) updateCropBounds()
        bindTools()
        refreshPills()
    }

    private fun setCropAspect(ratio: Float?) {
        binding?.cropOverlay?.aspect = ratio
        updateSliderForSelection()
    }

    /** The frame has to line up with where the photo is actually drawn. */
    private fun updateCropBounds() {
        val binding = binding ?: return
        val image = binding.imgEdit
        image.post {
            val drawable = image.drawable ?: return@post
            val bounds = RectF(
                0f,
                0f,
                drawable.intrinsicWidth.toFloat(),
                drawable.intrinsicHeight.toFloat()
            )
            image.imageMatrix.mapRect(bounds)
            bounds.offset(image.left.toFloat(), image.top.toFloat())
            binding.cropOverlay.setContentBounds(bounds)
        }
    }

    private fun pillsFor(tool: Tool): List<Pill> = when (tool) {
        Tool.LIGHT -> listOf(
            Pill.Param(R.string.adj_exposure, R.string.exposure,
                { it.exposure }, { a, v -> a.copy(exposure = v) }),
            Pill.Param(R.string.adj_contrast, R.string.adj_contrast_label,
                { it.contrast }, { a, v -> a.copy(contrast = v) }),
            Pill.Param(R.string.adj_highlights, R.string.adj_highlights_label,
                { it.highlights }, { a, v -> a.copy(highlights = v) }),
            Pill.Param(R.string.adj_shadows, R.string.adj_shadows_label,
                { it.shadows }, { a, v -> a.copy(shadows = v) })
        )

        Tool.COLOR -> listOf(
            Pill.Param(R.string.adj_saturation, R.string.adj_saturation_label,
                { it.saturation }, { a, v -> a.copy(saturation = v) }),
            Pill.Param(R.string.adj_warmth, R.string.adj_warmth_label,
                { it.warmth }, { a, v -> a.copy(warmth = v) })
        )

        Tool.BEAUTY -> listOf(
            Pill.Param(R.string.adj_smooth, R.string.adj_smooth_label,
                { it.smoothing }, { a, v -> a.copy(smoothing = v) }, from = 0f)
        )

        Tool.CROP -> listOf(
            Pill.Action(R.string.crop_free) { setCropAspect(null) },
            Pill.Action(R.string.crop_square) { setCropAspect(1f) },
            Pill.Action(R.string.crop_4_3) { setCropAspect(4f / 3f) },
            Pill.Action(R.string.crop_16_9) { setCropAspect(16f / 9f) },
            Pill.Action(R.string.action_rotate_left) { rotate(-90) },
            Pill.Action(R.string.action_rotate_right) { rotate(90) },
            Pill.Action(R.string.action_flip) { flip() },
            Pill.Action(R.string.action_reset) { binding?.cropOverlay?.reset() }
        )

        Tool.MARKUP -> listOf(
            Pill.Action(R.string.action_undo) { binding?.markupOverlay?.undo() },
            Pill.Action(R.string.action_clear) { binding?.markupOverlay?.clear() }
        )
    }

    private fun refreshPills() {
        val binding = binding ?: return
        val row = binding.adjustmentRow
        row.removeAllViews()
        val inflater = LayoutInflater.from(row.context)
        val gap = (8 * resources.displayMetrics.density).toInt()

        pills.forEachIndexed { index, pill ->
            val item = ItemAdjustmentPillBinding.inflate(inflater, row, false)
            val isSelected = pill is Pill.Param && index == selectedPill
            item.root.setText(pill.label)
            item.root.setBackgroundResource(
                if (isSelected) R.drawable.bg_pill_active else R.drawable.bg_pill_inactive
            )
            item.root.setTextColor(
                ContextCompat.getColor(
                    row.context,
                    if (isSelected) R.color.dc_on_accent else R.color.dc_text_muted
                )
            )
            item.root.typeface = ResourcesCompat.getFont(
                row.context,
                if (isSelected) R.font.ibm_plex_sans_semibold else R.font.ibm_plex_sans_regular
            )
            item.root.setOnClickListener {
                when (pill) {
                    is Pill.Param -> {
                        selectedPill = index
                        refreshPills()
                    }

                    is Pill.Action -> pill.run()
                }
            }
            (item.root.layoutParams as LinearLayout.LayoutParams).marginStart =
                if (index == 0) 0 else gap
            row.addView(item.root)
        }

        updateSliderForSelection()
    }

    private fun updateSliderForSelection() {
        val binding = binding ?: return
        val pill = pills.getOrNull(selectedPill)

        if (pill is Pill.Param) {
            binding.sliderAdjustment.visibility = View.VISIBLE
            binding.sliderAdjustment.valueFrom = pill.from
            binding.sliderAdjustment.valueTo = pill.to
            val value = pill.read(adjustments).toFloat().coerceIn(pill.from, pill.to)
            binding.sliderAdjustment.value = value
            binding.tvAdjustmentName.setText(pill.title)
            binding.tvAdjustmentValue.text = getString(R.string.signed_value, value.toInt())
        } else {
            binding.sliderAdjustment.visibility = View.INVISIBLE
            binding.tvAdjustmentName.setText(
                if (tool == Tool.CROP) R.string.crop_label else R.string.markup_label
            )
            binding.tvAdjustmentValue.text = when (tool) {
                Tool.CROP -> {
                    val crop = binding.cropOverlay.normalizedCrop()
                    val percent = (crop.width() * crop.height() * 100).toInt()
                    percent.toString() + "% · " + geometry.rotationDegrees + "°"
                }

                else -> if (binding.markupOverlay.hasStrokes) "ON" else "—"
            }
        }
    }

    private fun onSliderChanged(value: Int) {
        val binding = binding ?: return
        val pill = pills.getOrNull(selectedPill) as? Pill.Param ?: return
        val previousSmoothing = adjustments.smoothing
        adjustments = pill.write(adjustments, value)
        binding.tvAdjustmentValue.text = getString(R.string.signed_value, value)

        if (adjustments.smoothing != previousSmoothing) {
            renderPreview()
        } else {
            applyToneFilter()
        }
    }

    private fun rotate(degrees: Int) {
        geometry = geometry.copy(rotationDegrees = (geometry.rotationDegrees + degrees + 360) % 360)
        renderPreview()
        updateSliderForSelection()
    }

    private fun flip() {
        geometry = geometry.copy(flipped = !geometry.flipped)
        renderPreview()
    }

    // ── Save ───────────────────────────────────────────────────────────────

    private fun save() {
        val binding = binding ?: return
        val uri = mediaUri ?: return
        if (saving) return
        saving = true
        binding.btnSave.setText(R.string.saving)

        viewLifecycleOwner.lifecycleScope.launch {
            val full = ImageEditor.load(requireContext(), uri, SAVE_EDGE)
            val rendered = full?.let {
                ImageEditor.render(
                    source = it,
                    adjustments = adjustments,
                    geometry = geometry,
                    markup = binding.markupOverlay.strokes(),
                    crop = if (binding.cropOverlay.isCropped) {
                        binding.cropOverlay.normalizedCrop()
                    } else {
                        null
                    }
                )
            }
            val saved = rendered?.let {
                MediaOutput.writeJpeg(
                    requireContext(),
                    it,
                    MediaOutput.fileName("IMG_EDIT") + ".jpg"
                )
            }
            saving = false
            if (saved != null) {
                binding.btnSave.setText(R.string.save)
                navigateBack()
            } else {
                binding.btnSave.setText(R.string.save)
                binding.tvEditPlaceholder.visibility = View.VISIBLE
                binding.tvEditPlaceholder.setText(R.string.save_failed)
            }
        }
    }

    companion object {
        private const val ARG_URI = "media_uri"
        private const val PREVIEW_EDGE = 1600
        private const val SAVE_EDGE = 4096

        fun of(uri: Uri): EditFragment = EditFragment().apply {
            arguments = Bundle().apply { putString(ARG_URI, uri.toString()) }
        }
    }
}
