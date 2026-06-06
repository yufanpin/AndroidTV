package com.tivimatelite.player

object AmlogicSurfaceWorkaround {
    fun shouldForceSetOutputSurfaceWorkaround(sdkInt: Int, codecName: String): Boolean {
        return sdkInt <= 28 && codecName.contains("amlogic", ignoreCase = true)
    }
}
