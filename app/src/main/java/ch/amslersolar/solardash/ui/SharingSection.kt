// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import ch.amslersolar.solardash.R
import ch.amslersolar.solardash.model.Plant
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * "Share with observers" in the plant's settings: the switch, and while it's on the observer code as a QR code (for
 * the phone's camera), a button to send the link, "New code", and the stores the plant sends to.
 */
@Composable
fun SharingSection(
    plant: Plant,
    link: String?,
    onShare: () -> Unit,
    onStop: () -> Unit,
    onNewCode: () -> Unit,
    onStores: (List<String>) -> Unit,
) {
    val sharing = plant.sharing
    var stopping by remember { mutableStateOf(false) }
    var renewing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { if (sharing == null) onShare() else stopping = true }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(stringResource(R.string.screens_share), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.screens_share_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = sharing != null, onCheckedChange = { if (it) onShare() else stopping = true })
        }
        if (sharing != null && link != null) {
            val context = LocalContext.current
            val qr = remember(link) { qrCode(link) }
            Text(stringResource(R.string.screens_share_scan), style = MaterialTheme.typography.bodyMedium)
            // Dark modules on white, with the quiet zone, whatever the theme: phone cameras read it best.
            Image(
                qr.asImageBitmap(), contentDescription = stringResource(R.string.screens_share_qr),
                filterQuality = FilterQuality.None,
                modifier = Modifier.size(240.dp).background(Color.White),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                    context.startActivity(Intent.createChooser(send, null))
                }) { Text(stringResource(R.string.screens_share_link)) }
                OutlinedButton(onClick = { renewing = true }) { Text(stringResource(R.string.screens_new_code)) }
            }
            var stores by rememberSaveable(sharing.stores) { mutableStateOf(sharing.stores.joinToString("\n")) }
            val parsed = stores.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val valid = parsed.isNotEmpty() && parsed.all { it.startsWith("https://") }
            OutlinedTextField(
                stores, { stores = it },
                label = { Text(stringResource(R.string.screens_stores)) },
                isError = !valid,
                modifier = Modifier.fillMaxWidth(),
            )
            if (parsed != sharing.stores) {
                Button(enabled = valid, onClick = { onStores(parsed) }) { Text(stringResource(R.string.forms_save)) }
            }
        }
    }
    if (stopping) {
        AlertDialog(
            onDismissRequest = { stopping = false },
            title = { Text(stringResource(R.string.screens_stop_sharing_title)) },
            text = { Text(stringResource(R.string.screens_stop_sharing_text)) },
            confirmButton = { TextButton(onClick = { stopping = false; onStop() }) { Text(stringResource(R.string.screens_stop_sharing)) } },
            dismissButton = { TextButton(onClick = { stopping = false }) { Text(stringResource(R.string.forms_cancel)) } },
        )
    }
    if (renewing) {
        AlertDialog(
            onDismissRequest = { renewing = false },
            title = { Text(stringResource(R.string.screens_new_code)) },
            text = { Text(stringResource(R.string.screens_new_code_hint)) },
            confirmButton = { TextButton(onClick = { renewing = false; onNewCode() }) { Text(stringResource(R.string.screens_new_code)) } },
            dismissButton = { TextButton(onClick = { renewing = false }) { Text(stringResource(R.string.forms_cancel)) } },
        )
    }
}

/** The QR code of [text] as a bitmap, one pixel per module, with a 4-module quiet zone. */
fun qrCode(text: String): Bitmap {
    val matrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4),
    )
    val bitmap = createBitmap(matrix.width, matrix.height)
    for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
        bitmap[x, y] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return bitmap
}
