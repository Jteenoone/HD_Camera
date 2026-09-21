package com.example.hd_camera.ui.intro

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.example.hd_camera.R

/** The three intro pages, straight from screens 01–03 of the design. */
enum class IntroPage(
    @StringRes val eyebrow: Int,
    @StringRes val title: Int,
    @StringRes val body: Int,
    @StringRes val imagePlaceholder: Int,
    @DrawableRes val image: Int,
    val showsExposureChips: Boolean = false,
    val showsFilterPips: Boolean = false
) {
    RESOLUTION(
        eyebrow = R.string.intro1_eyebrow,
        title = R.string.intro1_title,
        body = R.string.intro1_body,
        imagePlaceholder = R.string.intro_visual_1,
        image = R.drawable.intro_resolution
    ),
    PRO(
        eyebrow = R.string.intro2_eyebrow,
        title = R.string.intro2_title,
        body = R.string.intro2_body,
        imagePlaceholder = R.string.intro_visual_2,
        image = R.drawable.intro_pro,
        showsExposureChips = true
    ),
    FILTERS(
        eyebrow = R.string.intro3_eyebrow,
        title = R.string.intro3_title,
        body = R.string.intro3_body,
        imagePlaceholder = R.string.intro_visual_3,
        image = R.drawable.intro_filters,
        showsFilterPips = true
    );

    val isLast: Boolean get() = ordinal == entries.lastIndex
}
