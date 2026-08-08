package nl.maalstroom.marine

data class MarineSnapshot(
    val latitudeDisplay: String = "—",
    val longitudeDisplay: String = "—",
    val positionAvailable: Boolean = false,

    val depthRaw: Double? = null,
    val depthOffset: Double = 0.0,

    val trueHeading: Double? = null,
    val trueWindAngle: Double? = null,
    val trueWindDirection: Double? = null,
    val trueWindSpeedKnots: Double? = null,

    val cogDegrees: Double? = null,
    val sogKnots: Double? = null,
    val stwKnots: Double? = null,
    val rudderDegrees: Double? = null,
    val pitchDegrees: Double? = null,
    val rollDegrees: Double? = null,
    val yawDegrees: Double? = null,
    val distanceToWaypointNm: Double? = null,
    val commandedCourseDegrees: Double? = null,
    val autopilotMode: String? = null,

    val lastNmeaMillis: Long = 0L
)

object NmeaStateStore {
    private val lock = Any()
    private var state = MarineSnapshot()

    fun snapshot(): MarineSnapshot =
        synchronized(lock) { state.copy() }

    fun update(transform: (MarineSnapshot) -> MarineSnapshot) {
        synchronized(lock) {
            state = transform(state)
        }
    }
}
