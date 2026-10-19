package com.example.viavox.data

import com.example.viavox.model.SavedTrip
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Exports GPS tracks from the private SQLite history; only the user's explicit
 * Share/Export action makes the file available to another Android application.
 */
object GpxTripExporter {
    fun encode(trip: SavedTrip): String {
        require(trip.points.isNotEmpty()) { "O percurso não possui pontos GPS válidos." }
        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append('\n')
            append("""<gpx version="1.1" creator="ViaVox" xmlns="http://www.topografix.com/GPX/1/1">""")
            append('\n')
            append("<metadata><name>ViaVox - Percurso ")
            append(trip.id)
            append("</name></metadata>\n<trk><name>Percurso ")
            append(trip.id)
            append("</name><trkseg>\n")
            for (point in trip.points) {
                require(point.latitude.isFinite() && point.longitude.isFinite() &&
                    point.latitude in -90.0..90.0 &&
                    point.longitude in -180.0..180.0
                ) { "O percurso contém coordenadas inválidas." }
                append(String.format(Locale.US,
                    """<trkpt lat="%.7f" lon="%.7f"><time>%s</time></trkpt>""",
                    point.latitude,point.longitude,
                    DateTimeFormatter.ISO_INSTANT.format(
                        Instant.ofEpochMilli(point.recordedAt)
                    )
                ))
                append('\n')
            }
            append("</trkseg></trk></gpx>\n")
        }
    }
}
