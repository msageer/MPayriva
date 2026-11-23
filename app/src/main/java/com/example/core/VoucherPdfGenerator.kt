package com.example.core

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.data.model.EpinVoucherRecord
import com.example.data.model.TransactionRecord
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Pure geometry specification for fixed-size 30 e-PINs per A4 portrait sheet (3 columns x 10 rows).
 * Every single e-PIN card ALWAYS uses the exact same 1/30th-A4 slot dimensions regardless of
 * whether the user is printing 1, 3, 10, 30, or 50 e-PINs.
 */
data class VoucherSlotBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

object VoucherSheetLayout {
    const val PAGE_WIDTH = 595        // Standard ISO A4 portrait width @ 72 DPI (210mm)
    const val PAGE_HEIGHT = 842       // Standard ISO A4 portrait height @ 72 DPI (297mm)
    const val COLUMNS = 3
    const val ROWS = 10
    const val CARDS_PER_PAGE = COLUMNS * ROWS // Fixed 30 e-PINs per A4 sheet

    const val MARGIN_X = 16f
    const val MARGIN_TOP = 14f
    const val MARGIN_BOTTOM = 14f
    const val HEADER_HEIGHT = 24f
    const val COL_GAP = 6f
    const val ROW_GAP = 5f

    val cardWidth: Float =
        (PAGE_WIDTH - (MARGIN_X * 2f) - (COL_GAP * (COLUMNS - 1))) / COLUMNS.toFloat()

    val cardHeight: Float =
        (PAGE_HEIGHT - MARGIN_TOP - HEADER_HEIGHT - MARGIN_BOTTOM - (ROW_GAP * (ROWS - 1))) / ROWS.toFloat()

    fun pageCountFor(voucherCount: Int): Int {
        if (voucherCount <= 0) return 1
        return (voucherCount + CARDS_PER_PAGE - 1) / CARDS_PER_PAGE
    }

    /**
     * Returns the exact fixed A4 slot rectangle for index 0..29 on any page.
     * Slot size never changes with voucher count.
     */
    fun slotBounds(indexOnPage: Int): VoucherSlotBounds {
        require(indexOnPage in 0 until CARDS_PER_PAGE) {
            "Slot index $indexOnPage out of bounds for $CARDS_PER_PAGE-slot A4 sheet"
        }
        val col = indexOnPage % COLUMNS
        val row = indexOnPage / COLUMNS
        val left = MARGIN_X + col * (cardWidth + COL_GAP)
        val top = MARGIN_TOP + HEADER_HEIGHT + row * (cardHeight + ROW_GAP)
        return VoucherSlotBounds(
            left = left,
            top = top,
            right = left + cardWidth,
            bottom = top + cardHeight
        )
    }
}

/**
 * Generates printable A4 PDF voucher sheets strictly fitted to 30 e-PINs per A4 page (3 x 10 grid)
 * for Recharge Card e-PINs, Data Card e-PINs, WAEC/JAMB PINs, and Electricity Tokens.
 *
 * Even if printing 1 or 3 e-PINs, each card preserves the exact 1/30th-A4 size so sheets cut
 * uniformly and match standard Nigerian recharge-card / data-card A4 layouts.
 */
object VoucherPdfGenerator {
    const val CARDS_PER_A4_PAGE = VoucherSheetLayout.CARDS_PER_PAGE

    fun extractPrintableVouchers(tx: TransactionRecord): List<EpinVoucherRecord> {
        if (tx.vouchers.isNotEmpty()) return tx.vouchers
        val raw = tx.tokenOrPin?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val dateStr = CurrencyFormatter.formatDateTime(tx.createdAt?.toDate())
        return lines.map { line ->
            val cleanedPin = line.replace(Regex("^#\\d+:\\s*"), "").substringBefore(" (S/N:").trim()
            val snMatch = Regex("\\(S/N:\\s*([^)]+)\\)").find(line)?.groupValues?.getOrNull(1)
                ?: tx.serialNumber.orEmpty()
            EpinVoucherRecord(
                pin = cleanedPin.ifBlank { line },
                serialNumber = snMatch,
                batchNo = tx.providerOrderId.orEmpty(),
                network = tx.productName.ifBlank { tx.resolvedType().label },
                denomination = CurrencyFormatter.formatKobo(tx.sellingPriceKobo),
                loadCode = when (tx.resolvedType().name) {
                    "RECHARGE_CARD", "DATA_CARD" -> "*311*PIN#"
                    "ELECTRICITY" -> "Enter on Prepaid Meter"
                    "WAEC_PIN" -> "www.waecdirect.org"
                    "JAMB_PIN" -> "JAMB e-Facility / CBT Center"
                    else -> "Use on Provider Portal"
                },
                transactionDate = dateStr
            )
        }
    }

    fun generateVoucherPdfFile(
        context: Context,
        tx: TransactionRecord,
        sheetHeader: String = BrandingConfig.Default.appDisplayName
    ): File {
        val vouchers = extractPrintableVouchers(tx)
        val outDir = File(context.cacheDir, "vouchers").apply { mkdirs() }
        val safeRef = tx.reference.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "VOUCHER" }
        val outFile = File(outDir, "MSageer_EPIN_${safeRef}.pdf")

        val pdf = PdfDocument()
        try {
            val pages = if (vouchers.isEmpty()) {
                listOf(emptyList())
            } else {
                vouchers.chunked(VoucherSheetLayout.CARDS_PER_PAGE)
            }
            val cleanHeader = sheetHeader.ifBlank { BrandingConfig.Default.appDisplayName }

            pages.forEachIndexed { pageIndex, pageVouchers ->
                val pageInfo = PdfDocument.PageInfo.Builder(
                    VoucherSheetLayout.PAGE_WIDTH,
                    VoucherSheetLayout.PAGE_HEIGHT,
                    pageIndex + 1
                ).create()
                val page = pdf.startPage(pageInfo)
                drawVoucherPage(
                    canvas = page.canvas,
                    tx = tx,
                    vouchers = pageVouchers,
                    sheetHeader = cleanHeader,
                    pageNumber = pageIndex + 1,
                    totalPages = pages.size,
                    totalVoucherCount = vouchers.size,
                    startGlobalIndex = pageIndex * VoucherSheetLayout.CARDS_PER_PAGE
                )
                pdf.finishPage(page)
            }
            FileOutputStream(outFile).use { fos ->
                pdf.writeTo(fos)
            }
        } finally {
            pdf.close()
        }
        return outFile
    }

    private fun drawVoucherPage(
        canvas: android.graphics.Canvas,
        tx: TransactionRecord,
        vouchers: List<EpinVoucherRecord>,
        sheetHeader: String,
        pageNumber: Int,
        totalPages: Int,
        totalVoucherCount: Int,
        startGlobalIndex: Int
    ) {
        val navy = Color.parseColor("#0A1D37")
        val emerald = Color.parseColor("#0E9F6E")
        val emeraldBright = Color.parseColor("#34D399")
        val slateDark = Color.parseColor("#1E293B")
        val muted = Color.parseColor("#475569")
        val subtle = Color.parseColor("#64748B")
        val lightBg = Color.parseColor("#F1F5F9")
        val borderColor = Color.parseColor("#94A3B8")

        val pageTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = navy
            textSize = 9.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val pageMetaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted
            textSize = 7.2f
            textAlign = Paint.Align.RIGHT
        }
        val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = borderColor
            style = Paint.Style.STROKE
            strokeWidth = 0.8f
            pathEffect = DashPathEffect(floatArrayOf(3.5f, 2.5f), 0f)
        }
        val headerBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = navy
            style = Paint.Style.FILL
        }
        val headerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 6.8f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val denomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = emeraldBright
            textSize = 7.2f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        val pinBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lightBg
            style = Paint.Style.FILL
        }
        val pinBoxStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CBD5E1")
            style = Paint.Style.STROKE
            strokeWidth = 0.5f
        }
        val pinLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = emerald
            textSize = 5.2f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val slotIndexPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = subtle
            textSize = 5.2f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
        }
        val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = navy
            textSize = 9.2f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val serialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = slateDark
            textSize = 5.8f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        val loadCodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = navy
            textSize = 5.9f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = subtle
            textSize = 5.0f
        }

        // Compact A4 Sheet Header (fits within 24pt so all 10 rows have full breathing room)
        val headerBaselineY = VoucherSheetLayout.MARGIN_TOP + 12f
        val leftHeader = fitTextToWidth(
            "$sheetHeader — E-PIN Sheet (A4 • 30/Page)",
            pageTitlePaint,
            VoucherSheetLayout.PAGE_WIDTH * 0.54f
        )
        canvas.drawText(leftHeader, VoucherSheetLayout.MARGIN_X, headerBaselineY, pageTitlePaint)

        val rightMeta = "Ref: ${tx.reference.takeLast(16)}  •  $totalVoucherCount PIN${if (totalVoucherCount == 1) "" else "s"}  •  Page $pageNumber/$totalPages"
        canvas.drawText(
            rightMeta,
            VoucherSheetLayout.PAGE_WIDTH - VoucherSheetLayout.MARGIN_X,
            headerBaselineY,
            pageMetaPaint
        )

        // Draw each e-PIN strictly inside its fixed 1/30th A4 slot (3 columns x 10 rows)
        vouchers.forEachIndexed { idx, voucher ->
            val slot = VoucherSheetLayout.slotBounds(idx)
            val left = slot.left
            val top = slot.top
            val right = slot.right
            val bottom = slot.bottom
            val innerWidth = slot.width - 10f

            // 1. Dashed cut-out border
            canvas.drawRoundRect(left, top, right, bottom, 4f, 4f, cardBorderPaint)

            // 2. Compact Navy header bar (15pt height)
            val stripBottom = top + 15f
            canvas.drawRoundRect(left + 0.8f, top + 0.8f, right - 0.8f, stripBottom, 3.5f, 3.5f, headerBarPaint)

            val denomLabel = fitTextToWidth(
                voucher.denomination.ifBlank { tx.resolvedType().label },
                denomPaint,
                slot.width * 0.42f
            )
            val denomWidth = denomPaint.measureText(denomLabel)
            val networkTitle = voucher.network.ifBlank { tx.productName.ifBlank { tx.resolvedType().label } }
            val maxHeaderLeftWidth = (innerWidth - denomWidth - 4f).coerceAtLeast(44f)
            val headerTitle = fitTextToWidth(
                "$networkTitle • ${BrandingConfig.Default.appDisplayName}",
                headerTextPaint,
                maxHeaderLeftWidth
            )
            canvas.drawText(headerTitle, left + 5f, top + 10.6f, headerTextPaint)
            canvas.drawText(denomLabel, right - 5f, top + 10.6f, denomPaint)

            // 3. Highlighted PIN Box (24pt height: top+18 .. top+42)
            val pinTop = stripBottom + 3f
            val pinBottom = pinTop + 24f
            canvas.drawRoundRect(left + 4f, pinTop, right - 4f, pinBottom, 3.5f, 3.5f, pinBoxPaint)
            canvas.drawRoundRect(left + 4f, pinTop, right - 4f, pinBottom, 3.5f, 3.5f, pinBoxStrokePaint)

            val pinHeading = if (tx.resolvedType().name == "ELECTRICITY") "METER TOKEN" else "SECRET PIN"
            canvas.drawText(pinHeading, left + 7.5f, pinTop + 7.2f, pinLabelPaint)
            canvas.drawText("#${startGlobalIndex + idx + 1}", right - 7.5f, pinTop + 7.2f, slotIndexPaint)

            val formattedPin = formatPinReadable(voucher.pin)
            val maxPinWidth = slot.width - 15f
            pinPaint.textSize = computeFittedTextSize(formattedPin, pinPaint, maxPinWidth, defaultSize = 9.2f, minSize = 6.2f)
            canvas.drawText(formattedPin, left + 7.5f, pinTop + 19.8f, pinPaint)
            pinPaint.textSize = 9.2f

            // 4. Compact Metadata Rows (Serial / Batch, Load Code, Ref & Date)
            val serialLine = when {
                voucher.serialNumber.isNotBlank() -> "S/N: ${voucher.serialNumber}"
                voucher.batchNo.isNotBlank() -> "Batch: ${voucher.batchNo}"
                else -> "Order: ${tx.reference.takeLast(12)}"
            }
            canvas.drawText(
                fitTextToWidth(serialLine, serialPaint, innerWidth),
                left + 5f,
                pinBottom + 9.0f,
                serialPaint
            )

            val loadInstruction = "Dial/Load: ${voucher.loadCode.ifBlank { "*311*PIN#" }}"
            canvas.drawText(
                fitTextToWidth(loadInstruction, loadCodePaint, innerWidth),
                left + 5f,
                pinBottom + 17.5f,
                loadCodePaint
            )

            val footerText = "Ref ${tx.reference.takeLast(10)} • ${voucher.transactionDate.take(16)}"
            canvas.drawText(
                fitTextToWidth(footerText, footerPaint, innerWidth),
                left + 5f,
                bottom - 4.0f,
                footerPaint
            )
        }
    }

    private fun fitTextToWidth(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + ellipsis) > maxWidth) {
            end--
        }
        return text.substring(0, end).trimEnd() + ellipsis
    }

    private fun computeFittedTextSize(
        text: String,
        paint: Paint,
        maxWidth: Float,
        defaultSize: Float,
        minSize: Float
    ): Float {
        paint.textSize = defaultSize
        val measured = paint.measureText(text)
        if (measured <= maxWidth || measured <= 0f) return defaultSize
        val scaled = defaultSize * (maxWidth / measured)
        return scaled.coerceIn(minSize, defaultSize)
    }

    private fun formatPinReadable(pin: String): String {
        val digitsOnly = pin.replace(Regex("\\s+"), "")
        return if (digitsOnly.length >= 12 && digitsOnly.all { it.isDigit() }) {
            digitsOnly.chunked(4).joinToString(" ")
        } else {
            pin.take(28)
        }
    }

    /**
     * Launches Android's native Print Spooler pre-configured to ISO A4 Portrait,
     * where the user can print directly or tap "Save as PDF" without storage permissions.
     */
    fun printOrSaveAsPdf(
        context: Context,
        tx: TransactionRecord,
        sheetHeader: String = BrandingConfig.Default.appDisplayName
    ) {
        try {
            val vouchers = extractPrintableVouchers(tx)
            val totalPages = VoucherSheetLayout.pageCountFor(vouchers.size)
            val pdfFile = generateVoucherPdfFile(context, tx, sheetHeader)
            val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
            if (printManager == null) {
                shareVoucherPdf(context, tx, sheetHeader)
                return
            }
            val jobName = "MSageer_EPIN_${tx.reference}"
            val adapter = object : PrintDocumentAdapter() {
                override fun onLayout(
                    oldAttributes: PrintAttributes?,
                    newAttributes: PrintAttributes?,
                    cancellationSignal: CancellationSignal?,
                    callback: LayoutResultCallback,
                    extras: Bundle?
                ) {
                    if (cancellationSignal?.isCanceled == true) {
                        callback.onLayoutCancelled()
                        return
                    }
                    val info = PrintDocumentInfo.Builder("$jobName.pdf")
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(totalPages)
                        .build()
                    callback.onLayoutFinished(info, true)
                }

                override fun onWrite(
                    pages: Array<out PageRange>?,
                    destination: ParcelFileDescriptor,
                    cancellationSignal: CancellationSignal?,
                    callback: WriteResultCallback
                ) {
                    try {
                        FileInputStream(pdfFile).use { input ->
                            FileOutputStream(destination.fileDescriptor).use { output ->
                                input.copyTo(output)
                            }
                        }
                        callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    } catch (e: Exception) {
                        callback.onWriteFailed(e.message)
                    }
                }
            }
            val a4Attributes = PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build()
            printManager.print(jobName, adapter, a4Attributes)
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to open print preview: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Generates the 30-per-A4 PDF and opens Android's Share/Save chooser via FileProvider.
     */
    fun shareVoucherPdf(
        context: Context,
        tx: TransactionRecord,
        sheetHeader: String = BrandingConfig.Default.appDisplayName
    ) {
        try {
            val pdfFile = generateVoucherPdfFile(context, tx, sheetHeader)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                pdfFile
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "M-Sageer Pay E-PIN Vouchers (30/A4) - ${tx.reference}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Save or Share E-PIN PDF"))
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to export PDF: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
