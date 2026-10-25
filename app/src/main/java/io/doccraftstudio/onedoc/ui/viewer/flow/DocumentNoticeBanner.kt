package io.doccraftstudio.onedoc.ui.viewer.flow

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.doccraftstudio.onedoc.R
import io.doccraftstudio.onedoc.reader.flow.DocumentNotice
import io.doccraftstudio.onedoc.ui.viewer.common.LocalReaderPalette

/**
 * Документ с предупреждением сверху (например, XML с ошибкой показан исходным текстом).
 * Баннер не входит в список блоков — иначе сдвинулись бы индексы позиции и закладок, —
 * а увеличивает верхний отступ [content] на свою высоту, чтобы не закрывать первые строки.
 * Закрытый баннер остаётся закрытым до выхода из документа, в том числе после поворота.
 */
@Composable
fun WithDocumentNotice(
    notice: DocumentNotice?,
    contentPadding: PaddingValues,
    content: @Composable (PaddingValues) -> Unit,
) {
    var dismissed by rememberSaveable(notice) { mutableStateOf(false) }
    var bannerHeight by remember { mutableStateOf(0.dp) }
    val visible = notice != null && !dismissed
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val top = contentPadding.calculateTopPadding()
    Box(Modifier.fillMaxSize()) {
        content(
            PaddingValues(
                start = contentPadding.calculateStartPadding(layoutDirection),
                end = contentPadding.calculateEndPadding(layoutDirection),
                top = top + if (visible) bannerHeight else 0.dp,
                bottom = contentPadding.calculateBottomPadding(),
            ),
        )
        if (notice != null && visible) {
            DocumentNoticeBanner(
                notice = notice,
                onDismiss = { dismissed = true },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = top)
                    .onSizeChanged { bannerHeight = with(density) { it.height.toDp() } },
            )
        }
    }
}

@Composable
private fun DocumentNoticeBanner(notice: DocumentNotice, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalReaderPalette.current
    Surface(
        modifier = modifier.widthIn(max = 600.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = palette.chrome,
        contentColor = palette.text,
        shadowElevation = 3.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = notice.message(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
            )
            IconButton(onClick = onDismiss) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_dismiss))
            }
        }
    }
}

@Composable
private fun DocumentNotice.message(): String = when (this) {
    is DocumentNotice.MalformedXml -> if (line != null) {
        stringResource(R.string.xml_notice_malformed_line, line)
    } else {
        stringResource(R.string.xml_notice_malformed)
    }
    DocumentNotice.XmlEntitiesNotExpanded -> stringResource(R.string.xml_notice_entities)
}
