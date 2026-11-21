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
 * Generates printable A4 PDF voucher sheets (2 x 5 cut-out cards per page)
 * for Recharge Card e-PINs, Data Card e-PINs, WAEC/JAMB PINs, and Electricity Tokens.
 *
 * Uses zero-permission Android PrintManager ("Print" or "Save as PDF")
 * and FileProvider PDF sharing so customers can print immediately OR anytime
 * later from Transaction History.
 */
object VoucherPdfGenerator {
    private const val PAGE_WIDTH = 595  // A4 portrait @ 72dpi
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 24f
    private const val CARDS_PER_PAGE = 10 // 2 columns x 5 rows

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
        businessHeader: String = BrandingConfig.Default.appDisplayName
    ): File {
        val vouchers = extractPrintableVouchers(tx)
        val outDir = File(context.cacheDir, "vouchers").apply { mkdirs() }
        val safeRef = tx.reference.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "VOUCHER" }
        val outFile = File(outDir, "MSageer_EPIN_${safeRef}.pdf")

        val pdf = PdfDocument()
        try {
            val pages = if (vouchers.isEmpty()) listOf(emptyList()) else vouchers.chunked(CARDS_PER_PAGE)
            pages.forEachIndexed { pageIndex, pageVouchers ->
                val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageIndex + 1).create()
                val page = pdf.startPage(pageInfo)
                drawVoucherPage(
                    canvas = page.canvas,
                    tx = tx,
                    vouchers = pageVouchers,
                    businessHeader = businessHeader.ifBlank { BrandingConfig.Default.appDisplayName },
                    pageNumber = pageIndex + 1,
                    totalPages = pages.size
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
        businessHeader: String,
        pageNumber: Int,
        totalPages: Int
    ) {
        val navy = Color.parseColor("#0A1D37")
        val emerald = Color.parseColor("#0E9F6E")
        val muted = Color.parseColor("#475569")
        val lightBg = Color.parseColor("#F1F5F9")

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = navy
            textSize = 14f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted
            textSize = 9f
        }
        val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
            style = Paint.Style.STROKE
            strokeWidth = 1f
            pathEffect = DashPathEffect(floatArrayOf(5f, 4f), 0f)
        }
        val headerBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = navy
            style = Paint.Style.FILL
        }
        val headerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 9.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val denomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#34D399")
            textSize = 10f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        val pinBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lightBg
            style = Paint.Style.FILL
        }
        val pinLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = emerald
            textSize = 7.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = navy
            textSize = 13.5f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted
            textSize = 8f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }

        // Page Header
        canvas.drawText("$businessHeader — Printable E-PIN Voucher Sheet", MARGIN, MARGIN + 14f, titlePaint)
        canvas.drawText(
            "Ref: ${tx.reference}  •  ${tx.productName}  •  Page $pageNumber of $totalPages",
            MARGIN,
            MARGIN + 27f,
            subtitlePaint
        )

        val topStart = MARGIN + 40f
        val colGap = 14f
        val rowGap = 12f
        val cardWidth = (PAGE_WIDTH - (MARGIN * 2) - colGap) / 2f
        val cardHeight = (PAGE_HEIGHT - topStart - MARGIN - (rowGap * 4)) / 5f

        vouchers.forEachIndexed { idx, voucher ->
            val col = idx % 2
            val row = idx / 2
            val left = MARGIN + col * (cardWidth + colGap)
            val top = topStart + row * (cardHeight + rowGap)
            val right = left + cardWidth
            val bottom = top + cardHeight

            // Dashed cut-out border
            canvas.drawRoundRect(left, top, right, bottom, 8f, 8f, cardBorderPaint)

            // Navy header strip
            val stripBottom = top + 24f
            canvas.drawRoundRect(left + 1f, top + 1f, right - 1f, stripBottom, 7f, 7f, headerBarPaint)
            val headerTitle = "${businessHeader.take(18)} • ${voucher.network.take(14)}"
            canvas.drawText(headerTitle, left + 8f, top + 16f, headerTextPaint)
            canvas.drawText(voucher.denomination.ifBlank { tx.resolvedType().label }, right - 8f, top + 16f, denomPaint)

            // PIN Highlight Box
            val pinTop = stripBottom + 8f
            val pinBottom = pinTop + 34f
            canvas.drawRoundRect(left + 8f, pinTop, right - 8f, pinBottom, 6f, 6f, pinBoxPaint)
            canvas.drawText("SECRET PIN / TOKEN", left + 14f, pinTop + 11f, pinLabelPaint)
            val formattedPin = formatPinReadable(voucher.pin)
            canvas.drawText(formattedPin, left + 14f, pinTop + 27f, pinPaint)

            // Metadata lines
            var y = pinBottom + 13f
            if (voucher.serialNumber.isNotBlank()) {
                canvas.drawText("S/N: ${voucher.serialNumber}", left + 10f, y, metaPaint)
                y += 12f
            }
            if (voucher.batchNo.isNotBlank()) {
                canvas.drawText("Batch: ${voucher.batchNo}", left + 10f, y, metaPaint)
                y += 12f
            }
            canvas.drawText("Load: ${voucher.loadCode.ifBlank { "*311*PIN#" }}", left + 10f, y, metaPaint)
            canvas.drawText(
                "Ref: ${tx.reference.takeLast(14)}  •  ${voucher.transactionDate.take(16)}",
                left + 10f,
                bottom - 8f,
                subtitlePaint
            )
        }
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
     * Launches Android's native Print Spooler, where the user can either print
     * directly to a printer OR tap "Save as PDF" without requiring storage permissions.
     */
    fun printOrSaveAsPdf(
        context: Context,
        tx: TransactionRecord,
        businessHeader: String = BrandingConfig.Default.appDisplayName
    ) {
        try {
            val pdfFile = generateVoucherPdfFile(context, tx, businessHeader)
            val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
            if (printManager == null) {
                shareVoucherPdf(context, tx, businessHeader)
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
                        .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
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
            printManager.print(jobName, adapter, PrintAttributes.Builder().build())
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to open print preview: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Generates the PDF and opens Android's Share/Save chooser via FileProvider
     * so the user can save to Files/Drive or send via WhatsApp/Email.
     */
    fun shareVoucherPdf(
        context: Context,
        tx: TransactionRecord,
        businessHeader: String = BrandingConfig.Default.appDisplayName
    ) {
        try {
            val pdfFile = generateVoucherPdfFile(context, tx, businessHeader)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                pdfFile
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "M-Sageer Pay E-PIN Vouchers - ${tx.reference}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Save or Share E-PIN PDF"))
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to export PDF: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
