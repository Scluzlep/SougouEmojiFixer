package com.king250.sougou.ui.page

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.king250.sougou.patch.EmojiCatalog
import com.king250.sougou.patch.PatchConfiguration
import com.king250.sougou.patch.PatchUiState
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun GroupOrderPage(
    configuration: PatchConfiguration,
    state: PatchUiState,
    onMoveGroup: (Int, Int) -> Unit,
    onReset: () -> Unit,
    onApply: (List<Int>) -> Unit,
    onOrderEditing: (Boolean) -> Unit,
    onRefreshOrder: () -> Unit,
) {
    // Local snapshot state drives the list synchronously during the drag, so
    // reordering follows the finger without waiting on the ViewModel round-trip.
    // Prefer the live order reported by the Hook (from the real package
    // directories) and fall back to the stored configuration.
    val orderState = remember {
        mutableStateOf(state.currentOrder ?: configuration.groupOrder)
    }
    val localOrder by orderState
    val groups = localOrder.map { id ->
        EmojiCatalog.orderGroups.first { it.packageId == id }
    }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val gapPx = with(density) { ITEM_GAP_DP.dp.toPx() }

    fun strideOf(itemHeight: Int): Float = itemHeight + gapPx

    // FLIP machinery: `offsets[id]` is the item's current visual translation
    // in the list content space. On every reorder every affected item gets an
    // inverted offset synchronously (so there is never a flash at the new
    // slot) and is then animated back to zero together with its siblings.
    val offsets = remember { mutableStateMapOf<Int, Float>() }
    val flipJobs = remember { mutableStateMapOf<Int, Job>() }

    var draggedGroupId by remember { mutableStateOf<Int?>(null) }
    var dragItemContent by remember { mutableFloatStateOf(0f) }
    var dragStartFingerY by remember { mutableFloatStateOf(-1f) }
    var fingerDelta by remember { mutableFloatStateOf(0f) }
    var itemHeightPx by remember { mutableIntStateOf(0) }
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    var autoScrollJob by remember { mutableStateOf<Job?>(null) }
    var autoScrollDirection by remember { mutableIntStateOf(0) }

    fun flipItem(id: Int, fromOffset: Float) {
        if (abs(fromOffset) < 0.5f) return
        offsets[id] = fromOffset
        flipJobs[id]?.cancel()
        flipJobs[id] = scope.launch {
            animate(
                initialValue = fromOffset,
                targetValue = 0f,
                animationSpec = FLIP_SPRING,
            ) { value, _ ->
                offsets[id] = value
            }
            flipJobs.remove(id)
        }
    }

    fun reorder(from: Int, to: Int) {
        val oldOrder = orderState.value
        if (from !in oldOrder.indices || to !in oldOrder.indices || from == to) return
        val newOrder = oldOrder.toMutableList()
        val moved = newOrder.removeAt(from)
        newOrder.add(to, moved)

        val stride = strideOf(itemHeightPx)
        if (stride > 0f) {
            oldOrder.forEach { id ->
                if (id == moved && draggedGroupId == id) return@forEach
                val oldIndex = oldOrder.indexOf(id)
                val newIndex = newOrder.indexOf(id)
                if (newIndex != oldIndex) {
                    flipItem(
                        id,
                        (oldIndex - newIndex) * stride + (offsets[id] ?: 0f),
                    )
                }
            }
        }

        orderState.value = newOrder
        onMoveGroup(from, to)
    }

    fun stopAutoScroll() {
        autoScrollJob?.cancel()
        autoScrollJob = null
        autoScrollDirection = 0
    }

    // This must run both for pointer movement and for auto-scroll frames. Once
    // the finger is parked above/below the viewport there are no more drag
    // events, but the scroll position keeps changing and the dragged item must
    // keep crossing slots as they pass underneath it.
    fun reorderForDraggedPosition() {
        val id = draggedGroupId ?: return
        val stride = strideOf(itemHeightPx)
        if (stride <= 0f) return

        val currentIndex = orderState.value.indexOf(id)
        if (currentIndex < 0) return
        val targetIndex = (dragItemContent / stride)
            .roundToInt()
            .coerceIn(0, orderState.value.lastIndex)
        if (targetIndex != currentIndex) {
            reorder(currentIndex, targetIndex)
        }
    }

    fun startAutoScroll(direction: Int, intensity: Float) {
        if (autoScrollJob?.isActive == true && autoScrollDirection == direction) {
            return
        }
        autoScrollJob?.cancel()
        autoScrollDirection = direction
        val speed = AUTO_SCROLL_BASE_SPEED * intensity.coerceAtLeast(AUTO_SCROLL_MIN_INTENSITY)
        autoScrollJob = scope.launch {
            while (isActive) {
                val before = scrollState.value
                scrollState.scrollBy(direction * speed)
                val applied = (scrollState.value - before).toFloat()
                dragItemContent += applied
                reorderForDraggedPosition()
                if (applied == 0f) break
                delay(AUTO_SCROLL_FRAME_MILLIS.milliseconds)
            }
        }
    }

    fun updateAutoScroll() {
        if (dragStartFingerY < 0f || viewportHeightPx <= 0) return
        val fingerY = dragStartFingerY + fingerDelta
        val zone = with(density) { AUTO_SCROLL_ZONE_DP.dp.toPx() }
        val topIntensity = ((zone - fingerY) / zone).coerceIn(0f, 1f)
        val bottomIntensity =
            ((fingerY - (viewportHeightPx - zone)) / zone).coerceIn(0f, 1f)
        when {
            topIntensity > 0f -> startAutoScroll(direction = -1, intensity = topIntensity)
            bottomIntensity > 0f -> startAutoScroll(direction = 1, intensity = bottomIntensity)
            else -> stopAutoScroll()
        }
    }

    fun settleDrop() {
        val id = draggedGroupId
        val content = dragItemContent
        draggedGroupId = null
        stopAutoScroll()
        if (id != null) {
            val index = orderState.value.indexOf(id)
            val stride = strideOf(itemHeightPx)
            if (index >= 0 && stride > 0f) {
                flipItem(id, content - index * stride)
            }
        }
    }

    LaunchedEffect(Unit) {
        onRefreshOrder()
    }

    LaunchedEffect(configuration.groupOrder) {
        if (draggedGroupId == null && orderState.value != configuration.groupOrder) {
            orderState.value = configuration.groupOrder
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            text = "列表越靠上，在输入法表情栏中越靠前。长按条目上下拖动，也可使用箭头微调。",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState)
                .onSizeChanged { size ->
                    if (size.height > 0) viewportHeightPx = size.height
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            groups.forEach { group ->
                key(group.packageId) {
                    val isDragging = draggedGroupId == group.packageId
                    val scale by animateFloatAsState(
                        targetValue = if (isDragging) 1.02f else 1f,
                        label = "drag scale",
                    )
                    val elevation by animateDpAsState(
                        targetValue = if (isDragging) 10.dp else 1.dp,
                        label = "drag elevation",
                    )
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (isDragging) {
                                    dragItemContent - orderState.value
                                        .indexOf(group.packageId) * strideOf(itemHeightPx)
                                } else {
                                    offsets[group.packageId] ?: 0f
                                }
                                scaleX = scale
                                scaleY = scale
                            }
                            .onSizeChanged { size ->
                                if (size.height > 0) itemHeightPx = size.height
                            }
                            .pointerInput(group.packageId) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { startOffset ->
                                        draggedGroupId = group.packageId
                                        onOrderEditing(true)
                                        fingerDelta = 0f
                                        val stride = strideOf(itemHeightPx)
                                        val startIndex = orderState.value.indexOf(group.packageId)
                                        dragItemContent = if (stride > 0f && startIndex >= 0) {
                                            startIndex * stride
                                        } else {
                                            0f
                                        }
                                        dragStartFingerY = if (stride > 0f && startIndex >= 0) {
                                            (startIndex * stride - scrollState.value) +
                                                startOffset.y
                                        } else {
                                            -1f
                                        }
                                    },
                                    onDragEnd = {
                                        onOrderEditing(false)
                                        settleDrop()
                                    },
                                    onDragCancel = {
                                        onOrderEditing(false)
                                        settleDrop()
                                    },
                                ) { change, amount ->
                                    change.consume()
                                    dragItemContent += amount.y
                                    fingerDelta += amount.y

                                    reorderForDraggedPosition()

                                    updateAutoScroll()
                                }
                            },
                        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("☰", style = MaterialTheme.typography.titleLarge)
                            Column(
                                modifier = Modifier
                                    .padding(start = 12.dp)
                                    .weight(1f),
                            ) {
                                Text(group.chineseName, fontWeight = FontWeight.Medium)
                                Text(
                                    text = buildList {
                                        add("ID ${group.packageId}")
                                        state.groupEntryCounts[group.packageId]
                                            ?.takeIf { it >= 0 }
                                            ?.let { add("$it 个表情") }
                                    }.joinToString("  ·  "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = {
                                    reorder(
                                        orderState.value.indexOf(group.packageId),
                                        orderState.value.indexOf(group.packageId) - 1,
                                    )
                                },
                                enabled = orderState.value.indexOf(group.packageId) > 0,
                            ) {
                                Text("↑")
                            }
                            TextButton(
                                onClick = {
                                    reorder(
                                        orderState.value.indexOf(group.packageId),
                                        orderState.value.indexOf(group.packageId) + 1,
                                    )
                                },
                                enabled =
                                    orderState.value.indexOf(group.packageId) <
                                        orderState.value.lastIndex,
                            ) {
                                Text("↓")
                            }
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.weight(1f),
            ) {
                Text("恢复默认")
            }
            Button(
                onClick = { onApply(orderState.value) },
                enabled = state.canInstall,
                modifier = Modifier.weight(1f),
            ) {
                Text("保存配置")
            }
        }
    }
}

private val FLIP_SPRING = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)


private const val ITEM_GAP_DP = 8
private const val AUTO_SCROLL_ZONE_DP = 80
private const val AUTO_SCROLL_BASE_SPEED = 24f
private const val AUTO_SCROLL_MIN_INTENSITY = 0.25f
private const val AUTO_SCROLL_FRAME_MILLIS = 16L
