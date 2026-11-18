package io.github.weavespeak.client

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager

/**
 * Hold the phone to your ear and it behaves like a phone call: the screen goes dark (so a
 * cheek cannot press Mute or Leave) and the sound moves to the earpiece. Lower it, and both
 * come back.
 *
 * The darkening is Android's own (PROXIMITY_SCREEN_OFF_WAKE_LOCK turns the screen off while
 * the sensor reads near); this class adds the earpiece, through AudioRoute.ear().
 *
 * Armed only while the page says so — in voice, camera off, the app on screen — and never
 * on the strength of a single reading. A phone that already reads "near" the moment it is
 * armed is in a pocket or face down on a table, not being lifted, so nothing happens until
 * it has read "far" once. And a worn headset is left alone: somebody wearing one who leans
 * on the phone has not asked for the earpiece.
 */
class EarSensor(context: Context) : SensorEventListener {

    private val app = context.applicationContext
    private val sensors = app.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val power = app.getSystemService(PowerManager::class.java)
    private var lock: PowerManager.WakeLock? = null

    private var armed = false
    private var seenFar = false
    private var near = false

    val available: Boolean get() = sensor != null

    fun arm(on: Boolean) {
        if (on == armed || sensor == null) return
        armed = on
        if (on) {
            seenFar = false
            sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            sensors.unregisterListener(this)
            setNear(false)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val max = sensor?.maximumRange ?: return
        // Most proximity sensors are really two-state, reporting either 0 or their maximum.
        val reading = event.values.firstOrNull() ?: return
        val isNear = reading < max && reading < NEAR_CM
        if (!isNear) seenFar = true
        if (!seenFar) return
        setNear(isNear && !headsetWorn())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun headsetWorn(): Boolean {
        val kind = AudioRoute.of(app).current.substringBefore(':')
        return kind != AudioRoute.KIND_SPEAKER && kind != AudioRoute.KIND_EARPIECE
    }

    private fun setNear(value: Boolean) {
        if (value == near) return
        near = value
        if (value) {
            if (lock == null && power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                lock = power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "weave:ear").apply {
                    setReferenceCounted(false)
                    acquire(MAX_HOLD_MS)
                }
            }
        } else {
            // RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY: the screen comes back once the face has
            // actually gone, not at the instant the lock is let go.
            lock?.takeIf { it.isHeld }?.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
            lock = null
        }
        AudioRoute.of(app).ear(value)
    }

    companion object {
        /** Closer than this is a face; the sensors that report distances agree on ~5 cm. */
        private const val NEAR_CM = 5f
        /** A safety net: no call keeps the screen dark for longer than this on one lift. */
        private const val MAX_HOLD_MS = 2 * 60 * 60 * 1000L
    }
}
