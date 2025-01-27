package com.example.lightsensorlogger_2

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView


// class to navivgate the different views in app
class LocationAdapter(
    private val locations: List<Pair<String, String>>,
    private val onLocationSelected: (String, String) -> Unit
) : RecyclerView.Adapter<LocationAdapter.LocationViewHolder>() {

    class LocationViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val locationName: TextView = view.findViewById(R.id.location_name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LocationViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_location, parent, false)
        return LocationViewHolder(view)
    }

    override fun onBindViewHolder(holder: LocationViewHolder, position: Int) {
        val (name, type) = locations[position]
        holder.locationName.text = name
        holder.itemView.setOnClickListener { onLocationSelected(name, type) }
    }

    override fun getItemCount() = locations.size
}
