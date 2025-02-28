package es.upm.ies.surco.session_logging

import java.io.File
import java.io.OutputStreamWriter
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.TimeZone

object SessionWriter {
    object V3 {
        const val VERSION_SCHEME = 3
        /**
         * Creates the JSON line for the beacons static data (ID, tilt, orientation, description).
         * @param outputStreamWriter The output stream to write to.
         * @param timeZone The time zone of the session (at the end).
         * @param beacons The collection of beacons.
         * @param startZonedDateTime The start instant of the session.
         * @param finishZonedDateTime The finish instant of the session.
         * @return The JSON line String.
         *
         * Output example, formatted for readability:
         * {
         *  "version_scheme": $VERSION_SCHEME,
         *  "timezone": "Europe/Madrid",
         *  "start_instant": "2021-10-01T12:00:00Z",
         *  "finish_instant": "2021-10-01T12:30:00Z",
         *  "beacons": [
         *    {
         *      "id": "id_of_beacon1",
         *      "tilt": 0.0,
         *      "orientation": 0.0,
         *      "position": "position_of_beacon1",
         *      "description": "U295IGxhIGNvc2l0YSBtw6FzIGxpbmRhIHkgbW9uYSBkZSBlc3RlIG11bmRvLg=="
         *    },
         *    {
         *      "id": "id_of_beacon2",
         *      "tilt": 0.0,
         *      "orientation": 0.0,
         *      "position": "position_of_beacon2",
         *      "description": "U295IGxhIGNvc2l0YSBtw6FzIGxpbmRhIHkgbW9uYSBkZSBlc3RlIG11bmRvLg=="
         *    }
         *   ]
         *  }
         */
        fun createJSONHeader(
            outputStreamWriter: OutputStreamWriter,
            timeZone: TimeZone,
            beacons: Collection<BeaconSimplified>,
            startZonedDateTime: ZonedDateTime,
            finishZonedDateTime: ZonedDateTime,
        ) {
            val bufferedOutputStreamWriter = outputStreamWriter.buffered()

            val formatter = DateTimeFormatter.ISO_INSTANT

            bufferedOutputStreamWriter.append("{")
            bufferedOutputStreamWriter.append("\"version_scheme\": $VERSION_SCHEME,")  // Version of the file format.
            bufferedOutputStreamWriter.append("\"timezone\": \"${timeZone.id}\",")
            bufferedOutputStreamWriter.append("\"start_localized_instant\": \"${startZonedDateTime.format(formatter)}\",")
            bufferedOutputStreamWriter.append("\"finish_localized_instant\": \"${finishZonedDateTime.format(formatter)}\",")
            bufferedOutputStreamWriter.append("\"beacons\": [")  // Open "beacons"
            for ((index, beacon) in beacons.withIndex()) {
                // Add a comma before the next element, as JSON does not allow trailing commas.
                // https://stackoverflow.com/questions/201782/can-you-use-a-trailing-comma-in-a-json-object
                if (index > 0) {
                    bufferedOutputStreamWriter.append(",")
                }
                bufferedOutputStreamWriter.append("{")  // Open the unique beacon object.

                // Encode the description in Base64 to avoid issues with special characters (especially quotes and newlines).
                val base64encodedDescription =
                    Base64.getEncoder()
                        .encodeToString(beacon.descriptionValue.toByteArray(Charsets.UTF_8))
                bufferedOutputStreamWriter.append("\"id\": \"${beacon.id}\",")
                bufferedOutputStreamWriter.append("\"tilt\": ${beacon.tiltValue},")
                bufferedOutputStreamWriter.append("\"orientation\": ${beacon.directionValue},")
                bufferedOutputStreamWriter.append("\"position\": \"${beacon.positionValue}\",")
                bufferedOutputStreamWriter.append("\"description\": \"$base64encodedDescription\"")

                bufferedOutputStreamWriter.append("}")  // Close the unique beacon object.
            }
            bufferedOutputStreamWriter.append("]")  // Close "beacons"
            bufferedOutputStreamWriter.append("}")  // Close the JSON object.
            bufferedOutputStreamWriter.flush()
        }

        /**
         * Create the CSV header of the file.
         * @param outputStreamWriter The output stream to write to.
         *
         * Currently, it is:
         * beacon_id,timestamp,data,latitude,longitude
         */
        fun appendCsvHeader(outputStreamWriter: OutputStreamWriter) {
            outputStreamWriter.write("beacon_id,localized_timestamp,data,latitude,longitude,azimuth\n")
        }

        /**
         * Create the CSV body of the file.
         * @param outputStreamWriter The output stream to write to.
         * @param beacons The collection of beacons.
         *
         * For example:
         * <header>
         * 0x010203040506,2021-10-01T12:00:00.000,127,14,60,30
         * 0x010203040506,2021-10-01T12:00:01.000,126,14,60,30
         * 0x010203040506,2021-10-01T12:00:02.000,125,14,60,30
         * 0x010203040507,2021-10-01T12:00:00.000,127,14,60,30
         * 0x010203040507,2021-10-01T12:00:01.000,128,14,60,30
         * 0x010203040507,2021-10-01T12:00:02.000,129,14,60,30
         */
        fun appendCsvBodyFromData(
            outputStreamWriter: OutputStreamWriter,
            timeZone: TimeZone,
            beacons: Collection<BeaconSimplified>,
        ) {
            val bufferedWriter = outputStreamWriter.buffered()
            val result = StringBuilder()
            val zoneId = timeZone.toZoneId()

            val formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

            for (beacon in beacons) {
                for (entry in beacon.sensorData.value!!) {
                    val localizedTimestamp = ZonedDateTime.ofInstant(entry.timestamp, zoneId)
                    result.setLength(0)  // Clear the StringBuilder
                    result.append(beacon.id)
                        .append(",")
                        .append(localizedTimestamp.format(formatter))
                        .append(",")
                        .append(entry.data)
                        .append(",")
                        .append(entry.latitude)
                        .append(",")
                        .append(entry.longitude)
                        .append(",")
                        .append(entry.azimuth)
                        .append("\n")
                    bufferedWriter.write(result.toString())
                }
            }
            bufferedWriter.flush()
        }

        /**
         * Append the CSV body of the session, from a temporary file.
         */
        fun appendCsvBodyFromTempFile(
            outputStreamWriter: OutputStreamWriter,
            tempFile: File
        ) {
            tempFile.reader(Charsets.UTF_8).use { reader ->
                reader.copyTo(outputStreamWriter)
            }
        }
    }
}
