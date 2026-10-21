package com.tunelyst.music.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.Icon
import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.Role

/** Lightweight local motion tokens keep the app expressive without pinning a prerelease UI artifact. */
internal object TunelystMotionSpecs {
    fun <T> fastSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.76f, stiffness = 720f)
    fun <T> defaultEffects(): FiniteAnimationSpec<T> = tween(260, easing = FastOutSlowInEasing)
    fun <T> fastEffects(): FiniteAnimationSpec<T> = tween(170, easing = FastOutSlowInEasing)
}

/** Adds a quick, soft scale response while keeping the platform's normal ripple and semantics. */
@Composable
internal fun Modifier.springyClickable(
    enabled: Boolean = true,
    pressedScale: Float = 0.96f,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit
): Modifier {
    val actualInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by actualInteractionSource.collectIsPressedAsState()
    val motionEnabled = LocalTunelystMotionEnabled.current
    val largeTargets = LocalTunelystTouchTargetsLarge.current
    val scale by animateFloatAsState(
        targetValue = if (motionEnabled && pressed && enabled) pressedScale else 1f,
        animationSpec = if (motionEnabled) TunelystMotionSpecs.fastSpatial<Float>() else snap(),
        label = "springy-tap-scale"
    )
    val indication: Indication? = LocalIndication.current
    return this
        .then(if (largeTargets) Modifier.heightIn(min = 56.dp) else Modifier)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = actualInteractionSource,
            indication = indication,
            enabled = enabled,
            role = role,
            onClick = onClick
        )
}

/** Long-press menu gesture with spring feedback but no dark ripple while the user holds a row/card. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.springyCombinedClickable(
    enabled: Boolean = true,
    pressedScale: Float = 0.98f,
    role: Role? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val motionEnabled = LocalTunelystMotionEnabled.current
    val largeTargets = LocalTunelystTouchTargetsLarge.current
    val scale by animateFloatAsState(
        targetValue = if (motionEnabled && pressed && enabled) pressedScale else 1f,
        animationSpec = if (motionEnabled) TunelystMotionSpecs.fastSpatial<Float>() else snap(),
        label = "springy-long-press-scale"
    )
    return this
        .then(if (largeTargets) Modifier.heightIn(min = 56.dp) else Modifier)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = role,
            onLongClick = onLongClick,
            onClick = onClick
        )
}

/** Scale-only press response for Material controls that already provide their own click semantics. */
@Composable
internal fun Modifier.springyPress(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.92f
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val motionEnabled = LocalTunelystMotionEnabled.current
    val scale by animateFloatAsState(
        targetValue = if (motionEnabled && pressed) pressedScale else 1f,
        animationSpec = if (motionEnabled) TunelystMotionSpecs.fastSpatial<Float>() else snap(),
        label = "springy-control-scale"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** Springy equivalent of toggleable for switches and settings rows. */
@Composable
internal fun Modifier.springyToggleable(
    value: Boolean,
    enabled: Boolean = true,
    pressedScale: Float = 0.98f,
    role: Role? = null,
    onValueChange: (Boolean) -> Unit
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val motionEnabled = LocalTunelystMotionEnabled.current
    val largeTargets = LocalTunelystTouchTargetsLarge.current
    val scale by animateFloatAsState(
        targetValue = if (motionEnabled && pressed && enabled) pressedScale else 1f,
        animationSpec = if (motionEnabled) TunelystMotionSpecs.fastSpatial<Float>() else snap(),
        label = "springy-toggle-scale"
    )
    val indication: Indication? = LocalIndication.current
    return this
        .then(if (largeTargets) Modifier.heightIn(min = 56.dp) else Modifier)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .toggleable(
            value = value,
            interactionSource = interactionSource,
            indication = indication,
            enabled = enabled,
            role = role,
            onValueChange = onValueChange
        )
}

/** M3 Expressive state icon swap with a soft spring and a short fade; reduced-motion stays static. */
@Composable
internal fun ExpressiveStateIcon(
    icon: ImageVector,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    label: String = "expressive-state-icon"
) {
    val motionEnabled = LocalTunelystMotionEnabled.current
    AnimatedContent(
        targetState = icon,
        transitionSpec = {
            if (motionEnabled) {
                (scaleIn(initialScale = 0.68f, animationSpec = spring(dampingRatio = 0.46f, stiffness = 520f)) +
                    fadeIn(animationSpec = tween(110))) togetherWith
                    (scaleOut(targetScale = 0.72f, animationSpec = tween(95)) +
                        fadeOut(animationSpec = tween(75)))
            } else {
                EnterTransition.None togetherWith ExitTransition.None
            }
        },
        label = label
    ) { displayedIcon ->
        Icon(
            imageVector = displayedIcon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = modifier.size(size)
        )
    }
}

@Composable
internal fun ExpressiveToggleIcon(
    selected: Boolean,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    label: String = "expressive-toggle-icon"
) {
    val motionEnabled = LocalTunelystMotionEnabled.current
    AnimatedContent(
        targetState = selected,
        transitionSpec = {
            if (motionEnabled) {
                (scaleIn(initialScale = 0.68f, animationSpec = spring(dampingRatio = 0.46f, stiffness = 520f)) +
                    fadeIn(animationSpec = tween(110))) togetherWith
                    (scaleOut(targetScale = 0.72f, animationSpec = tween(95)) +
                        fadeOut(animationSpec = tween(75)))
            } else {
                EnterTransition.None togetherWith ExitTransition.None
            }
        },
        label = label
    ) { checked ->
        Icon(
            imageVector = if (checked) selectedIcon else unselectedIcon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = modifier.size(size)
        )
    }
}
