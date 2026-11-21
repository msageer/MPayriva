package com.example.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.NavyDeep
import com.example.ui.theme.PureWhite
import com.example.ui.theme.StatusSuccess

/**
 * Full-screen in-app payment gateway dialog powered by Android WebView.
 * Guarantees users can ALWAYS cancel or navigate back without getting trapped,
 * and automatically intercepts payment completion redirects to verify & credit the wallet.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InAppPaymentCheckoutDialog(
    checkoutUrl: String,
    reference: String,
    onCancelAndClose: () -> Unit,
    onPaymentCompleted: (reference: String) -> Unit
) {
    var loadProgress by remember { mutableIntStateOf(15) }
    var isLoading by remember { mutableStateOf(true) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var handledReturn by remember { mutableStateOf(false) }

    fun inspectUrlForReturn(url: String?): Boolean {
        if (url.isNullOrBlank() || handledReturn) return false
        val lower = url.lowercase()
        val parsed = runCatching { Uri.parse(url) }.getOrNull()
        val statusParam = parsed?.getQueryParameter("status")?.lowercase().orEmpty()
        val isCallbackPath = lower.contains("/payment-complete")

        if (statusParam == "cancelled" || statusParam == "canceled") {
            handledReturn = true
            onCancelAndClose()
            return true
        }
        if (isCallbackPath || statusParam == "successful" || statusParam == "completed") {
            handledReturn = true
            onPaymentCompleted(reference)
            return true
        }
        return false
    }

    Dialog(
        onDismissRequest = onCancelAndClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        BackHandler {
            val wv = webViewRef
            if (wv != null && wv.canGoBack()) {
                wv.goBack()
            } else {
                onCancelAndClose()
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                webViewRef?.stopLoading()
                webViewRef?.destroy()
                webViewRef = null
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // Top Secure Checkout Bar — Cancel / Back ALWAYS visible & clickable
                Surface(
                    color = NavyDeep,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                val wv = webViewRef
                                if (wv != null && wv.canGoBack()) {
                                    wv.goBack()
                                } else {
                                    onCancelAndClose()
                                }
                            },
                            modifier = Modifier.testTag("checkout_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Go back",
                                tint = PureWhite
                            )
                        }

                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = "Encrypted checkout",
                            tint = StatusSuccess,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Secure Payment Checkout",
                                style = MaterialTheme.typography.titleSmall,
                                color = PureWhite,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Ref: ${reference.takeLast(16)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = PureWhite.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        TextButton(
                            onClick = onCancelAndClose,
                            modifier = Modifier.testTag("checkout_cancel_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Cancel payment",
                                tint = PureWhite,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cancel", color = PureWhite, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                if (isLoading && loadProgress < 100) {
                    LinearProgressIndicator(
                        progress = { (loadProgress / 100f).coerceIn(0.05f, 1f) },
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                    )
                }

                // In-App Payment Gateway WebView
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(PureWhite)
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.javaScriptCanOpenWindowsAutomatically = true
                                settings.setSupportMultipleWindows(false)
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        loadProgress = newProgress
                                        isLoading = newProgress < 100
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        super.onPageStarted(view, url, favicon)
                                        isLoading = true
                                        inspectUrlForReturn(url)
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        isLoading = false
                                    }

                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        val targetUrl = request?.url?.toString()
                                        return inspectUrlForReturn(targetUrl)
                                    }
                                }

                                webViewRef = this
                                loadUrl(checkoutUrl)
                            }
                        }
                    )
                }

                HorizontalDivider()

                // Persistent Bottom Escape & Verification Controls
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onCancelAndClose,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .testTag("checkout_bottom_cancel_button")
                        ) {
                            Text("Cancel & Back", fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = {
                                if (!handledReturn) {
                                    handledReturn = true
                                    onPaymentCompleted(reference)
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            modifier = Modifier
                                .weight(1.2f)
                                .height(46.dp)
                                .testTag("checkout_verify_now_button")
                        ) {
                            Icon(Icons.Filled.Verified, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("I've Paid — Verify", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
