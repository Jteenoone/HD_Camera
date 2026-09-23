package com.example.hd_camera.ui.camera

import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import androidx.camera.extensions.ExtensionMode
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.load
import com.example.hd_camera.R
import com.example.hd_camera.camera.CameraEngine
import com.example.hd_camera.camera.PhotoCapture
import com.example.hd_camera.databinding.FragmentFiltersBinding
import com.example.hd_camera.databinding.ItemFilterBinding
import com.example.hd_camera.filters.PhotoFilter
import com.example.hd_camera.filters.asRenderEffect
import com.example.hd_camera.filters.supportsLivePreviewFilter
import com.example.hd_camera.media.MediaRepository
import com.example.hd_camera.ui.AlwaysDark
import com.example.hd_camera.ui.applySystemBarPadding
import com.example.hd_camera.ui.darkInflater
import com.example.hd_camera.ui.gallery.GalleryFragment
import com.example.hd_camera.ui.navigateBack
import com.example.hd_camera.ui.navigateTo
import kotlinx.coroutines.launch

/**
 * Screen 07 · Live filters. The colour matrix runs on the preview through a RenderEffect and
 * on the captured frame through the same definition, so what you see is what gets saved.
 */
class FiltersFragment : Fragment(R.layout.fragment_filters), ShutterKeyHandler, AlwaysDark {

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater =
        darkInflater(super.onGetLayoutInflater(savedInstanceState))

    private var binding: FragmentFiltersBinding? = null
    private var engine: CameraEngine? = null

    private var selectedFilter = 1
    private var strength = 0.72f
    private var comparing = false
    private var capturing = false

    private val filters = PhotoFilter.entries

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentFiltersBinding.bind(view).also { this.binding = it }
        binding.topBar.applySystemBarPadding(top = true)
        binding.bottomBar.applySystemBarPadding(bottom = true)

        // A RenderEffect only reaches the preview when it is drawn into a TextureView.
        binding.previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        val engine = CameraEngine(requireContext(), viewLifecycleOwner, binding.previewView)
            .also { this.engine = it }
        engine.onCameraError = { error ->
            binding.tvCameraStatus.visibility = View.VISIBLE
            binding.tvCameraStatus.text = getString(R.string.camera_unavailable)
            error.printStackTrace()
        }
        engine.onCameraReady = {
            binding.tvCameraStatus.visibility = View.GONE
            applyPreviewFilter()
        }
        // "Beauty" in the mode strip is this screen, so face retouch comes on with it.
        engine.start(CameraEngine.Mode.PHOTO, ExtensionMode.FACE_RETOUCH)

        binding.btnBack.setOnClickListener { navigateBack() }
        binding.btnShutter.setOnClickListener { takePhoto() }
        binding.btnLastShot.setOnClickListener { navigateTo(GalleryFragment()) }
        binding.btnFlip.setOnClickListener { engine.switchLens() }
        bindCompare()

        binding.sliderStrength.value = strength * 100f
        binding.sliderStrength.addOnChangeListener { _, value, _ ->
            strength = value / 100f
            binding.tvStrength.text = getString(R.string.percent_value, value.toInt())
            applyPreviewFilter()
        }
        binding.tvStrength.text = getString(R.string.percent_value, (strength * 100).toInt())

        bindFilters(binding.filterRow)
    }

    override fun onResume() {
        super.onResume()
        loadLastShot()
    }

    private fun loadLastShot() {
        val binding = binding ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val latest = MediaRepository.latest(requireContext())
            if (latest != null) {
                binding.btnLastShot.load(latest.uri)
            } else {
                binding.btnLastShot.setImageDrawable(null)
            }
        }
    }

    /**
     * A beauty shot goes through the processing path, so the file is already on disk by the
     * time the capture returns — showing the URI it hands back puts the new frame in the
     * thumbnail straight away rather than on the next visit to the screen.
     */
    private fun showLastShot(uri: Uri?) {
        val binding = binding ?: return
        if (uri != null) {
            binding.btnLastShot.load(uri)
        } else {
            loadLastShot()
        }
    }

    override fun onDestroyView() {
        engine?.release()
        engine = null
        binding = null
        super.onDestroyView()
    }

    // ── Filter selection ───────────────────────────────────────────────────

    private fun bindFilters(row: LinearLayout) {
        row.removeAllViews()
        val inflater = LayoutInflater.from(row.context)
        val gap = (12 * resources.displayMetrics.density).toInt()

        filters.forEachIndexed { index, filter ->
            val item = ItemFilterBinding.inflate(inflater, row, false)
            item.filterLabel.setText(filter.label)
            val isSelected = index == selectedFilter

            item.filterThumb.load(filter.preview)
            item.filterThumb.strokeColor = ColorStateList.valueOf(
                ContextCompat.getColor(
                    row.context,
                    if (isSelected) R.color.dc_accent else R.color.dc_border_3
                )
            )
            item.filterThumb.strokeWidth =
                resources.getDimension(if (isSelected) R.dimen.stroke_selected else R.dimen.stroke_hairline)

            item.filterLabel.setTextColor(
                ContextCompat.getColor(
                    row.context,
                    if (isSelected) R.color.dc_text else R.color.dc_text_80
                )
            )
            item.filterLabel.typeface = ResourcesCompat.getFont(
                row.context,
                if (isSelected) R.font.ibm_plex_sans_semibold else R.font.ibm_plex_sans_regular
            )
            item.root.setOnClickListener {
                selectedFilter = index
                bindFilters(row)
                applyPreviewFilter()
            }
            (item.root.layoutParams as LinearLayout.LayoutParams).marginStart =
                if (index == 0) 0 else gap
            row.addView(item.root)
        }
    }

    private fun currentFilter(): PhotoFilter = filters[selectedFilter]

    private fun applyPreviewFilter() {
        val binding = binding ?: return
        if (!supportsLivePreviewFilter) {
            binding.tvCameraStatus.visibility = View.VISIBLE
            binding.tvCameraStatus.text = getString(R.string.filter_preview_unsupported)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        val filter = currentFilter()
        val effective = if (comparing) PhotoFilter.NONE else filter
        binding.previewView.setRenderEffect(
            if (effective == PhotoFilter.NONE) null
            else effective.matrixAt(strength).asRenderEffect()
        )
    }

    /** Press and hold "Compare" to see the frame without the filter. */
    private fun bindCompare() {
        val binding = binding ?: return
        binding.btnCompare.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    comparing = true
                    applyPreviewFilter()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    comparing = false
                    applyPreviewFilter()
                    view.performClick()
                }
            }
            true
        }
    }

    // ── Capture ────────────────────────────────────────────────────────────

    private fun takePhoto() {
        val engine = engine ?: return
        val binding = binding ?: return
        if (capturing) return
        capturing = true
        binding.btnShutter.playShutterFeedback()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                when (
                    val result = PhotoCapture.capture(
                        context = requireContext(),
                        engine = engine,
                        filter = currentFilter(),
                        strength = strength,
                        // Vendor face retouch may not exist; then the app softens the frame
                        // itself.
                        smoothing = if (engine.usingVendorExtension) 0f else BEAUTY_AMOUNT
                    )
                ) {
                    is PhotoCapture.Result.Saved -> showLastShot(result.uri)
                    is PhotoCapture.Result.Failed -> {
                        binding.tvCameraStatus.visibility = View.VISIBLE
                        binding.tvCameraStatus.text = getString(R.string.capture_failed)
                        result.error.printStackTrace()
                    }
                }
            } finally {
                // The screen can close mid-capture; the flag must not survive it.
                capturing = false
            }
        }
    }

    override fun onShutterKey(): Boolean {
        takePhoto()
        return true
    }

    private companion object {
        /** How much softening the app applies when the camera has no beauty extension. */
        const val BEAUTY_AMOUNT = 0.45f
    }
}
