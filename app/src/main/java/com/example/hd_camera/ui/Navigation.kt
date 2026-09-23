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

/**
 * Swaps one screen for another at the same level: the one being left is dropped first, so
 * hopping between the viewfinders does not pile them up. Whatever is underneath — Home —
 * stays put, and Back still returns there.
 */
fun Fragment.navigateSibling(destination: Fragment) {
    parentFragmentManager.popBackStack()
    parentFragmentManager.commit {
        setReorderingAllowed(true)
        replace(R.id.nav_host, destination)
        addToBackStack(null)
    }
}

fun Fragment.navigateBack() {
    if (!parentFragmentManager.popBackStackImmediate()) {
        requireActivity().finish()
    }
}

/**
 * The X on every screen off Home. Home is always the root of the stack — the activity puts
 * it there on launch and onboarding hands over with [navigateToRoot] — so unwinding the whole
 * back stack lands on it however deep the user has gone.
 */
fun Fragment.navigateHome() {
    parentFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
}
