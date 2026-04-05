package dev.pl36.cameralink.core.geotag

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dev.pl36.cameralink.core.logging.D
import dev.pl36.cameralink.core.model.GeoTagLocationSample
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class GeoTagLocationManager(
    private val context: Context,
) {
    private val client: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
    private var callback: LocationCallback? = null
    private val geocoder: Geocoder? = if (Geocoder.isPresent()) Geocoder(context) else null

    @SuppressLint("MissingPermission")
    suspend fun captureCurrentLocation(): GeoTagLocationSample {
        D.geo("captureCurrentLocation: requesting HIGH_ACCURACY fix")
        D.timeStart("geo_capture")
        val location = suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationTokenSource()
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token)
                .addOnSuccessListener { location ->
                    val resolved = location ?: return@addOnSuccessListener continuation.resumeWithException(
                        IllegalStateException("Location fix unavailable."),
                    )
                    D.geo("Fix acquired: lat=${resolved.latitude}, lon=${resolved.longitude}, acc=${resolved.accuracy}m")
                    continuation.resume(resolved)
                }
                .addOnFailureListener { e ->
                    D.err("GEO", "getCurrentLocation failed", e)
                    continuation.resumeWithException(e)
                }

            continuation.invokeOnCancellation {
                D.geo("captureCurrentLocation cancelled")
                cancellation.cancel()
            }
        }
        val sample = location.toSample(source = "pin").withPlaceName()
        D.timeEnd("GEO", "geo_capture", "captureCurrentLocation done, place=${sample.placeName}")
        return sample
    }

    @SuppressLint("MissingPermission")
    fun startSession(
        onSample: (GeoTagLocationSample) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        stopSession()
        D.geo("startSession: interval=60s, minInterval=15s, maxDelay=120s")
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            60_000L,
        )
            .setMinUpdateIntervalMillis(15_000L)
            .setMaxUpdateDelayMillis(120_000L)
            .setWaitForAccurateLocation(false)
            .build()

        val sessionCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                D.geo("Session update: ${result.locations.size} location(s)")
                result.locations.forEach { location ->
                    D.geo("  lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}m")
                    onSample(location.toSample(source = "session"))
                }
            }
        }
        callback = sessionCallback
        client.requestLocationUpdates(
            locationRequest,
            sessionCallback,
            Looper.getMainLooper(),
        ).addOnFailureListener { e ->
            D.err("GEO", "requestLocationUpdates failed", e)
            onFailure(e)
        }
    }

    fun stopSession() {
        val hadCallback = callback != null
        callback?.let(client::removeLocationUpdates)
        callback = null
        D.geo("stopSession: wasActive=$hadCallback")
    }

    private fun Location.toSample(source: String): GeoTagLocationSample {
        return GeoTagLocationSample(
            capturedAtMillis = time,
            latitude = latitude,
            longitude = longitude,
            altitude = if (hasAltitude()) altitude else null,
            speedMps = if (hasSpeed()) speed else null,
            bearingDegrees = if (hasBearing()) bearing else null,
            accuracyMeters = accuracy,
            source = source,
        )
    }

    @Suppress("DEPRECATION")
    private suspend fun GeoTagLocationSample.withPlaceName(): GeoTagLocationSample {
        val geo = geocoder ?: run {
            D.geo("Geocoder not available, skipping place name")
            return this
        }
        return withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    suspendCancellableCoroutine { continuation ->
                        geo.getFromLocation(latitude, longitude, 1) { addresses ->
                            val name = addresses.firstOrNull()?.let { addr ->
                                addr.locality ?: addr.subAdminArea ?: addr.adminArea
                            }
                            D.geo("Geocoded: ($latitude, $longitude) -> $name")
                            continuation.resume(copy(placeName = name))
                        }
                    }
                } else {
                    val addresses = geo.getFromLocation(latitude, longitude, 1)
                    val name = addresses?.firstOrNull()?.let { addr ->
                        addr.locality ?: addr.subAdminArea ?: addr.adminArea
                    }
                    D.geo("Geocoded (legacy): ($latitude, $longitude) -> $name")
                    copy(placeName = name)
                }
            } catch (e: Exception) {
                D.err("GEO", "Geocoding failed for ($latitude, $longitude)", e)
                this@withPlaceName
            }
        }
    }
}
