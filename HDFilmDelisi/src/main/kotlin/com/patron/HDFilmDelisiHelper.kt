package com.patron

import android.content.Context

object HDFilmDelisiHelper {
    var isAllowedVersion: Boolean = true
        private set

    fun setup(context: Context) {
        isAllowedVersion = true
    }
}
