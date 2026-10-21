package com.example.fileopener.ui.common

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.example.fileopener.R
import com.example.fileopener.domain.model.DocumentCategory

/** Иконка, подпись и фирменный цвет типа документа (не зависят от dynamic color, чтобы тип узнавался сразу). */
@get:DrawableRes
val DocumentCategory.iconRes: Int
    get() = when (this) {
        DocumentCategory.PDF -> R.drawable.ic_doc_pdf
        DocumentCategory.WORD -> R.drawable.ic_doc_word
        DocumentCategory.SPREADSHEET -> R.drawable.ic_doc_sheet
        DocumentCategory.TEXT -> R.drawable.ic_doc_text
    }

@get:StringRes
val DocumentCategory.labelRes: Int
    get() = when (this) {
        DocumentCategory.PDF -> R.string.category_pdf
        DocumentCategory.WORD -> R.string.category_word
        DocumentCategory.SPREADSHEET -> R.string.category_spreadsheet
        DocumentCategory.TEXT -> R.string.category_text
    }

val DocumentCategory.accentColor: Color
    get() = when (this) {
        DocumentCategory.PDF -> Color(0xFFD93025)
        DocumentCategory.WORD -> Color(0xFF2B579A)
        DocumentCategory.SPREADSHEET -> Color(0xFF217346)
        DocumentCategory.TEXT -> Color(0xFF757575)
    }
