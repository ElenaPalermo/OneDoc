package com.example.fileopener.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.fileopener.data.thumbnail.PdfThumbnailProvider
import com.example.fileopener.domain.model.DocumentFormat
import com.example.fileopener.domain.model.DocumentItem

/** Источник миниатюр PDF; `null` — в превью и тестах, тогда показываются иконки типов. */
val LocalPdfThumbnails = staticCompositionLocalOf<PdfThumbnailProvider?> { null }

/** Миниатюра первой страницы PDF; `null`, пока она рисуется, и для остальных форматов. */
@Composable
fun rememberPdfThumbnail(document: DocumentItem): ImageBitmap? {
    val provider = LocalPdfThumbnails.current
    if (provider == null || document.format != DocumentFormat.PDF) return null
    val bitmap by produceState(provider.cached(document), provider, document) {
        if (value == null) value = provider.thumbnail(document)
    }
    return remember(bitmap) { bitmap?.asImageBitmap() }
}

/** Иконка типа в списке; у PDF — миниатюра первой страницы, как только она готова. */
@Composable
fun DocumentLeadingIcon(document: DocumentItem, modifier: Modifier = Modifier) {
    val category = document.format.category
    val thumbnail = rememberPdfThumbnail(document)
    Box(modifier.size(width = 40.dp, height = 48.dp), contentAlignment = Alignment.Center) {
        if (thumbnail != null) {
            ThumbnailImage(thumbnail, Modifier.fillMaxSize(), corner = 4.dp)
        } else {
            Icon(painterResource(category.iconRes), contentDescription = null, tint = category.accentColor)
        }
    }
}

/** Обложка карточки в сетке: миниатюра PDF или крупная иконка типа на подложке его цвета. */
@Composable
fun DocumentCover(document: DocumentItem, modifier: Modifier = Modifier) {
    val category = document.format.category
    val thumbnail = rememberPdfThumbnail(document)
    Box(
        modifier.background(category.accentColor.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                painterResource(category.iconRes),
                contentDescription = null,
                tint = category.accentColor,
                modifier = Modifier.size(48.dp),
            )
        }
    }
}

@Composable
private fun ThumbnailImage(bitmap: ImageBitmap, modifier: Modifier, corner: Dp) {
    val shape = RoundedCornerShape(corner)
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = Alignment.TopCenter,
        modifier = modifier
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    )
}
