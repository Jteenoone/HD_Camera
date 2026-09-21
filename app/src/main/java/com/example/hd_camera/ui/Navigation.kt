package com.example.hd_camera.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import com.example.hd_camera.R

/** Pushes [destination] onto the single fragment host declared in activity_main.xml. */
fun Fragment.navigateTo(destination: Fragment, addToBackStack: Boolean = true) {
    parentFragmentManager.commit {
        setReorderingAllowed(true)
        replace(R.id.nav_host, destination)
        if (addToBackStack) addToBackStack(null)
    }
}

/** Replaces the whole stack — the onboarding flow hands over to the camera this way. */
fun Fragment.navigateToRoot(destination: Fragment) {
    parentFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
    parentFragmentManager.commit {
        setReorderingAllowed(true)
        replace(R.id.nav_host, destination)
    }
}

fun Fragment.navigateBack() {
    if (!parentFragmentManager.popBackStackImmediate()) {
        requireActivity().finish()
    }
}
