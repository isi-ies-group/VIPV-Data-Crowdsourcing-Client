package es.upm.ies.surco

import android.content.Context
import androidx.lifecycle.MutableLiveData
import es.upm.ies.surco.R

/**
 * Notifies the observers of a MutableLiveData.
 */
fun <T> MutableLiveData<T>.notifyObservers() {
    this.postValue(this.value)
}

/**
 * Creates a map with the beacon positions,
 * assigns localized keys to the unlocalized positions strings.
 * These values are written to the JSON session file.
 */
fun createPositionMap(context: Context): Map<String, String> {
    // localized values
    val positions = context.resources.getStringArray(R.array.beacon_positions)
    // unlocalized keys
    val values = context.resources.getStringArray(R.array.beacon_spinner_keys)
    return positions.zip(values).toMap()
}
