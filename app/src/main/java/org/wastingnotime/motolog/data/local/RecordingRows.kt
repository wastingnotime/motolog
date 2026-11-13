package org.wastingnotime.motolog.data.local

import androidx.room.*
import org.wastingnotime.motolog.domain.model.Observation

/** Bounded mutable checkpoint; raw evidence and closed gaps remain append-only. */
@Entity(foreignKeys = [ForeignKey(entity = RideSession::class, parentColumns = ["id"], childColumns = ["rideId"])])
data class RecordingCursor(
    @PrimaryKey val rideId: String,
    val nextSequence: Long = 0,
    val lastSampleId: String? = null,
    val lastEventSpeed: Double? = null,
    val clockEpoch: String,
    val lastElapsed: Long? = null,
    val minimumElapsed: Long = 0,
    @Embedded(prefix = "last_") val last: Observation,
    @Embedded(prefix = "gap_") val gap: Observation?,
    val gapReason: String? = null,
    val rejected: Long = 0,
    val tentativeGap: Boolean = false,
)

/** Nullable text retains even NaN/infinite/missing native fields without SQLite coercion. */
@Entity(foreignKeys = [ForeignKey(entity = RideSession::class, parentColumns = ["id"], childColumns = ["rideId"])],
    indices = [Index(value = ["rideId", "sequence"], unique = true)])
data class QualityFix(
    @PrimaryKey val id: String, val rideId: String, val sequence: Long,
    @Embedded(prefix = "observed_") val observed: Observation,
    val clockEpoch: String, val elapsed: Long, val receivedElapsed: Long,
    val latitude: String, val longitude: String, val accuracy: String?,
    val speed: String?, val speedAccuracy: String?, val rejection: String?,
)

@Entity(primaryKeys = ["bikeId", "date", "zone"], foreignKeys = [ForeignKey(entity = Bike::class,
    parentColumns = ["id"], childColumns = ["bikeId"])])
data class RideDay(val bikeId: String, val date: String, val zone: String,
    val meters: Double = 0.0, val eligibleMillis: Long = 0, val gaps: Long = 0, val rejected: Long = 0)

/** Versioned per-ride totals, updated atomically with raw evidence and daily allocations. */
@Entity(foreignKeys = [ForeignKey(entity = RideSession::class, parentColumns = ["id"], childColumns = ["rideId"])])
data class RideTotal(@PrimaryKey val rideId: String, val algorithmVersion: String,
    val meters: Double = 0.0, val eligibleMillis: Long = 0)

/** One bounded detection checkpoint; never restored as armed after process loss. */
@Entity
data class AutomaticState(@PrimaryKey val slot: Int = 1, val bike: String,
    val epoch: String, val minimumElapsed: Long, val payload: String = "[]",
    val idleSince: Long? = null, val version: String = org.wastingnotime.motolog.domain.ride.AutomaticDetection.VERSION)
