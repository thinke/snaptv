package io.github.thinke.snaptv.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.tv.material3.Text

/** True on phones, tablets and boxes with a touchscreen or touch-like pointer. */
@Composable
fun hasTouch(): Boolean {
    val pm = LocalContext.current.packageManager
    return remember { pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) || pm.hasSystemFeature(PackageManager.FEATURE_FAKETOUCH) }
}

/**
 * A button for touch and mouse only. It can't take D-pad focus, so remote navigation stays
 * exactly as it is on a TV.
 */
@Composable
fun TouchButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .focusProperties { canFocus = false }
            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White)
    }
}

/** −50 −10 +10 +50 for delay adjusters. */
@Composable
fun DelayButtons(adjust: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = modifier) {
        for (step in listOf(-50, -10, 10, 50)) {
            TouchButton(if (step > 0) "+$step" else "$step") { adjust(step) }
        }
    }
}

/** Tap or mouse click runs [onClick]; TV Material's clickables only react to the OK key. */
fun Modifier.touchClick(enabled: Boolean, onClick: () -> Unit): Modifier =
    if (!enabled) this else pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) }

// TV Material's ListItem and buttons, plus touch and mouse clicks. Same parameters as the
// originals where SnapTV uses them, so screens only import these instead.

@Composable
fun ListItem(
    selected: Boolean,
    onClick: () -> Unit,
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) = androidx.tv.material3.ListItem(
    selected = selected,
    onClick = onClick,
    headlineContent = headlineContent,
    modifier = modifier.touchClick(enabled, onClick),
    enabled = enabled,
    supportingContent = supportingContent,
    trailingContent = trailingContent,
)

@Composable
fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    androidx.tv.material3.Button(onClick = onClick, modifier = modifier.touchClick(enabled, onClick), enabled = enabled, content = content)

@Composable
fun OutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    androidx.tv.material3.OutlinedButton(onClick = onClick, modifier = modifier.touchClick(enabled, onClick), enabled = enabled, content = content)
