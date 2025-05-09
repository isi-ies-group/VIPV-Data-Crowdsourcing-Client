package es.upm.ies.surco.ui

import android.content.Context
import android.graphics.PorterDuff
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import es.upm.ies.surco.R
import es.upm.ies.surco.databinding.RowItemBeaconBinding
import es.upm.ies.surco.session_logging.BeaconSimplified
import es.upm.ies.surco.session_logging.BeaconSimplifiedStatus
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.toString

class ListAdapterBeacons(
    activityContext: Context,
    private var beaconsList: List<BeaconSimplified>,
    private val lifecycleOwner: LifecycleOwner
) : ArrayAdapter<BeaconSimplified>(activityContext, R.layout.row_item_beacon, beaconsList) {

    private val observers = mutableMapOf<String, Observer<BeaconSimplifiedStatus>>()

    override fun getCount(): Int = beaconsList.size

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val beacon = getItem(position)
        if (beacon == null) {
            Log.e("BeaconListAdapter", "Beacon is null")
        }

        val binding: RowItemBeaconBinding
        val view: View

        if (convertView == null) {
            binding = RowItemBeaconBinding.inflate(LayoutInflater.from(context), parent, false)
            view = binding.root
            view.tag = binding
        } else {
            binding = convertView.tag as RowItemBeaconBinding
            view = convertView
        }

        binding.tvBeaconIdentifier.text = beacon?.id.toString()
        binding.tvBeaconLastReading.text = beacon?.sensorData?.value?.lastOrNull()?.data.toString()
        binding.tvBeaconLastSeen.text = beacon?.sensorData?.value?.lastOrNull()?.timestamp?.let {
            timestampFormatter.format(it)  // to local time
        }.orEmpty()
        binding.tvBeaconInfoIncomplete.visibility =
            if (beacon?.statusValue?.value != BeaconSimplifiedStatus.INFO_MISSING) View.GONE else View.VISIBLE

        // Remove existing observer if any
        observers[beacon!!.id.toString()]?.let {
            beacon.statusValue.removeObserver(it)
        }

        val observer = Observer<BeaconSimplifiedStatus> { status ->
            val (imageResource, tintColor, contentDescription) = when (status) {
                BeaconSimplifiedStatus.OFFLINE -> Triple(
                    R.drawable.bluetooth_off,
                    R.color.warning_red,
                    context.getString(R.string.beacon_detail_out_of_range_error)
                )

                BeaconSimplifiedStatus.INFO_MISSING -> Triple(
                    R.drawable.warning,
                    R.color.warning_orange,
                    context.getString(R.string.beacon_detail_info_error)
                )

                else -> Triple(
                    R.drawable.check, R.color.green_ok, context.getString(R.string.beacon_detail_ok)
                )  // OK
            }
            binding.ivBeaconStatus.setImageResource(imageResource)
            binding.ivBeaconStatus.setColorFilter(
                context.getColor(tintColor), PorterDuff.Mode.SRC_IN
            )
            binding.ivBeaconStatus.contentDescription = contentDescription
        }

        // Add new observer
        beacon.statusValue.observe(lifecycleOwner, observer)
        observers[beacon.id.toString()] = observer

        return view
    }

    /**
     * Updates the data in the adapter.
     * @param beacons The new list of beacons.
     */
    fun updateData(beacons: List<BeaconSimplified>) {
        beaconsList = beacons
        clear()
        addAll(beacons)
        notifyDataSetChanged()
    }

    companion object {
        val timestampFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    }
}
