package com.digitalcamerahd.camera4k.selfiecamera.ui.intro

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView
import com.digitalcamerahd.camera4k.selfiecamera.R
import com.digitalcamerahd.camera4k.selfiecamera.databinding.ItemIntroBinding

class IntroPagerAdapter(
    private val onNext: () -> Unit,
    private val onFinish: () -> Unit
) : RecyclerView.Adapter<IntroPagerAdapter.PageViewHolder>() {

    private val pages = IntroPage.entries

    /**
     * Window insets reach a ViewPager2 page too late to be applied by the page itself,
     * so the fragment hands them over here.
     */
    private var topInset = 0
    private var bottomInset = 0

    fun setSystemBarInsets(top: Int, bottom: Int) {
        if (top == topInset && bottom == bottomInset) return
        topInset = top
        bottomInset = bottom
        notifyItemRangeChanged(0, itemCount)
    }

    override fun getItemCount(): Int = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val binding = ItemIntroBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.bind(pages[position])
    }

    inner class PageViewHolder(private val binding: ItemIntroBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.btnSkip.setOnClickListener { onFinish() }
        }

        fun bind(page: IntroPage) {
            // A top *padding* would leave the button's touch target sitting under the status
            // bar, where the system swallows the tap; a margin moves the whole target down.
            (binding.btnSkip.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.topMargin = topInset
                binding.btnSkip.layoutParams = params
            }
            binding.introFooter.updatePadding(bottom = footerBasePadding + bottomInset)
            binding.tvEyebrow.setText(page.eyebrow)
            binding.tvTitle.setText(page.title)
            binding.tvBody.setText(page.body)
            binding.imgVisual.setImageResource(page.image)
            // The caption was only ever a stand-in for the artwork.
            binding.tvImagePlaceholder.visibility = View.GONE
            binding.exposureChips.isVisible(page.showsExposureChips)
            binding.filterPips.isVisible(page.showsFilterPips)

            // Last page swaps the "Next" pill for a full-width "Get started".
            binding.btnNext.isVisible(!page.isLast)
            binding.btnGetStarted.isVisible(page.isLast)
            binding.footerSpacer.isVisible(!page.isLast)
            binding.btnNext.setOnClickListener { onNext() }
            binding.btnGetStarted.setOnClickListener { onFinish() }

            bindDots(page.ordinal)
        }

        private fun bindDots(active: Int) {
            val dots = listOf(binding.dot0, binding.dot1, binding.dot2)
            val density = binding.root.resources.displayMetrics.density
            dots.forEachIndexed { index, dot ->
                val isActive = index == active
                dot.layoutParams = dot.layoutParams.apply {
                    width = ((if (isActive) 22 else 6) * density).toInt()
                }
                dot.setBackgroundResource(
                    if (isActive) R.drawable.bg_dot_active else R.drawable.bg_dot_inactive
                )
            }
        }

        private fun View.isVisible(visible: Boolean) {
            visibility = if (visible) View.VISIBLE else View.GONE
        }

        private val footerBasePadding = binding.introFooter.paddingBottom
    }
}
