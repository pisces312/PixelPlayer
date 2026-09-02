package com.theveloper.pixelplay.utils

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Lightweight gate for Google Play Services (GMS) availability.
 *
 * PixelPlayer runs on devices without GMS (Huawei, Amazon Fire, many
 * China-market ROMs). Touching any GMS API (Wearable, Cast) on such devices
 * triggers the framework's "Update Google Play services" notification. The
 * startup-path connection points are gated behind this check so that
 * notification never fires while GMS-backed features degrade gracefully.
 */
object GmsAvailability {
    fun isAvailable(context: Context): Boolean =
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
}
