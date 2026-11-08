package com.meterproof.app

import android.app.AlertDialog
import android.content.Context

/** نافذة تفاصيل عداد من الجولة عند الضغط عليه في الخارطة، مع زر لعرض صورته إن وُجدت. */
object MeterDialog {

    fun show(context: Context, m: MeterPoint, store: EvidenceStore, onShowPhoto: (Int) -> Unit) {
        val photoIndex = m.photoId?.let { id -> store.photoPoints().firstOrNull { it.id == id }?.index }
        val b = AlertDialog.Builder(context)
            .setTitle(m.title)
            .setMessage(m.detail)
            .setNegativeButton("إغلاق", null)
        if (photoIndex != null) {
            b.setPositiveButton("عرض الصورة") { _, _ -> onShowPhoto(photoIndex) }
        }
        b.show()
    }
}
