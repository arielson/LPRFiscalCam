package br.net.ari.lprfiscalcam.adapters

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import br.net.ari.lprfiscalcam.models.Fiscalizacao

class FiscalizacaoAdapter(
    context: Context?, textViewResourceId: Int,
    private val values: List<Fiscalizacao>
) : ArrayAdapter<Fiscalizacao>(context!!, textViewResourceId, values) {
    override fun getCount(): Int {
        return values.size
    }

    override fun getItem(position: Int): Fiscalizacao {
        return values[position]
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val label = super.getView(position, convertView, parent) as TextView
        label.setTextColor(Color.BLACK)
        val texto = String.format(
            "%s - %s [%s - %s]",
            values[position].codigo,
            values[position].titulo,
            values[position].dataInicio,
            values[position].dataFim
        )
        label.text = texto
        return label
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        val label = super.getDropDownView(position, convertView, parent) as TextView
        label.setTextColor(Color.BLACK)
        val texto = String.format("%s - %s", values[position].codigo, values[position].titulo)
        label.text = texto
        return label
    }
}