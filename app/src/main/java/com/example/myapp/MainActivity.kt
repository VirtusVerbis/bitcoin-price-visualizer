package com.example.myapp

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.example.myapp.data.PriceRepository
import com.example.myapp.ui.PriceDisplay
import com.example.myapp.ui.PulseDirection
import com.example.myapp.ui.theme.MyAppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import java.util.Random

// Cooldown timer for clone spawning (3 seconds)
const val CLONE_SPAWN_COOLDOWN_MS = 3000L

// Cooldown timer for Fiat USD shrink (3 seconds)
const val FIAT_SHRINK_COOLDOWN_MS = 3000L

// Max sprite counts
const val MAX_BITCOIN_SPRITES_PER_TYPE = 5  // Allows up to 5 per type (2 original + 3 clones = 10 total)
const val MAX_FIAT_USD_SPRITES = 10         // Maximum number of Fiat USD sprites that can exist
const val MAX_CAT_SPRITES = 1               // Maximum number of cat sprites that can exist

// Cat collision separation multipliers
const val CAT_COLLISION_SEPARATION_OVERLAPPING = 1.25f  // Separation multiplier when sprites are overlapping with cat
const val CAT_COLLISION_SEPARATION_TOUCHING = 1.25f     // Separation multiplier when sprites are just touching cat

// Cat sprite animation
const val CAT_ANIMATION_FRAME_DELAY_MS = 150L  // Delay between animation frames (150ms = ~6.7 fps for smooth sprite animation)

// Cat sprite speed
const val CAT_BASE_SPEED = 3f  // Base movement speed in pixels per frame
const val CAT_SPEED_MULTIPLIER = 3.0f  // Speed multiplier for cat (can be adjusted for different speeds)

// Cat diagonal movement detection
const val CAT_DIAGONAL_RATIO_MIN = 0.3f  // Minimum ratio for diagonal detection (was 0.5f)
const val CAT_DIAGONAL_RATIO_MAX = 3.0f  // Maximum ratio for diagonal detection (was 2.0f)
const val CAT_DIAGONAL_MIN_VELOCITY = 0.1f  // Minimum velocity for both X and Y to be considered diagonal

// Flag to enable/disable Binance and Coinbase logcat logs
const val ENABLE_EXCHANGE_LOGS = false

// Flag to enable/disable drag gesture logcat logs
const val ENABLE_DRAG_LOGS = false

// Shared state for sprite position and velocity
class SpriteState {
    var position: Offset = Offset.Zero
    var velocity: Offset = Offset.Zero
    var userAppliedVelocity: Offset = Offset.Zero // Velocity added by user fling
    var userInteractionStartTime: Long = 0L // When user interaction started (for 5-second timer)
}

// Sprite type enum
enum class SpriteType {
    BITCOIN_ORANGE,  // bitcoin_orange_sprite (Binance-controlled)
    BITCOIN,         // bitcoin_sprite (Coinbase-controlled)
    FIAT_USD,        // fiat_usd.png (spawns when sell volume >= 50%)
    CAT              // e_cat.png (roams around, unaffected by collisions)
}

// Sprite data structure
data class SpriteData(
    val spriteState: SpriteState,
    val spriteResourceId: Int,
    val speedMultiplier: Float,
    val isOriginal: Boolean,
    val spriteType: SpriteType,
    val lastCloneSpawnTime: Long = 0L, // Timestamp when sprite last triggered/spawned a clone
    val sizeScale: Float = 1.0f, // Current size multiplier (default 1.0 = 100%)
    val bitcoinCollisionCount: Int = 0, // Number of Bitcoin collisions (0-4, for Fiat USD)
    val lastShrinkCooldownTime: Long = 0L // Timestamp for shrink cooldown (for Fiat USD)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.ui.graphics.Color.Black
                ) {
                    PriceDisplayScreen()
                }
            }
        }
    }
}

@Composable
fun PriceDisplayScreen() {
    val repository = remember { PriceRepository() }
    
    // Binance state
    var binancePrice by remember { mutableStateOf<Double?>(null) }
    var binancePreviousPrice by remember { mutableStateOf<Double?>(null) }
    var binanceIsConnected by remember { mutableStateOf(false) }
    var binanceBuyVolume by remember { mutableStateOf<Double?>(null) }
    var binanceSellVolume by remember { mutableStateOf<Double?>(null) }
    
    // Coinbase state
    var coinbasePrice by remember { mutableStateOf<Double?>(null) }
    var coinbasePreviousPrice by remember { mutableStateOf<Double?>(null) }
    var coinbaseIsConnected by remember { mutableStateOf(false) }
    var coinbaseBuyVolume by remember { mutableStateOf<Double?>(null) }
    var coinbaseSellVolume by remember { mutableStateOf<Double?>(null) }
    
    // Volume normalization and animation
    var maxVolume by remember { mutableStateOf(1.0) }
    var binanceVolumeAnimating by remember { mutableStateOf(false) }
    var coinbaseVolumeAnimating by remember { mutableStateOf(false) }
    
    // Calculate max volume from all volumes
    // Use 0.0 for null values to ensure maxVolume is always > 0
    val calculateMaxVolume: () -> Double = {
        val volumes = listOf(
            binanceBuyVolume ?: 0.0,
            binanceSellVolume ?: 0.0,
            coinbaseBuyVolume ?: 0.0,
            coinbaseSellVolume ?: 0.0
        )
        // Ensure maxVolume is at least 1.0 to avoid division by zero
        val maxVol = maxOf(volumes.maxOrNull() ?: 1.0, 1.0)
        if (ENABLE_EXCHANGE_LOGS) {
            Log.d("MainActivity", "Max Volume: $maxVol, Volumes: BinanceBuy=${binanceBuyVolume}, BinanceSell=${binanceSellVolume}, CoinbaseBuy=${coinbaseBuyVolume}, CoinbaseSell=${coinbaseSellVolume}")
        }
        maxVol
    }
    
    // Track previous volumes to detect changes
    var prevBinanceBuy by remember { mutableStateOf<Double?>(null) }
    var prevBinanceSell by remember { mutableStateOf<Double?>(null) }
    var prevCoinbaseBuy by remember { mutableStateOf<Double?>(null) }
    var prevCoinbaseSell by remember { mutableStateOf<Double?>(null) }
    
    // Watch for Binance volume changes and trigger animation
    LaunchedEffect(binanceBuyVolume, binanceSellVolume) {
        // Check if volumes actually changed (not just first load)
        val buyChanged = binanceBuyVolume != prevBinanceBuy
        val sellChanged = binanceSellVolume != prevBinanceSell
        
        if (buyChanged || sellChanged) {
            // Update previous values
            prevBinanceBuy = binanceBuyVolume
            prevBinanceSell = binanceSellVolume
            
            // Trigger animation only if volumes exist and changed
            if (binanceBuyVolume != null || binanceSellVolume != null) {
                binanceVolumeAnimating = true
                
                // Reset animation flag after animation completes
                delay(500)
                binanceVolumeAnimating = false
            }
        }
    }
    
    // Watch for Coinbase volume changes and trigger animation
    LaunchedEffect(coinbaseBuyVolume, coinbaseSellVolume) {
        // Check if volumes actually changed (not just first load)
        val buyChanged = coinbaseBuyVolume != prevCoinbaseBuy
        val sellChanged = coinbaseSellVolume != prevCoinbaseSell
        
        if (buyChanged || sellChanged) {
            // Update previous values
            prevCoinbaseBuy = coinbaseBuyVolume
            prevCoinbaseSell = coinbaseSellVolume
            
            // Trigger animation only if volumes exist and changed
            if (coinbaseBuyVolume != null || coinbaseSellVolume != null) {
                coinbaseVolumeAnimating = true
                
                // Reset animation flag after animation completes
                delay(500)
                coinbaseVolumeAnimating = false
            }
        }
    }
    
    // Update max volume when any volume changes
    LaunchedEffect(binanceBuyVolume, binanceSellVolume, coinbaseBuyVolume, coinbaseSellVolume) {
        maxVolume = calculateMaxVolume()
    }
    
    // Calculate Binance speed multiplier (for original sprite)
    val binanceSpeedMultiplier = remember(binanceBuyVolume, binanceSellVolume) {
        val totalVolume = (binanceBuyVolume ?: 0.0) + (binanceSellVolume ?: 0.0)
        val deltaVolume = (binanceBuyVolume ?: 0.0) - (binanceSellVolume ?: 0.0)
        
        // Normalize delta (ratio-based)
        val normalizedDelta = if (totalVolume > 0) deltaVolume / totalVolume else 0.0
        
        // Dynamic scaling factor based on volume magnitude
        val scaleFactor = when {
            totalVolume > 1000 -> 0.5f
            totalVolume > 100 -> 1.0f
            else -> 2.0f
        }
        
        // Calculate speed multiplier (buy increases, sell decreases)
        // Ensure minimum multiplier is 1.0 (cannot go below current default speed)
        val multiplier = (1.0 + (normalizedDelta * scaleFactor)).coerceAtLeast(1.0).toFloat()
        
        if (ENABLE_EXCHANGE_LOGS) {
            Log.d("MainActivity", "Binance Speed Multiplier: $multiplier (Buy: ${binanceBuyVolume}, Sell: ${binanceSellVolume}, Delta: $deltaVolume, Normalized: $normalizedDelta, Scale: $scaleFactor)")
        }
        
        multiplier
    }
    
    // Calculate Coinbase speed multiplier (for Bitcoin sprite)
    val coinbaseSpeedMultiplier = remember(coinbaseBuyVolume, coinbaseSellVolume) {
        val totalVolume = (coinbaseBuyVolume ?: 0.0) + (coinbaseSellVolume ?: 0.0)
        val deltaVolume = (coinbaseBuyVolume ?: 0.0) - (coinbaseSellVolume ?: 0.0)
        
        // Normalize delta (ratio-based)
        val normalizedDelta = if (totalVolume > 0) deltaVolume / totalVolume else 0.0
        
        // Dynamic scaling factor based on volume magnitude
        val scaleFactor = when {
            totalVolume > 1000 -> 0.5f
            totalVolume > 100 -> 1.0f
            else -> 2.0f
        }
        
        // Calculate speed multiplier (buy increases, sell decreases)
        // Ensure minimum multiplier is 1.0 (cannot go below current default speed)
        val multiplier = (1.0 + (normalizedDelta * scaleFactor)).coerceAtLeast(1.0).toFloat()
        
        if (ENABLE_EXCHANGE_LOGS) {
            Log.d("MainActivity", "Coinbase Speed Multiplier: $multiplier (Buy: ${coinbaseBuyVolume}, Sell: ${coinbaseSellVolume}, Delta: $deltaVolume, Normalized: $normalizedDelta, Scale: $scaleFactor)")
        }
        
        multiplier
    }
    
    // Poll APIs every 5 seconds
    LaunchedEffect(Unit) {
        while (true) {
            // Fetch Binance price
            repository.getBinancePrice().fold(
                onSuccess = { price ->
                    binancePreviousPrice = binancePrice
                    binancePrice = price
                    binanceIsConnected = true
                },
                onFailure = {
                    binanceIsConnected = false
                }
            )
            
            // Fetch Coinbase price
            repository.getCoinbasePrice().fold(
                onSuccess = { price ->
                    coinbasePreviousPrice = coinbasePrice
                    coinbasePrice = price
                    coinbaseIsConnected = true
                },
                onFailure = {
                    coinbaseIsConnected = false
                }
            )
            
            // Fetch Binance volumes
            repository.getBinanceVolumes().fold(
                onSuccess = { (buy, sell) ->
                    val oldBuy = binanceBuyVolume
                    val oldSell = binanceSellVolume
                    binanceBuyVolume = buy
                    binanceSellVolume = sell
                    if (ENABLE_EXCHANGE_LOGS) {
                        Log.d("MainActivity", "Binance volumes updated - Buy: $buy (was $oldBuy), Sell: $sell (was $oldSell)")
                    }
                },
                onFailure = { e ->
                    if (ENABLE_EXCHANGE_LOGS) {
                        Log.e("MainActivity", "Failed to fetch Binance volumes: ${e.message}", e)
                    }
                    binanceBuyVolume = null
                    binanceSellVolume = null
                }
            )
            
            // Fetch Coinbase volumes
            repository.getCoinbaseVolumes().fold(
                onSuccess = { (buy, sell) ->
                    val oldBuy = coinbaseBuyVolume
                    val oldSell = coinbaseSellVolume
                    coinbaseBuyVolume = buy
                    coinbaseSellVolume = sell
                    if (ENABLE_EXCHANGE_LOGS) {
                        Log.d("MainActivity", "Coinbase volumes updated - Buy: $buy (was $oldBuy), Sell: $sell (was $oldSell)")
                    }
                },
                onFailure = { e ->
                    if (ENABLE_EXCHANGE_LOGS) {
                        Log.e("MainActivity", "Failed to fetch Coinbase volumes: ${e.message}", e)
                    }
                    coinbaseBuyVolume = null
                    coinbaseSellVolume = null
                }
            )
            
            delay(5000) // 5 seconds
        }
    }
    
    // Manage all sprites in a list
    val sprites = remember { mutableStateListOf<SpriteData>() }
    
    // Global cooldown: disables all spawning for 3 seconds after any clone is spawned
    var lastGlobalCloneSpawnTime by remember { mutableStateOf(0L) }
    
    // Global dragging state: tracks which sprite is currently being dragged (only one at a time)
    var currentlyDraggedSprite by remember { mutableStateOf<SpriteState?>(null) }
    
    // Track Fiat USD sprites to remove (after 4th Bitcoin collision)
    val spritesToRemove = remember { mutableSetOf<SpriteData>() }
    
    // Track screen size for spawning and centering sprites (cat, Fiat USD)
    var screenSizeForSpawn by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    
    // Initialize original sprites if list is empty
    LaunchedEffect(Unit) {
        if (sprites.isEmpty()) {
            val sprite1State = SpriteState()
            val sprite2State = SpriteState()
            
            sprites.add(
                SpriteData(
                    spriteState = sprite1State,
                    spriteResourceId = R.drawable.bitcoin_orange_sprite,
                    speedMultiplier = binanceSpeedMultiplier,
                    isOriginal = true,
                    spriteType = SpriteType.BITCOIN_ORANGE
                )
            )
            sprites.add(
                SpriteData(
                    spriteState = sprite2State,
                    spriteResourceId = R.drawable.bitcoin_sprite,
                    speedMultiplier = coinbaseSpeedMultiplier,
                    isOriginal = true,
                    spriteType = SpriteType.BITCOIN
                )
            )
        }
    }
    
    // Initialize cat sprite at app start (center of screen)
    LaunchedEffect(screenSizeForSpawn.width, screenSizeForSpawn.height) {
        if (screenSizeForSpawn.width > 0f && screenSizeForSpawn.height > 0f) {
            // Check if cat sprite already exists
            val catCount = sprites.count { it.spriteType == SpriteType.CAT }
            
            if (catCount < MAX_CAT_SPRITES) {
                val catState = SpriteState()
                val baseSpeed = CAT_BASE_SPEED
                val catSpeedMultiplier = CAT_SPEED_MULTIPLIER
                val effectiveSpeed = baseSpeed * catSpeedMultiplier
                
                // Position at center of screen
                val spriteSizeDp = 64f
                val density = android.content.res.Resources.getSystem().displayMetrics.density
                val spriteSizePx = spriteSizeDp * density
                val centerX = (screenSizeForSpawn.width - spriteSizePx) / 2f
                val centerY = (screenSizeForSpawn.height - spriteSizePx) / 2f
                
                catState.position = Offset(centerX, centerY)
                
                // Initialize with random direction velocity using seeded random for true randomization
                val random = Random(System.currentTimeMillis())
                val randomAngle = random.nextFloat() * 2f * kotlin.math.PI.toFloat()
                val initialVel = Offset(
                    effectiveSpeed * kotlin.math.cos(randomAngle),
                    effectiveSpeed * kotlin.math.sin(randomAngle)
                )
                catState.velocity = initialVel
                
                // Create cat sprite
                val catSprite = SpriteData(
                    spriteState = catState,
                    spriteResourceId = R.drawable.e_cat_down_1, // Default frame (animation handled in composable)
                    speedMultiplier = catSpeedMultiplier,
                    isOriginal = true,
                    spriteType = SpriteType.CAT,
                    lastCloneSpawnTime = 0L,
                    sizeScale = 1.0f,
                    bitcoinCollisionCount = 0,
                    lastShrinkCooldownTime = 0L
                )
                
                sprites.add(catSprite)
            }
        }
    }
    
    // Update speed multipliers for existing sprites
    LaunchedEffect(binanceSpeedMultiplier, coinbaseSpeedMultiplier) {
        sprites.forEach { sprite ->
            when (sprite.spriteType) {
                SpriteType.BITCOIN_ORANGE -> {
                    val index = sprites.indexOf(sprite)
                    if (index >= 0) {
                        sprites[index] = sprite.copy(speedMultiplier = binanceSpeedMultiplier)
                    }
                }
                SpriteType.BITCOIN -> {
                    val index = sprites.indexOf(sprite)
                    if (index >= 0) {
                        sprites[index] = sprite.copy(speedMultiplier = coinbaseSpeedMultiplier)
                    }
                }
                SpriteType.FIAT_USD -> {
                    // Fiat USD uses fixed speed multiplier of 1.0f
                    val index = sprites.indexOf(sprite)
                    if (index >= 0) {
                        sprites[index] = sprite.copy(speedMultiplier = 1.0f)
                    }
                }
                SpriteType.CAT -> {
                    // Cat uses fixed speed multiplier
                    val index = sprites.indexOf(sprite)
                    if (index >= 0) {
                        sprites[index] = sprite.copy(speedMultiplier = CAT_SPEED_MULTIPLIER)
                    }
                }
            }
        }
    }
    
    // Fiat USD spawning logic: spawn when sell volume >= 50% of total volume
    LaunchedEffect(binanceSellVolume, coinbaseSellVolume, binanceBuyVolume, coinbaseBuyVolume) {
        delay(5000) // Wait for initial volume data
        
        while (true) {
            // Wait for screen size to be available
            if (screenSizeForSpawn.width > 0f && screenSizeForSpawn.height > 0f) {
                // Calculate total volume
                val totalVolume = (binanceBuyVolume ?: 0.0) + (binanceSellVolume ?: 0.0) + 
                                  (coinbaseBuyVolume ?: 0.0) + (coinbaseSellVolume ?: 0.0)
                
                // Check if sell volume >= 50% of total
                val binanceSellRatio = if (totalVolume > 0) (binanceSellVolume ?: 0.0) / totalVolume else 0.0
                val coinbaseSellRatio = if (totalVolume > 0) (coinbaseSellVolume ?: 0.0) / totalVolume else 0.0
                val shouldSpawn = binanceSellRatio >= 0.5 || coinbaseSellRatio >= 0.5
                
                // Count existing Fiat USD sprites
                val fiatUsdCount = sprites.count { it.spriteType == SpriteType.FIAT_USD }
                
                // Spawn if condition met and below max count
                if (shouldSpawn && fiatUsdCount < MAX_FIAT_USD_SPRITES) {
                    // Calculate sprite size in pixels (assuming default density)
                    val spriteSizeDp = 64f
                    val density = android.content.res.Resources.getSystem().displayMetrics.density
                    val spriteSizePx = spriteSizeDp * density
                    val spawnLocation = findEmptySpawnLocation(screenSizeForSpawn, spriteSizePx, sprites.toList())
                    
                    if (spawnLocation != null) {
                        val fiatState = SpriteState()
                        val baseSpeed = 3f
                        val effectiveSpeed = baseSpeed * 1.0f // Fixed speed for Fiat USD
                        
                        // Set spawn position
                        fiatState.position = spawnLocation
                        
                        // Initialize with random direction velocity
                        val randomAngle = kotlin.random.Random.nextFloat() * 2f * kotlin.math.PI.toFloat()
                        val initialVel = Offset(
                            effectiveSpeed * kotlin.math.cos(randomAngle),
                            effectiveSpeed * kotlin.math.sin(randomAngle)
                        )
                        fiatState.velocity = initialVel
                        
                        // Create Fiat USD sprite
                        val fiatSprite = SpriteData(
                            spriteState = fiatState,
                            spriteResourceId = R.drawable.fiat_usd,
                            speedMultiplier = 1.0f,
                            isOriginal = false,
                            spriteType = SpriteType.FIAT_USD,
                            lastCloneSpawnTime = 0L,
                            sizeScale = 1.0f,
                            bitcoinCollisionCount = 0,
                            lastShrinkCooldownTime = 0L
                        )
                        
                        sprites.add(fiatSprite)
                    }
                }
            }
            
            delay(5000) // Check every 5 seconds (same as API polling)
        }
    }
    
    // Function to create a clone
    val onCloneRequest: (SpriteData, SpriteData, Offset) -> Unit = { sprite1, sprite2, collisionPoint ->
        // Determine clone type based on colliding sprite types
        val cloneType = when {
            sprite1.spriteType == sprite2.spriteType -> sprite1.spriteType
            else -> if (kotlin.random.Random.nextBoolean()) sprite1.spriteType else sprite2.spriteType
        }
        
        // Count sprites of this type
        val count = sprites.count { it.spriteType == cloneType }
        if (count < MAX_BITCOIN_SPRITES_PER_TYPE) { // Allows up to MAX_BITCOIN_SPRITES_PER_TYPE per type
            val cloneState = SpriteState()
            val baseSpeed = if (cloneType == SpriteType.CAT) CAT_BASE_SPEED else 3f
            
            // Determine speed multiplier based on clone type
            val cloneSpeedMultiplier = when (cloneType) {
                SpriteType.BITCOIN_ORANGE -> binanceSpeedMultiplier
                SpriteType.BITCOIN -> coinbaseSpeedMultiplier
                SpriteType.FIAT_USD -> 1.0f // Fixed speed for Fiat USD
                SpriteType.CAT -> CAT_SPEED_MULTIPLIER
            }
            val effectiveSpeed = baseSpeed * cloneSpeedMultiplier
            
            // Calculate random direction from collision point
            val randomAngle = kotlin.random.Random.nextFloat() * 2f * kotlin.math.PI.toFloat()
            val cloneVelocity = Offset(
                effectiveSpeed * kotlin.math.cos(randomAngle),
                effectiveSpeed * kotlin.math.sin(randomAngle)
            )
            
            // Set clone position to collision point (already adjusted to top-left corner)
            cloneState.position = collisionPoint
            cloneState.velocity = cloneVelocity
            
            // Determine sprite resource ID based on clone type
            val cloneResourceId = when (cloneType) {
                SpriteType.BITCOIN_ORANGE -> R.drawable.bitcoin_orange_sprite
                SpriteType.BITCOIN -> R.drawable.bitcoin_sprite
                SpriteType.FIAT_USD -> R.drawable.fiat_usd
                SpriteType.CAT -> R.drawable.e_cat_down_1 // Default frame (animation handled in composable)
            }
            
            val currentTime = System.currentTimeMillis()
            
            // Create clone with cooldown timestamp set to current time
            val clone = SpriteData(
                spriteState = cloneState,
                spriteResourceId = cloneResourceId,
                speedMultiplier = cloneSpeedMultiplier,
                isOriginal = false,
                spriteType = cloneType,
                lastCloneSpawnTime = currentTime
            )
            
            // Add clone to list
            sprites.add(clone)
            
            // Set global cooldown: disable all spawning for 3 seconds
            lastGlobalCloneSpawnTime = currentTime
            
            // Update parent sprites' cooldown timestamps in the list
            val sprite1Index = sprites.indexOfFirst { it.spriteState == sprite1.spriteState }
            val sprite2Index = sprites.indexOfFirst { it.spriteState == sprite2.spriteState }
            
            if (sprite1Index >= 0) {
                sprites[sprite1Index] = sprites[sprite1Index].copy(lastCloneSpawnTime = currentTime)
            }
            if (sprite2Index >= 0) {
                sprites[sprite2Index] = sprites[sprite2Index].copy(lastCloneSpawnTime = currentTime)
            }
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                screenSizeForSpawn = coordinates.size.toSize()
            }
    ) {
        // Remove sprites marked for deletion (Fiat USD after 4 collisions)
        LaunchedEffect(spritesToRemove.size) {
            if (spritesToRemove.isNotEmpty()) {
                sprites.removeAll(spritesToRemove)
                spritesToRemove.clear()
            }
        }
        
        // Render all sprites
        sprites.forEach { spriteData ->
            BouncingSprite(
                spriteData = spriteData,
                allSprites = sprites.toList(),
                onCloneRequest = onCloneRequest,
                getLastGlobalCloneSpawnTime = { lastGlobalCloneSpawnTime },
                getFreshSpriteData = { spriteState ->
                    sprites.find { it.spriteState == spriteState }
                },
                currentlyDraggedSprite = currentlyDraggedSprite,
                setCurrentlyDraggedSprite = { spriteState -> currentlyDraggedSprite = spriteState },
                markSpriteForRemoval = { spriteToRemove -> spritesToRemove.add(spriteToRemove) },
                updateSpriteData = { oldSprite, newSprite ->
                    val index = sprites.indexOf(oldSprite)
                    if (index >= 0) {
                        sprites[index] = newSprite
                    }
                },
                initialOffset = if (spriteData.isOriginal) {
                    when (spriteData.spriteType) {
                        SpriteType.BITCOIN_ORANGE -> Offset(0f, 0f)
                        SpriteType.BITCOIN -> Offset(1f, 0f)
                        SpriteType.FIAT_USD -> Offset(0.5f, 0.5f) // Fiat USD spawns at random location, but use center as default
                        SpriteType.CAT -> Offset(0.5f, 0.5f) // Cat spawns at center of screen
                    }
                } else {
                    Offset(0.5f, 0.5f) // Clones start at center
                },
                        contentDescription = if (spriteData.isOriginal) {
                    when (spriteData.spriteType) {
                        SpriteType.BITCOIN_ORANGE -> "Bouncing sprite"
                        SpriteType.BITCOIN -> "Bitcoin bouncing sprite"
                        SpriteType.FIAT_USD -> "Fiat USD sprite"
                        SpriteType.CAT -> "Cat sprite"
                    }
                } else {
                    when (spriteData.spriteType) {
                        SpriteType.FIAT_USD -> "Fiat USD sprite"
                        SpriteType.CAT -> "Cat sprite"
                        else -> "Cloned sprite"
                    }
                }
            )
        }
        
        // Price displays as overlays
        // Top left - Binance
        PriceDisplay(
            label = "Binance BTC-USDT",
            price = binancePrice,
            isConnected = binanceIsConnected,
            previousPrice = binancePreviousPrice,
            buyVolume = binanceBuyVolume,
            sellVolume = binanceSellVolume,
            maxVolume = maxVolume,
            volumeAnimating = binanceVolumeAnimating,
            modifier = Modifier.align(Alignment.TopStart),
            pulseDirection = PulseDirection.LEFT_TO_RIGHT
        )
        
        // Top right - Coinbase
        PriceDisplay(
            label = "Coinbase BTC-USD",
            price = coinbasePrice,
            isConnected = coinbaseIsConnected,
            previousPrice = coinbasePreviousPrice,
            buyVolume = coinbaseBuyVolume,
            sellVolume = coinbaseSellVolume,
            maxVolume = maxVolume,
            volumeAnimating = coinbaseVolumeAnimating,
            modifier = Modifier.align(Alignment.TopEnd),
            horizontalAlignment = Alignment.End,
            pulseDirection = PulseDirection.RIGHT_TO_LEFT
        )
    }
}

// Helper function to find an empty spawn location on the screen
fun findEmptySpawnLocation(
    screenSize: androidx.compose.ui.geometry.Size,
    spriteSizePx: Float,
    existingSprites: List<SpriteData>
): Offset? {
    val maxAttempts = 50
    var attempts = 0
    
    while (attempts < maxAttempts) {
        // Generate random position
        val randomX = kotlin.random.Random.nextFloat() * (screenSize.width - spriteSizePx).coerceAtLeast(0f)
        val randomY = kotlin.random.Random.nextFloat() * (screenSize.height - spriteSizePx).coerceAtLeast(0f)
        val candidatePos = Offset(randomX, randomY)
        
        // Check if position overlaps with any existing sprite
        var hasOverlap = false
        for (sprite in existingSprites) {
            val existingPos = sprite.spriteState.position
            val existingSizePx = spriteSizePx * sprite.sizeScale // Use scaled size for collision check
            if (checkCollision(candidatePos, existingPos, existingSizePx)) {
                hasOverlap = true
                break
            }
        }
        
        // If no overlap found, return this position
        if (!hasOverlap) {
            return candidatePos
        }
        
        attempts++
    }
    
    // If no empty spot found after max attempts, return null
    return null
}

// Collision detection: Check if two sprites' bounding boxes overlap
fun checkCollision(
    pos1: Offset,
    pos2: Offset,
    size: Float
): Boolean {
    val left1 = pos1.x
    val right1 = pos1.x + size
    val top1 = pos1.y
    val bottom1 = pos1.y + size
    
    val left2 = pos2.x
    val right2 = pos2.x + size
    val top2 = pos2.y
    val bottom2 = pos2.y + size
    
    return !(right1 < left2 || left1 > right2 || bottom1 < top2 || top1 > bottom2)
}

// Elastic collision physics: Calculate new velocities after collision
// Uses reflection approach (like wall bounce) instead of complex impulse calculations
fun calculateCollisionResponse(
    pos1: Offset,
    vel1: Offset,
    pos2: Offset,
    vel2: Offset,
    size: Float
): Pair<Offset, Offset> {
    // Calculate collision normal (direction from sprite1 center to sprite2 center)
    val center1 = Offset(pos1.x + size / 2f, pos1.y + size / 2f)
    val center2 = Offset(pos2.x + size / 2f, pos2.y + size / 2f)
    
    val dx = center2.x - center1.x
    val dy = center2.y - center1.y
    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
    
    // Handle perfect overlap (distance == 0)
    if (distance == 0f) {
        // Push sprites apart with opposite velocities
        val randomAngle = kotlin.random.Random.nextFloat() * 2f * kotlin.math.PI.toFloat()
        val pushSpeed = 3f
        val normalX = kotlin.math.cos(randomAngle)
        val normalY = kotlin.math.sin(randomAngle)
        return Pair(
            Offset(-pushSpeed * normalX, -pushSpeed * normalY),
            Offset(pushSpeed * normalX, pushSpeed * normalY)
        )
    }
    
    // Normalized collision normal (like the wall surface)
    val normalX = dx / distance
    val normalY = dy / distance
    
    // Reflect velocity across the collision normal (same as wall bounce)
    // Formula: v' = v - 2 * (v · n) * n
    // This reflects the velocity vector across the normal line
    
    // For sprite1: reflect across normal pointing from sprite1 to sprite2
    val dot1 = vel1.x * normalX + vel1.y * normalY
    val newVel1X = vel1.x - 2f * dot1 * normalX
    val newVel1Y = vel1.y - 2f * dot1 * normalY
    
    // For sprite2: reflect across normal pointing from sprite2 to sprite1 (opposite direction)
    val dot2 = vel2.x * (-normalX) + vel2.y * (-normalY)  // Opposite normal
    val newVel2X = vel2.x - 2f * dot2 * (-normalX)
    val newVel2Y = vel2.y - 2f * dot2 * (-normalY)
    
    return Pair(Offset(newVel1X, newVel1Y), Offset(newVel2X, newVel2Y))
}

@Composable
fun BouncingSprite(
    spriteData: SpriteData,
    allSprites: List<SpriteData>, // Snapshot list for safe iteration (snapped per frame)
    onCloneRequest: (SpriteData, SpriteData, Offset) -> Unit,
    getLastGlobalCloneSpawnTime: () -> Long, // Function to get current global cooldown time
    getFreshSpriteData: (SpriteState) -> SpriteData?, // Function to get fresh sprite data from list
    currentlyDraggedSprite: SpriteState?, // Global state: which sprite is currently being dragged
    setCurrentlyDraggedSprite: (SpriteState?) -> Unit, // Function to set the dragged sprite
    markSpriteForRemoval: (SpriteData) -> Unit, // Function to mark sprite for removal
    updateSpriteData: (SpriteData, SpriteData) -> Unit, // Function to update sprite data in list
    initialOffset: Offset = Offset(0f, 0f), // Offset factor: 0.0 = left, 1.0 = right, etc.
    contentDescription: String = "Bouncing sprite"
) {
    val spriteSize = 64.dp
    val spriteSizePx = with(LocalDensity.current) { spriteSize.toPx() }
    // Calculate effective sprite size based on sizeScale
    val effectiveSpriteSizePx = spriteSizePx * spriteData.sizeScale
    
    var screenSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    val baseSpeed = 3f // Default speed in pixels per frame
    val positionX = remember { Animatable(0f) }
    val positionY = remember { Animatable(0f) }
    
    // Track current velocity as a mutable state
    var currentVelocity by remember { mutableStateOf<Offset?>(null) }
    var initialized by remember { mutableStateOf(false) }
    var previousVelocity by remember { mutableStateOf<Offset?>(null) }
    
    // Track processed collisions per frame to prevent duplicate clone creation
    val processedCollisions = remember { mutableSetOf<Pair<SpriteData, SpriteData>>() }
    
    // Track drag state for velocity calculation
    var lastDragPosition by remember { mutableStateOf<Offset?>(null) }
    var lastDragTime by remember { mutableStateOf(0L) }
    var recentDragMovements by remember { mutableStateOf<List<Pair<Offset, Long>>>(emptyList()) }
    var dragStartPosition by remember { mutableStateOf<Offset?>(null) }  // Screen position where drag started
    var cumulativeDragAmount by remember { mutableStateOf(Offset.Zero) }  // Cumulative drag movement
    val spriteState = remember(spriteData) { spriteData.spriteState }  // Remember spriteState to maintain stable reference
    var localDraggedSpriteState by remember { mutableStateOf<SpriteState?>(null) }  // Local tracking of dragged sprite for this composable
    val coroutineScope = rememberCoroutineScope()
    
    // Sync positionX and positionY with spriteState.position when being dragged
    LaunchedEffect(spriteState.position, currentlyDraggedSprite == spriteState) {
        if (currentlyDraggedSprite == spriteState) {
            // Only sync when actively dragging to avoid conflicts with animation
            // Validate position values before syncing to prevent NaN
            val posX = spriteState.position.x
            val posY = spriteState.position.y
            val validX = if (posX.isNaN() || posX.isInfinite()) positionX.value else posX
            val validY = if (posY.isNaN() || posY.isInfinite()) positionY.value else posY
            positionX.snapTo(validX)
            positionY.snapTo(validY)
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                screenSize = coordinates.size.toSize()
            }
    ) {
        // Bouncing animation logic
        // Include currentlyDraggedSprite in the key so the physics loop restarts
        // when drag starts/ends, preventing stale drag state from pausing movement.
        LaunchedEffect(screenSize, spriteData.speedMultiplier, currentlyDraggedSprite) {
            if (screenSize.width == 0f || screenSize.height == 0f) return@LaunchedEffect
            
            val spriteState = spriteData.spriteState
            
            // Calculate effective speed based on multiplier
            val effectiveSpeed = baseSpeed * spriteData.speedMultiplier
            
            // Initialize position based on initialOffset factor or use already set position (for clones)
            if (!initialized && positionX.value == 0f && positionY.value == 0f) {
                // Check if spriteState.position is already set (for clones spawned at collision point)
                val alreadySetPosition = spriteState.position
                if (alreadySetPosition != Offset.Zero) {
                    // Use the position that was set when clone was created at collision point
                    positionX.snapTo(alreadySetPosition.x)
                    positionY.snapTo(alreadySetPosition.y)
                } else {
                    // Calculate position from initialOffset (for original sprites)
                    val startX = (screenSize.width - spriteSizePx) * (0.25f + initialOffset.x * 0.5f)
                    val startY = (screenSize.height - spriteSizePx) * (0.25f + initialOffset.y * 0.5f)
                    
                    positionX.snapTo(startX)
                    positionY.snapTo(startY)
                    spriteState.position = Offset(startX, startY)
                }
                
                // Initialize with random direction velocity
                val randomAngle = kotlin.random.Random.nextFloat() * 2f * kotlin.math.PI.toFloat()
                val initialVel = Offset(
                    effectiveSpeed * kotlin.math.cos(randomAngle),
                    effectiveSpeed * kotlin.math.sin(randomAngle)
                )
                currentVelocity = initialVel
                spriteState.velocity = initialVel
                previousVelocity = initialVel
                initialized = true
            } else {
                // When LaunchedEffect re-runs (e.g., speedMultiplier changes), preserve existing velocity
                // Don't normalize velocity here - let the physics loop handle it from spriteState.velocity
                // This ensures user-applied fling velocities are never overwritten
                val existingVelocity = spriteState.velocity
                val existingMagnitude = kotlin.math.sqrt(existingVelocity.x * existingVelocity.x + existingVelocity.y * existingVelocity.y)
                
                // Only update velocity if it's zero or very small (indicating it needs initialization)
                // Otherwise, preserve the existing velocity (which may be a user fling)
                if (existingMagnitude < 0.1f) {
                    // Velocity is essentially zero - initialize with random direction
                    val randomAngle = kotlin.random.Random.nextFloat() * 2f * kotlin.math.PI.toFloat()
                    val newVel = Offset(
                        effectiveSpeed * kotlin.math.cos(randomAngle),
                        effectiveSpeed * kotlin.math.sin(randomAngle)
                    )
                    currentVelocity = newVel
                    spriteState.velocity = newVel
                } else {
                    // Preserve existing velocity (could be user fling or collision-modified velocity)
                    currentVelocity = existingVelocity
                    // Don't modify spriteState.velocity - preserve user fling velocities
                }
            }
            
            // Initialize velocity variable for the loop - will be overwritten by spriteState.velocity each frame
            var velocity = spriteState.velocity
            
            while (true) {
                // Clear processed collisions at start of each frame
                processedCollisions.clear()
                
                // Check if this sprite is being dragged - if so, pause normal physics
                // Use spriteData.spriteState for stable reference comparison
                if (currentlyDraggedSprite == spriteData.spriteState) {
                    // Sprite is being dragged - skip normal physics updates
                    // Collision detection is handled in the drag gesture handler
                    delay(16) // ~60fps
                    continue
                }
                
                // Snapshot sprite data at the start of each frame to avoid concurrent modification
                // This snapshot includes current cooldown timestamps for accurate checks
                val frameSpriteSnapshot = allSprites.toList()
                val spriteCooldownMap = frameSpriteSnapshot.associateBy { it.spriteState }
                
                // Recalculate effective speed in case multiplier changed during loop
                val currentEffectiveSpeed = baseSpeed * spriteData.speedMultiplier
                
                // Get current position and velocity from shared state
                // Validate position and velocity to prevent NaN propagation
                val rawPos = spriteState.position
                val rawVel = spriteState.velocity
                val currentPos = Offset(
                    if (rawPos.x.isNaN() || rawPos.x.isInfinite()) positionX.value else rawPos.x,
                    if (rawPos.y.isNaN() || rawPos.y.isInfinite()) positionY.value else rawPos.y
                )
                velocity = Offset(
                    if (rawVel.x.isNaN() || rawVel.x.isInfinite()) 0f else rawVel.x,
                    if (rawVel.y.isNaN() || rawVel.y.isInfinite()) 0f else rawVel.y
                )
                
                // Velocity is read directly from spriteState.velocity and used as-is
                // No decay or normalization - sprite maintains constant speed from drag release
                
                // Calculate new position using velocity
                var newX = currentPos.x + velocity.x
                var newY = currentPos.y + velocity.y
                
                var newVelocityX = velocity.x
                var newVelocityY = velocity.y
                
                // Check for collision with all other sprites before boundary checks
                // Use frame snapshot to avoid concurrent modification exceptions
                frameSpriteSnapshot.forEach { otherSpriteData ->
                    if (otherSpriteData != spriteData) {
                        val otherState = otherSpriteData.spriteState
                        val otherPos = otherState.position
                        val otherVel = otherState.velocity
                        
                        // Get fresh sprite data FIRST to ensure we have the latest sizeScale values
                        // This is critical because sizeScale can change during collisions
                        val freshSpriteForCollision = getFreshSpriteData(spriteState) ?: spriteData
                        val freshOtherSpriteForCollision = getFreshSpriteData(otherState) ?: otherSpriteData
                        
                        // Calculate effective sizes for both sprites (for collision detection)
                        val thisEffectiveSize = spriteSizePx * freshSpriteForCollision.sizeScale
                        val otherEffectiveSize = spriteSizePx * freshOtherSpriteForCollision.sizeScale
                        val avgSize = (thisEffectiveSize + otherEffectiveSize) / 2f
                        
                        // Check if sprites are currently colliding
                        val isCurrentlyColliding = checkCollision(currentPos, otherPos, avgSize)
                        
                        if (isCurrentlyColliding) {
                            val currentTime = System.currentTimeMillis()
                            
                            // Handle Fiat USD vs Bitcoin collisions
                            // Use fresh sprite data already retrieved above
                            val isFiatUsd = freshSpriteForCollision.spriteType == SpriteType.FIAT_USD
                            val isOtherFiatUsd = freshOtherSpriteForCollision.spriteType == SpriteType.FIAT_USD
                            val isBitcoin = freshOtherSpriteForCollision.spriteType == SpriteType.BITCOIN || freshOtherSpriteForCollision.spriteType == SpriteType.BITCOIN_ORANGE
                            val isOtherBitcoin = freshSpriteForCollision.spriteType == SpriteType.BITCOIN || freshSpriteForCollision.spriteType == SpriteType.BITCOIN_ORANGE
                            val isCat = freshSpriteForCollision.spriteType == SpriteType.CAT
                            val isOtherCat = freshOtherSpriteForCollision.spriteType == SpriteType.CAT
                            
                            // Fiat USD vs Bitcoin: shrink Fiat USD
                            if (isFiatUsd && isBitcoin) {
                                val freshSprite = freshSpriteForCollision
                                val timeSinceLastShrink = currentTime - freshSprite.lastShrinkCooldownTime
                                
                                // Check if cooldown has expired (3 seconds)
                                if (timeSinceLastShrink >= FIAT_SHRINK_COOLDOWN_MS) {
                                    // Shrink by 25%
                                    val newSizeScale = freshSprite.sizeScale * 0.75f
                                    val newCollisionCount = freshSprite.bitcoinCollisionCount + 1
                                    
                                    // Update sprite with new size and collision count
                                    val updatedSprite = freshSprite.copy(
                                        sizeScale = newSizeScale,
                                        bitcoinCollisionCount = newCollisionCount,
                                        lastShrinkCooldownTime = currentTime
                                    )
                                    updateSpriteData(freshSprite, updatedSprite)
                                    
                                    // If this is the 4th collision, mark for removal
                                    if (newCollisionCount >= 4) {
                                        markSpriteForRemoval(updatedSprite)
                                    }
                                }
                            }
                            
                            // Bitcoin vs Fiat USD: shrink Fiat USD (other sprite)
                            if (isOtherBitcoin && isOtherFiatUsd) {
                                val freshOtherSprite = freshOtherSpriteForCollision
                                val timeSinceLastShrink = currentTime - freshOtherSprite.lastShrinkCooldownTime
                                
                                // Check if cooldown has expired (3 seconds)
                                if (timeSinceLastShrink >= FIAT_SHRINK_COOLDOWN_MS) {
                                    // Shrink by 25%
                                    val newSizeScale = freshOtherSprite.sizeScale * 0.75f
                                    val newCollisionCount = freshOtherSprite.bitcoinCollisionCount + 1
                                    
                                    // Update sprite with new size and collision count
                                    val updatedSprite = freshOtherSprite.copy(
                                        sizeScale = newSizeScale,
                                        bitcoinCollisionCount = newCollisionCount,
                                        lastShrinkCooldownTime = currentTime
                                    )
                                    updateSpriteData(freshOtherSprite, updatedSprite)
                                    
                                    // If this is the 4th collision, mark for removal
                                    if (newCollisionCount >= 4) {
                                        markSpriteForRemoval(updatedSprite)
                                    }
                                }
                            }
                            
                            // Fiat USD vs Fiat USD: grow both
                            if (isFiatUsd && isOtherFiatUsd) {
                                val freshSprite1 = freshSpriteForCollision
                                val freshSprite2 = freshOtherSpriteForCollision
                                
                                // Grow sprite1
                                val newSizeScale1 = if (freshSprite1.sizeScale < 1.0f) {
                                    // If less than 100%, grow up to 100% first
                                    (freshSprite1.sizeScale * 1.25f).coerceAtMost(1.0f)
                                } else {
                                    // If at or above 100%, can grow up to 125%
                                    (freshSprite1.sizeScale * 1.25f).coerceAtMost(1.25f)
                                }
                                val updatedSprite1 = freshSprite1.copy(sizeScale = newSizeScale1)
                                updateSpriteData(freshSprite1, updatedSprite1)
                                
                                // Grow sprite2
                                val newSizeScale2 = if (freshSprite2.sizeScale < 1.0f) {
                                    // If less than 100%, grow up to 100% first
                                    (freshSprite2.sizeScale * 1.25f).coerceAtMost(1.0f)
                                } else {
                                    // If at or above 100%, can grow up to 125%
                                    (freshSprite2.sizeScale * 1.25f).coerceAtMost(1.25f)
                                }
                                val updatedSprite2 = freshSprite2.copy(sizeScale = newSizeScale2)
                                updateSpriteData(freshSprite2, updatedSprite2)
                            }
                            
                            // Cat vs Bitcoin: Bitcoin bounces, cat continues unaffected
                            // Cat vs Fiat USD: Fiat USD shrinks 25% and bounces, cat continues unaffected
                            // Flag to skip normal collision response for cat (cat doesn't bounce)
                            var skipNormalCollisionResponse = false
                            
                            if (isCat && (isBitcoin || isOtherFiatUsd)) {
                                // Cat colliding with Bitcoin or Fiat USD - cat doesn't bounce
                                skipNormalCollisionResponse = true
                                
                                // Only bounce the other sprite (Bitcoin or Fiat USD)
                                if (isBitcoin) {
                                    // Bitcoin bounces off cat - use normal collision response for Bitcoin only
                                    // Calculate centers for collision normal
                                    val center1 = Offset(currentPos.x + thisEffectiveSize / 2f, currentPos.y + thisEffectiveSize / 2f)
                                    val center2 = Offset(otherPos.x + otherEffectiveSize / 2f, otherPos.y + otherEffectiveSize / 2f)
                                    val dx = center2.x - center1.x
                                    val dy = center2.y - center1.y
                                    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                                    
                                    // Calculate collision normal (pointing from cat to Bitcoin)
                                    val normalX: Float
                                    val normalY: Float
                                    if (distance > 0f) {
                                        normalX = dx / distance
                                        normalY = dy / distance
                                    } else {
                                        val randomAngle = kotlin.random.Random.nextFloat() * 2f * kotlin.math.PI.toFloat()
                                        normalX = kotlin.math.cos(randomAngle)
                                        normalY = kotlin.math.sin(randomAngle)
                                    }
                                    
                                    // Reflect Bitcoin's velocity across the collision normal (cat's velocity unchanged)
                                    val dot = otherVel.x * normalX + otherVel.y * normalY
                                    val newOtherVelX = otherVel.x - 2f * dot * normalX
                                    val newOtherVelY = otherVel.y - 2f * dot * normalY
                                    otherState.velocity = Offset(newOtherVelX, newOtherVelY)
                                    
                                    // Always separate sprites (move Bitcoin away from cat) - use larger separation to prevent sticking
                                    val minDistance = avgSize * 1.5f // Increased minimum distance
                                    val separation = if (distance < avgSize) {
                                        // If overlapping, push apart with extra force
                                        (minDistance - distance) * CAT_COLLISION_SEPARATION_OVERLAPPING
                                    } else {
                                        // If touching but not overlapping, still push further apart
                                        (minDistance - distance) * CAT_COLLISION_SEPARATION_TOUCHING
                                    }
                                    val otherNewX = otherPos.x + normalX * separation.coerceAtLeast(0f)
                                    val otherNewY = otherPos.y + normalY * separation.coerceAtLeast(0f)
                                    otherState.position = Offset(
                                        otherNewX.coerceIn(0f, screenSize.width - otherEffectiveSize),
                                        otherNewY.coerceIn(0f, screenSize.height - otherEffectiveSize)
                                    )
                                } else if (isOtherFiatUsd) {
                                    // Cat vs Fiat USD: Fiat USD shrinks 25% and bounces, cat continues
                                    val freshFiatSprite = freshOtherSpriteForCollision
                                    val newSizeScale = freshFiatSprite.sizeScale * 0.75f
                                    val updatedFiatSprite = freshFiatSprite.copy(sizeScale = newSizeScale)
                                    updateSpriteData(freshFiatSprite, updatedFiatSprite)
                                    
                                    // Calculate centers for collision normal
                                    val center1 = Offset(currentPos.x + thisEffectiveSize / 2f, currentPos.y + thisEffectiveSize / 2f)
                                    val center2 = Offset(otherPos.x + otherEffectiveSize / 2f, otherPos.y + otherEffectiveSize / 2f)
                                    val dx = center2.x - center1.x
                                    val dy = center2.y - center1.y
                                    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                                    
                                    // Calculate collision normal (pointing from cat to Fiat USD)
                                    val normalX: Float
                                    val normalY: Float
                                    if (distance > 0f) {
                                        normalX = dx / distance
                                        normalY = dy / distance
                                    } else {
                                        val randomAngle = kotlin.random.Random.nextFloat() * 2f * kotlin.math.PI.toFloat()
                                        normalX = kotlin.math.cos(randomAngle)
                                        normalY = kotlin.math.sin(randomAngle)
                                    }
                                    
                                    // Reflect Fiat USD's velocity across the collision normal (cat's velocity unchanged)
                                    val dot = otherVel.x * normalX + otherVel.y * normalY
                                    val newOtherVelX = otherVel.x - 2f * dot * normalX
                                    val newOtherVelY = otherVel.y - 2f * dot * normalY
                                    otherState.velocity = Offset(newOtherVelX, newOtherVelY)
                                    
                                    // Always separate sprites (move Fiat USD away from cat) - use larger separation to prevent sticking
                                    val minDistance = avgSize * 1.5f // Increased minimum distance
                                    val separation = if (distance < avgSize) {
                                        // If overlapping, push apart with extra force
                                        (minDistance - distance) * CAT_COLLISION_SEPARATION_OVERLAPPING
                                    } else {
                                        // If touching but not overlapping, still push further apart
                                        (minDistance - distance) * CAT_COLLISION_SEPARATION_TOUCHING
                                    }
                                    val otherNewX = otherPos.x + normalX * separation.coerceAtLeast(0f)
                                    val otherNewY = otherPos.y + normalY * separation.coerceAtLeast(0f)
                                    otherState.position = Offset(
                                        otherNewX.coerceIn(0f, screenSize.width - otherEffectiveSize),
                                        otherNewY.coerceIn(0f, screenSize.height - otherEffectiveSize)
                                    )
                                }
                            } else if (isOtherCat && (isOtherBitcoin || isFiatUsd)) {
                                // Other sprite is cat, this sprite is Bitcoin or Fiat USD
                                skipNormalCollisionResponse = true
                                
                                if (isOtherBitcoin) {
                                    // Bitcoin bounces off cat - reflect this sprite's (Bitcoin's) velocity
                                    val center1 = Offset(otherPos.x + otherEffectiveSize / 2f, otherPos.y + otherEffectiveSize / 2f)
                                    val center2 = Offset(currentPos.x + thisEffectiveSize / 2f, currentPos.y + thisEffectiveSize / 2f)
                                    val dx = center2.x - center1.x
                                    val dy = center2.y - center1.y
                                    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                                    
                                    val normalX: Float
                                    val normalY: Float
                                    if (distance > 0f) {
                                        normalX = dx / distance
                                        normalY = dy / distance
                                    } else {
                                        val randomAngle = kotlin.random.Random.nextFloat() * 2f * kotlin.math.PI.toFloat()
                                        normalX = kotlin.math.cos(randomAngle)
                                        normalY = kotlin.math.sin(randomAngle)
                                    }
                                    
                                    // Reflect this sprite's (Bitcoin's) velocity
                                    val dot = velocity.x * normalX + velocity.y * normalY
                                    newVelocityX = velocity.x - 2f * dot * normalX
                                    newVelocityY = velocity.y - 2f * dot * normalY
                                    
                                    // Always separate sprites - use larger separation to prevent sticking
                                    val minDistance = avgSize * 1.5f // Increased minimum distance
                                    val separation = if (distance < avgSize) {
                                        // If overlapping, push apart with extra force
                                        (minDistance - distance) * CAT_COLLISION_SEPARATION_OVERLAPPING
                                    } else {
                                        // If touching but not overlapping, still push further apart
                                        (minDistance - distance) * CAT_COLLISION_SEPARATION_TOUCHING
                                    }
                                    newX = currentPos.x - normalX * separation.coerceAtLeast(0f)
                                    newY = currentPos.y - normalY * separation.coerceAtLeast(0f)
                                } else if (isFiatUsd) {
                                    // Fiat USD vs Cat: Fiat USD shrinks 25% and bounces
                                    val freshFiatSprite = freshSpriteForCollision
                                    val newSizeScale = freshFiatSprite.sizeScale * 0.75f
                                    val updatedFiatSprite = freshFiatSprite.copy(sizeScale = newSizeScale)
                                    updateSpriteData(freshFiatSprite, updatedFiatSprite)
                                    
                                    // Calculate collision normal
                                    val center1 = Offset(otherPos.x + otherEffectiveSize / 2f, otherPos.y + otherEffectiveSize / 2f)
                                    val center2 = Offset(currentPos.x + thisEffectiveSize / 2f, currentPos.y + thisEffectiveSize / 2f)
                                    val dx = center2.x - center1.x
                                    val dy = center2.y - center1.y
                                    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                                    
                                    val normalX: Float
                                    val normalY: Float
                                    if (distance > 0f) {
                                        normalX = dx / distance
                                        normalY = dy / distance
                                    } else {
                                        val randomAngle = kotlin.random.Random.nextFloat() * 2f * kotlin.math.PI.toFloat()
                                        normalX = kotlin.math.cos(randomAngle)
                                        normalY = kotlin.math.sin(randomAngle)
                                    }
                                    
                                    // Reflect Fiat USD's (this sprite's) velocity
                                    val dot = velocity.x * normalX + velocity.y * normalY
                                    newVelocityX = velocity.x - 2f * dot * normalX
                                    newVelocityY = velocity.y - 2f * dot * normalY
                                    
                                    // Always separate sprites - use larger separation to prevent sticking
                                    val minDistance = avgSize * 1.5f // Increased minimum distance
                                    val separation = if (distance < avgSize) {
                                        // If overlapping, push apart with extra force
                                        (minDistance - distance) * CAT_COLLISION_SEPARATION_OVERLAPPING
                                    } else {
                                        // If touching but not overlapping, still push further apart
                                        (minDistance - distance) * CAT_COLLISION_SEPARATION_TOUCHING
                                    }
                                    newX = currentPos.x - normalX * separation.coerceAtLeast(0f)
                                    newY = currentPos.y - normalY * separation.coerceAtLeast(0f)
                                }
                            }
                            
                            // Only perform normal collision response if cat collision handling didn't skip it
                            if (!skipNormalCollisionResponse) {
                                // Create a collision pair (ordered to ensure consistent deduplication)
                                // Use fresh sprite data for collision pair to ensure consistency
                                val collisionPair = if (freshSpriteForCollision.hashCode() < freshOtherSpriteForCollision.hashCode()) {
                                    Pair(freshSpriteForCollision, freshOtherSpriteForCollision)
                                } else {
                                    Pair(freshOtherSpriteForCollision, freshSpriteForCollision)
                                }
                                
                                // Calculate centers for collision normal and collision point (using effective sizes)
                                val center1 = Offset(currentPos.x + thisEffectiveSize / 2f, currentPos.y + thisEffectiveSize / 2f)
                                val center2 = Offset(otherPos.x + otherEffectiveSize / 2f, otherPos.y + otherEffectiveSize / 2f)
                                val dx = center2.x - center1.x
                                val dy = center2.y - center1.y
                                val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                                
                                // Calculate collision normal (pointing from sprite1 to sprite2)
                                val normalX: Float
                                val normalY: Float
                                if (distance > 0f) {
                                    normalX = dx / distance
                                    normalY = dy / distance
                                } else {
                                    // If distance is 0, use a random direction to separate (unit vector)
                                    val randomAngle = kotlin.random.Random.nextFloat() * 2f * kotlin.math.PI.toFloat()
                                    normalX = kotlin.math.cos(randomAngle)
                                    normalY = kotlin.math.sin(randomAngle)
                                }
                                
                                // Calculate collision point (midpoint between centers)
                                val collisionCenter = Offset((center1.x + center2.x) / 2f, (center1.y + center2.y) / 2f)
                                
                                // Offset along collision normal to prevent immediate re-collision
                                // Use 0.75x average sprite size to keep clone near collision point
                                val offsetDistance = avgSize * 0.75f
                                val offsetCenter = Offset(
                                    collisionCenter.x + normalX * offsetDistance,
                                    collisionCenter.y + normalY * offsetDistance
                                )
                                
                                // Adjust to top-left corner position for sprite placement (use base size for clone)
                                val collisionPoint = Offset(
                                    offsetCenter.x - spriteSizePx / 2f,
                                    offsetCenter.y - spriteSizePx / 2f
                                )
                                
                                // Check global cooldown: if any clone was spawned recently, disable all spawning
                                // Get fresh value each frame to see updates
                                // Use currentTime already declared at the start of collision handler
                                val lastGlobalSpawn = getLastGlobalCloneSpawnTime()
                                val globalCooldownActive = (currentTime - lastGlobalSpawn) < CLONE_SPAWN_COOLDOWN_MS
                                
                                // Use fresh sprite data already retrieved above for clone creation
                                // Trigger clone creation if:
                                // 1. Collision not yet processed this frame
                                // 2. At least one sprite is original
                                // 3. Only the sprite with lower hash code spawns (prevents duplicate spawns)
                                // 4. Global cooldown is not active (no clones spawned in last 3 seconds)
                                if (!processedCollisions.contains(collisionPair) && 
                                    (freshSpriteForCollision.isOriginal || freshOtherSpriteForCollision.isOriginal) &&
                                    freshSpriteForCollision.hashCode() < freshOtherSpriteForCollision.hashCode() &&
                                    !globalCooldownActive) {
                                    processedCollisions.add(collisionPair)
                                    onCloneRequest(freshSpriteForCollision, freshOtherSpriteForCollision, collisionPoint)
                                }
                                
                                // If sprites are overlapping, separate them FIRST before calculating collision response
                                if (distance < avgSize) {
                                    // Push sprites apart along collision normal (use average size)
                                    val minDistance = avgSize * 1.1f // Add 10% extra separation
                                    val overlap = avgSize - distance
                                    val separation = (minDistance - distance) / 2f * 1.5f // 1.5x separation force
                                    
                                    // Move this sprite away from the other
                                    newX = currentPos.x - normalX * separation
                                    newY = currentPos.y - normalY * separation
                                    
                                    // Also move the other sprite away from this one to prevent sticking
                                    val otherNewX = otherPos.x + normalX * separation
                                    val otherNewY = otherPos.y + normalY * separation
                                    otherState.position = Offset(
                                        otherNewX.coerceIn(0f, screenSize.width - otherEffectiveSize),
                                        otherNewY.coerceIn(0f, screenSize.height - otherEffectiveSize)
                                    )
                                    
                                    // Use separated positions for collision response calculation
                                    val separatedPos1 = Offset(newX, newY)
                                    val separatedPos2 = otherState.position
                                    
                                    // Calculate collision response using separated positions (use average size)
                                    val (newVel1, newVel2) = calculateCollisionResponse(
                                        separatedPos1, velocity,
                                        separatedPos2, otherVel,
                                        avgSize
                                    )
                                    
                                    // Apply new velocities
                                    newVelocityX = newVel1.x
                                    newVelocityY = newVel1.y
                                    otherState.velocity = newVel2
                                    
                                } else {
                                    // Sprites are touching but not overlapping - just calculate collision response
                                    val (newVel1, newVel2) = calculateCollisionResponse(
                                        currentPos, velocity,
                                        otherPos, otherVel,
                                        avgSize
                                    )
                                    
                                    // Apply new velocities
                                    newVelocityX = newVel1.x
                                    newVelocityY = newVel1.y
                                    otherState.velocity = newVel2
                                }
                            }
                        }
                    }
                }
                
                // Get fresh sprite data to check current sizeScale
                val freshSpriteForSize = getFreshSpriteData(spriteState) ?: spriteData
                val currentEffectiveSize = spriteSizePx * freshSpriteForSize.sizeScale
                
                // Check horizontal boundaries (using effective size)
                if (newX < 0) {
                    newVelocityX = -newVelocityX
                } else if (newX > screenSize.width - currentEffectiveSize) {
                    newVelocityX = -newVelocityX
                }
                
                // Check vertical boundaries (using effective size)
                if (newY < 0) {
                    newVelocityY = -newVelocityY
                } else if (newY > screenSize.height - currentEffectiveSize) {
                    newVelocityY = -newVelocityY
                }
                
                // Update velocity if changed
                if (newVelocityX != velocity.x || newVelocityY != velocity.y) {
                    velocity = Offset(newVelocityX, newVelocityY)
                    currentVelocity = velocity
                }
                
                previousVelocity = velocity
                
                // Update shared state
                spriteState.velocity = velocity

                // Optional detailed physics logging for debugging sprite motion after release
                if (ENABLE_DRAG_LOGS && spriteData.spriteType == SpriteType.BITCOIN_ORANGE) {
                    val speed = kotlin.math.sqrt(velocity.x * velocity.x + velocity.y * velocity.y)
                    Log.d(
                        "Physics",
                        "Sprite PHYSICS - Type: ${spriteData.spriteType}, Pos: (${currentPos.x}, ${currentPos.y}), " +
                                "Vel: (${velocity.x}, ${velocity.y}), Speed: $speed"
                    )
                }
                
                // Validate newX and newY before clamping (check for NaN or invalid values)
                val validNewX = if (newX.isNaN() || newX.isInfinite()) currentPos.x else newX
                val validNewY = if (newY.isNaN() || newY.isInfinite()) currentPos.y else newY
                
                // Calculate final position with boundary clamping (using effective size)
                // Ensure screenSize is valid before using it
                val currentEffectiveSizeForClamp = spriteSizePx * freshSpriteForSize.sizeScale
                val maxX = if (screenSize.width > currentEffectiveSizeForClamp) screenSize.width - currentEffectiveSizeForClamp else 0f
                val maxY = if (screenSize.height > currentEffectiveSizeForClamp) screenSize.height - currentEffectiveSizeForClamp else 0f
                val clampedX = validNewX.coerceIn(0f, maxX.coerceAtLeast(0f))
                val clampedY = validNewY.coerceIn(0f, maxY.coerceAtLeast(0f))
                
                // Final validation - ensure no NaN values before setting position
                val finalX = if (clampedX.isNaN() || clampedX.isInfinite()) currentPos.x else clampedX
                val finalY = if (clampedY.isNaN() || clampedY.isInfinite()) currentPos.y else clampedY
                
                spriteState.position = Offset(finalX, finalY)
                
                // Animate to new position (ensure target values are valid with multiple layers of validation)
                // Get current animation values as safe fallbacks
                val currentAnimX = positionX.value
                val currentAnimY = positionY.value
                
                // First check: use currentPos as fallback if final is invalid
                val safeX = if (finalX.isNaN() || finalX.isInfinite()) {
                    if (currentPos.x.isNaN() || currentPos.x.isInfinite()) {
                        if (currentAnimX.isNaN() || currentAnimX.isInfinite()) 0f else currentAnimX
                    } else {
                        currentPos.x
                    }
                } else {
                    finalX
                }
                
                val safeY = if (finalY.isNaN() || finalY.isInfinite()) {
                    if (currentPos.y.isNaN() || currentPos.y.isInfinite()) {
                        if (currentAnimY.isNaN() || currentAnimY.isInfinite()) 0f else currentAnimY
                    } else {
                        currentPos.y
                    }
                } else {
                    finalY
                }
                
                // Final check before animateTo - absolutely no NaN allowed
                if (!safeX.isNaN() && !safeX.isInfinite() && !safeY.isNaN() && !safeY.isInfinite()) {
                    positionX.animateTo(
                        targetValue = safeX,
                        animationSpec = tween(durationMillis = 16, easing = LinearEasing)
                    )
                    positionY.animateTo(
                        targetValue = safeY,
                        animationSpec = tween(durationMillis = 16, easing = LinearEasing)
                    )
                }
                
                delay(16) // ~60fps
            }
        }
        
        // Draw sprite (use sizeScale for visual size)
        val freshSpriteForVisual = getFreshSpriteData(spriteState) ?: spriteData
        val visualSize = spriteSize * freshSpriteForVisual.sizeScale
        
        // Cat sprite animation frame tracking
        var catAnimationFrame by remember { mutableStateOf(0) }
        var catDirection by remember { mutableStateOf("down") } // Tracks movement direction ("up", "down", "left", "right", "down_left", "down_right", etc.)
        
        // Animate cat sprite frames when moving
        LaunchedEffect(spriteData.spriteType == SpriteType.CAT, spriteState.velocity) {
            if (spriteData.spriteType == SpriteType.CAT) {
                val velocityMagnitude = kotlin.math.sqrt(
                    spriteState.velocity.x * spriteState.velocity.x + 
                    spriteState.velocity.y * spriteState.velocity.y
                )
                val isMoving = velocityMagnitude > 0.1f
                
                // Determine direction based on velocity
                val absX = kotlin.math.abs(spriteState.velocity.x)
                val absY = kotlin.math.abs(spriteState.velocity.y)
                
                // Check if movement is diagonal (both X and Y velocities are significant)
                val ratio = if (absY > 0f) absX / absY else 0f
                val isDiagonal = ratio >= CAT_DIAGONAL_RATIO_MIN && 
                                 ratio <= CAT_DIAGONAL_RATIO_MAX && 
                                 absX > CAT_DIAGONAL_MIN_VELOCITY && 
                                 absY > CAT_DIAGONAL_MIN_VELOCITY
                
                if (isDiagonal) {
                    // Diagonal movement detected
                    if (spriteState.velocity.x < 0 && spriteState.velocity.y > 0) {
                        // Moving down-left (negative X, positive Y)
                        catDirection = "down_left"
                    } else if (spriteState.velocity.x > 0 && spriteState.velocity.y > 0) {
                        // Moving down-right (positive X, positive Y)
                        catDirection = "down_right"
                    } else if (spriteState.velocity.x < 0 && spriteState.velocity.y < 0) {
                        // Moving up-left (negative X, negative Y)
                        catDirection = "up_left"
                    } else if (spriteState.velocity.x > 0 && spriteState.velocity.y < 0) {
                        // Moving up-right (positive X, negative Y)
                        catDirection = "up_right"
                    } else {
                        // Fall back to horizontal/vertical
                        if (absX > absY) {
                            if (spriteState.velocity.x < 0) {
                                catDirection = "left"
                            } else {
                                catDirection = "right"
                            }
                        } else {
                            if (spriteState.velocity.y < 0) {
                                catDirection = "up"
                            } else {
                                catDirection = "down"
                            }
                        }
                    }
                } else if (absX > absY) {
                    // Horizontal movement is dominant
                    if (spriteState.velocity.x < 0) {
                        catDirection = "left"
                    } else {
                        catDirection = "right"
                    }
                } else {
                    // Vertical movement is dominant
                    // Negative Y means moving up (towards top of screen)
                    // Positive Y or zero means moving down
                    if (spriteState.velocity.y < 0) {
                        catDirection = "up"
                    } else {
                        catDirection = "down"
                    }
                }
                
                if (isMoving) {
                    while (true) {
                        catAnimationFrame = (catAnimationFrame + 1) % 2 // Alternate between 0 and 1
                        delay(CAT_ANIMATION_FRAME_DELAY_MS)
                    }
                } else {
                    catAnimationFrame = 0 // Idle frame when not moving
                }
            }
        }
        
        // Select resource ID based on sprite type, direction, and animation frame
        val resourceId = if (spriteData.spriteType == SpriteType.CAT) {
            when (catDirection) {
                "up" -> when (catAnimationFrame) {
                    0 -> R.drawable.e_cat_up_1
                    1 -> R.drawable.e_cat_up_2
                    else -> R.drawable.e_cat_up_1
                }
                "down" -> when (catAnimationFrame) {
                    0 -> R.drawable.e_cat_down_1
                    1 -> R.drawable.e_cat_down_2
                    else -> R.drawable.e_cat_down_1
                }
                "left" -> when (catAnimationFrame) {
                    0 -> R.drawable.e_cat_left_1
                    1 -> R.drawable.e_cat_left_2
                    else -> R.drawable.e_cat_left_1
                }
                "right" -> when (catAnimationFrame) {
                    0 -> R.drawable.e_cat_right_1
                    1 -> R.drawable.e_cat_right_2
                    else -> R.drawable.e_cat_right_1
                }
                "down_left" -> when (catAnimationFrame) {
                    0 -> R.drawable.e_cat_down_left_1
                    1 -> R.drawable.e_cat_down_left_2
                    else -> R.drawable.e_cat_down_left_1
                }
                "down_right" -> when (catAnimationFrame) {
                    0 -> R.drawable.e_cat_down_right_1
                    1 -> R.drawable.e_cat_down_right_2
                    else -> R.drawable.e_cat_down_right_1
                }
                "up_left" -> when (catAnimationFrame) {
                    0 -> R.drawable.e_cat_up_left_1
                    1 -> R.drawable.e_cat_up_left_2
                    else -> R.drawable.e_cat_up_left_1
                }
                "up_right" -> when (catAnimationFrame) {
                    0 -> R.drawable.e_cat_up_right_1
                    1 -> R.drawable.e_cat_up_right_2
                    else -> R.drawable.e_cat_up_right_1
                }
                else -> R.drawable.e_cat_down_1 // Default to down frames
            }
        } else {
            spriteData.spriteResourceId
        }
        
        androidx.compose.foundation.Image(
            painter = painterResource(id = resourceId),
            contentDescription = contentDescription,
            modifier = Modifier
                .offset {
                    IntOffset(
                        positionX.value.toInt(),
                        positionY.value.toInt()
                    )
                }
                .size(visualSize)
                .pointerInput(spriteData) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            // Fiat USD and Cat sprites cannot be dragged - ignore drag gestures
                            if (spriteData.spriteType == SpriteType.FIAT_USD || spriteData.spriteType == SpriteType.CAT) {
                                return@detectDragGestures
                            }
                            
                            // Check if any sprite is already being dragged
                            if (currentlyDraggedSprite == null) {
                                // Claim this sprite as being dragged (use spriteData.spriteState for stable reference)
                                setCurrentlyDraggedSprite(spriteData.spriteState)
                                localDraggedSpriteState = spriteData.spriteState  // Also store locally for reliable tracking
                                // Store initial screen position where drag started (offset is already in screen coordinates)
                                // Add sprite center offset to get the sprite center position
                                val spriteCenterAtStart = Offset(
                                    spriteData.spriteState.position.x + spriteSizePx / 2f,
                                    spriteData.spriteState.position.y + spriteSizePx / 2f
                                )
                                dragStartPosition = spriteCenterAtStart
                                cumulativeDragAmount = Offset.Zero
                                // Store initial position and time for velocity calculation
                                lastDragPosition = offset
                                lastDragTime = System.currentTimeMillis()
                                recentDragMovements = emptyList()
                                
                                if (ENABLE_DRAG_LOGS) {
                                    Log.d("DragGesture", "Sprite SELECTED - Type: ${spriteData.spriteType}, Start Position: (${spriteData.spriteState.position.x}, ${spriteData.spriteState.position.y}), Touch Position: (${offset.x}, ${offset.y}), Local stored: ${System.identityHashCode(localDraggedSpriteState)}")
                                }
                            } else {
                                if (ENABLE_DRAG_LOGS) {
                                    Log.d("DragGesture", "Sprite selection REJECTED - Another sprite is already being dragged (Type: ${spriteData.spriteType})")
                                }
                            }
                        },
                        onDrag = { change, dragAmount ->
                            // Only process if this sprite is the one being dragged (check both global and local state)
                            if (localDraggedSpriteState == spriteData.spriteState || currentlyDraggedSprite == spriteData.spriteState) {
                                val currentTime = System.currentTimeMillis()
                                
                                // Accumulate drag amount to track total movement from drag start
                                cumulativeDragAmount = Offset(
                                    cumulativeDragAmount.x + dragAmount.x,
                                    cumulativeDragAmount.y + dragAmount.y
                                )
                                
                                // Calculate current screen position = initial center position + cumulative drag
                                val currentScreenPosition = if (dragStartPosition != null) {
                                    Offset(
                                        dragStartPosition!!.x + cumulativeDragAmount.x,
                                        dragStartPosition!!.y + cumulativeDragAmount.y
                                    )
                                } else {
                                    // Fallback: use current sprite center + drag amount
                                    Offset(
                                        spriteData.spriteState.position.x + spriteSizePx / 2f + cumulativeDragAmount.x,
                                        spriteData.spriteState.position.y + spriteSizePx / 2f + cumulativeDragAmount.y
                                    )
                                }
                                
                                // Validate screen position - check for NaN or invalid values
                                val validScreenX = if (currentScreenPosition.x.isNaN() || currentScreenPosition.x.isInfinite()) {
                                    spriteData.spriteState.position.x + spriteSizePx / 2f
                                } else {
                                    currentScreenPosition.x
                                }
                                val validScreenY = if (currentScreenPosition.y.isNaN() || currentScreenPosition.y.isInfinite()) {
                                    spriteData.spriteState.position.y + spriteSizePx / 2f
                                } else {
                                    currentScreenPosition.y
                                }
                                
                                // Track recent drag movements for velocity calculation (keep last 100ms)
                                recentDragMovements = (recentDragMovements + (dragAmount to currentTime))
                                    .filter { (_, time) -> currentTime - time <= 100L }
                                
                                // Update sprite position to follow finger (account for sprite center offset)
                                // Calculate sprite top-left from screen center position
                                val newPos = Offset(
                                    validScreenX - spriteSizePx / 2f,
                                    validScreenY - spriteSizePx / 2f
                                )
                                
                                // Keep sprite within screen bounds (ensure screenSize is valid)
                                val maxX = if (screenSize.width > 0) screenSize.width - spriteSizePx else 0f
                                val maxY = if (screenSize.height > 0) screenSize.height - spriteSizePx else 0f
                                val boundedX = newPos.x.coerceIn(0f, maxX.coerceAtLeast(0f))
                                val boundedY = newPos.y.coerceIn(0f, maxY.coerceAtLeast(0f))
                                
                                // Final validation - ensure no NaN values
                                val finalX = if (boundedX.isNaN() || boundedX.isInfinite()) spriteData.spriteState.position.x else boundedX
                                val finalY = if (boundedY.isNaN() || boundedY.isInfinite()) spriteData.spriteState.position.y else boundedY
                                
                                spriteData.spriteState.position = Offset(finalX, finalY)
                                
                                // Immediately update Animatable values to make sprite follow finger
                                // Use coroutine scope to call snapTo (required for suspend function)
                                coroutineScope.launch {
                                    // Validate before snapping
                                    val safeX = if (finalX.isNaN() || finalX.isInfinite()) positionX.value else finalX
                                    val safeY = if (finalY.isNaN() || finalY.isInfinite()) positionY.value else finalY
                                    if (!safeX.isNaN() && !safeX.isInfinite() && !safeY.isNaN() && !safeY.isInfinite()) {
                                        positionX.snapTo(safeX)
                                        positionY.snapTo(safeY)
                                    }
                                }
                                
                                // Track position and time for velocity calculation
                                lastDragPosition = currentScreenPosition
                                lastDragTime = currentTime
                                
                                if (ENABLE_DRAG_LOGS) {
                                    // Log drag direction and movement
                                    val dragDirection = if (dragAmount.x != 0f || dragAmount.y != 0f) {
                                        val angle = kotlin.math.atan2(dragAmount.y, dragAmount.x) * 180f / kotlin.math.PI.toFloat()
                                        val direction = when {
                                            angle >= -22.5f && angle < 22.5f -> "RIGHT"
                                            angle >= 22.5f && angle < 67.5f -> "DOWN-RIGHT"
                                            angle >= 67.5f && angle < 112.5f -> "DOWN"
                                            angle >= 112.5f && angle < 157.5f -> "DOWN-LEFT"
                                            angle >= 157.5f || angle < -157.5f -> "LEFT"
                                            angle >= -157.5f && angle < -112.5f -> "UP-LEFT"
                                            angle >= -112.5f && angle < -67.5f -> "UP"
                                            else -> "UP-RIGHT"
                                        }
                                        "Angle: ${angle.toInt()}° ($direction)"
                                    } else {
                                        "NONE"
                                    }
                                    val dragDistance = kotlin.math.sqrt(dragAmount.x * dragAmount.x + dragAmount.y * dragAmount.y)
                                    Log.d("DragGesture", "Sprite DRAGGING - Type: ${spriteData.spriteType}, Position: (${finalX}, ${finalY}), Drag Amount: (${dragAmount.x}, ${dragAmount.y}), Distance: ${dragDistance.toInt()}px, Direction: $dragDirection")
                                }
                                
                                // Check for collisions with other sprites during drag
                                val frameSpriteSnapshot = allSprites.toList()
                                frameSpriteSnapshot.forEach { otherSpriteData ->
                                    if (otherSpriteData != spriteData) {
                                        val otherState = otherSpriteData.spriteState
                                        val otherPos = otherState.position
                                        
                                        // Calculate effective sizes for collision detection during drag (get fresh sprite data)
                                val freshDraggedSprite = getFreshSpriteData(spriteData.spriteState) ?: spriteData
                                val draggedEffectiveSize = spriteSizePx * freshDraggedSprite.sizeScale
                                val otherEffectiveSizeDrag = spriteSizePx * otherSpriteData.sizeScale
                                val avgSizeDrag = (draggedEffectiveSize + otherEffectiveSizeDrag) / 2f
                                
                                // Check if sprites are colliding
                                        val isCurrentlyColliding = checkCollision(
                                            spriteData.spriteState.position,
                                            otherPos,
                                            avgSizeDrag
                                        )
                                        
                                        if (isCurrentlyColliding) {
                                            // Calculate collision response
                                            val center1 = Offset(
                                                spriteData.spriteState.position.x + spriteSizePx / 2f,
                                                spriteData.spriteState.position.y + spriteSizePx / 2f
                                            )
                                            val center2 = Offset(
                                                otherPos.x + spriteSizePx / 2f,
                                                otherPos.y + spriteSizePx / 2f
                                            )
                                            val dx = center2.x - center1.x
                                            val dy = center2.y - center1.y
                                            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                                            
                                            // Calculate collision normal
                                            val normalX: Float
                                            val normalY: Float
                                            if (distance > 0f) {
                                                normalX = dx / distance
                                                normalY = dy / distance
                                            } else {
                                                val randomAngle = kotlin.random.Random.nextFloat() * 2f * kotlin.math.PI.toFloat()
                                                normalX = kotlin.math.cos(randomAngle)
                                                normalY = kotlin.math.sin(randomAngle)
                                            }
                                            
                                            // If sprites are overlapping, separate them (using effective sizes)
                                            if (distance < avgSizeDrag) {
                                                val minDistance = avgSizeDrag * 1.1f
                                                val overlap = avgSizeDrag - distance
                                                val separation = (minDistance - distance) / 2f * 1.5f
                                                
                                                // Move dragged sprite away from other sprite
                                                val newDraggedX = (spriteData.spriteState.position.x - normalX * separation)
                                                    .coerceIn(0f, screenSize.width - draggedEffectiveSize)
                                                val newDraggedY = (spriteData.spriteState.position.y - normalY * separation)
                                                    .coerceIn(0f, screenSize.height - draggedEffectiveSize)
                                                
                                                spriteData.spriteState.position = Offset(newDraggedX, newDraggedY)
                                                
                                                // Immediately update Animatable values during collision
                                                coroutineScope.launch {
                                                    val safeX = if (newDraggedX.isNaN() || newDraggedX.isInfinite()) positionX.value else newDraggedX
                                                    val safeY = if (newDraggedY.isNaN() || newDraggedY.isInfinite()) positionY.value else newDraggedY
                                                    if (!safeX.isNaN() && !safeX.isInfinite() && !safeY.isNaN() && !safeY.isInfinite()) {
                                                        positionX.snapTo(safeX)
                                                        positionY.snapTo(safeY)
                                                    }
                                                }
                                                
                                                // Also move the other sprite away (if it's not being dragged)
                                                if (currentlyDraggedSprite != otherState) {
                                                    val otherNewX = (otherPos.x + normalX * separation)
                                                        .coerceIn(0f, screenSize.width - otherEffectiveSizeDrag)
                                                    val otherNewY = (otherPos.y + normalY * separation)
                                                        .coerceIn(0f, screenSize.height - otherEffectiveSizeDrag)
                                                    otherState.position = Offset(otherNewX, otherNewY)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        onDragEnd = {
                            if (ENABLE_DRAG_LOGS) {
                                Log.d("DragGesture", "onDragEnd CALLED - Type: ${spriteData.spriteType}, currentlyDraggedSprite == spriteState: ${currentlyDraggedSprite == spriteData.spriteState}, localDraggedSpriteState == spriteState: ${localDraggedSpriteState == spriteData.spriteState}, currentlyDraggedSprite identity: ${System.identityHashCode(currentlyDraggedSprite)}, localDraggedSpriteState identity: ${System.identityHashCode(localDraggedSpriteState)}, spriteState identity: ${System.identityHashCode(spriteData.spriteState)}")
                            }
                            // Only process if this sprite was the one being dragged (check local state first, then global)
                            val wasDragged = localDraggedSpriteState == spriteData.spriteState || currentlyDraggedSprite == spriteData.spriteState
                            if (wasDragged) {
                                // Calculate velocity from recent drag movements
                                val currentTime = System.currentTimeMillis()
                                
                                if (ENABLE_DRAG_LOGS) {
                                    Log.d("DragGesture", "onDragEnd - recentDragMovements.size: ${recentDragMovements.size}, lastDragPosition: $lastDragPosition, lastDragTime: $lastDragTime")
                                }
                                
                                val flingVelocity = if (recentDragMovements.isNotEmpty()) {
                                    // Use the most recent movements (last 50-100ms) for velocity calculation
                                    val recentMovements = recentDragMovements.takeLast(10) // Use last 10 movements
                                    
                                    // Sum recent drag movements
                                    val totalMovement = recentMovements.fold(Offset.Zero) { acc, (movement, _) ->
                                        Offset(acc.x + movement.x, acc.y + movement.y)
                                    }
                                    
                                    // Calculate time span of recent movements
                                    val timeSpan = if (recentMovements.size > 1) {
                                        val oldestTime = recentMovements.first().second
                                        val newestTime = recentMovements.last().second
                                        (newestTime - oldestTime).coerceAtLeast(1L).toFloat()
                                    } else {
                                        16f // Default to one frame if only one movement
                                    }
                                    
                                    // Convert drag amount (pixels) to velocity (pixels per frame at 60fps)
                                    // Divide by timeSpan (ms) and multiply by 16 (ms per frame at 60fps)
                                    // Use a multiplier to make the velocity more noticeable (scale up by 50x for better feel)
                                    val velocityMultiplier = 50f
                                    val calculatedVelocity = Offset(
                                        (totalMovement.x / timeSpan) * (16f / 1000f) * velocityMultiplier,
                                        (totalMovement.y / timeSpan) * (16f / 1000f) * velocityMultiplier
                                    )
                                    
                                    if (ENABLE_DRAG_LOGS) {
                                        Log.d("DragGesture", "onDragEnd - totalMovement: (${totalMovement.x}, ${totalMovement.y}), timeSpan: ${timeSpan}ms, calculatedVelocity: (${calculatedVelocity.x}, ${calculatedVelocity.y})")
                                    }
                                    
                                    calculatedVelocity
                                } else {
                                    // Fallback: calculate velocity from last drag position and current position
                                    val lastPosValue = lastDragPosition  // Store in local variable for smart cast
                                    if (lastPosValue != null && lastDragTime > 0L) {
                                        val timeDelta = (currentTime - lastDragTime).coerceAtLeast(1L).toFloat()
                                        val currentPos = spriteData.spriteState.position
                                        val lastPos = Offset(
                                            lastPosValue.x - spriteSizePx / 2f,
                                            lastPosValue.y - spriteSizePx / 2f
                                        )
                                        val positionDelta = Offset(
                                            currentPos.x - lastPos.x,
                                            currentPos.y - lastPos.y
                                        )
                                        
                                        // Convert to pixels per frame
                                        // Use a multiplier to make the velocity more noticeable (scale up by 50x for better feel)
                                        val velocityMultiplier = 50f
                                        val fallbackVelocity = Offset(
                                            (positionDelta.x / timeDelta) * (16f / 1000f) * velocityMultiplier,
                                            (positionDelta.y / timeDelta) * (16f / 1000f) * velocityMultiplier
                                        )
                                        
                                        if (ENABLE_DRAG_LOGS) {
                                            Log.d("DragGesture", "onDragEnd - Using fallback velocity calculation: positionDelta: (${positionDelta.x}, ${positionDelta.y}), timeDelta: ${timeDelta}ms, fallbackVelocity: (${fallbackVelocity.x}, ${fallbackVelocity.y})")
                                        }
                                        
                                        fallbackVelocity
                                    } else {
                                        if (ENABLE_DRAG_LOGS) {
                                            Log.d("DragGesture", "onDragEnd - No velocity data available, using zero velocity")
                                        }
                                        Offset.Zero
                                    }
                                }
                                
                                // Cap maximum velocity to prevent extreme speeds
                                val maxVelocity = 50f
                                val velocityMagnitude = kotlin.math.sqrt(
                                    flingVelocity.x * flingVelocity.x + flingVelocity.y * flingVelocity.y
                                )
                                
                                // Ensure velocity magnitude is never negative (shouldn't happen, but validate)
                                val safeMagnitude = velocityMagnitude.coerceAtLeast(0f)
                                
                                val cappedVelocity = if (safeMagnitude > maxVelocity) {
                                    val scale = maxVelocity / safeMagnitude
                                    Offset(flingVelocity.x * scale, flingVelocity.y * scale)
                                } else {
                                    flingVelocity
                                }
                                
                                // Validate capped velocity - ensure no NaN or invalid values
                                val finalVelocity = Offset(
                                    if (cappedVelocity.x.isNaN() || cappedVelocity.x.isInfinite()) 0f else cappedVelocity.x,
                                    if (cappedVelocity.y.isNaN() || cappedVelocity.y.isInfinite()) 0f else cappedVelocity.y
                                )
                                
                                // Ensure velocity magnitude is positive (at least a minimum threshold for movement)
                                val finalMagnitude = kotlin.math.sqrt(
                                    finalVelocity.x * finalVelocity.x + finalVelocity.y * finalVelocity.y
                                )
                                
                                if (ENABLE_DRAG_LOGS) {
                                    Log.d("DragGesture", "onDragEnd - flingVelocity: (${flingVelocity.x}, ${flingVelocity.y}), magnitude: $velocityMagnitude, cappedVelocity: (${cappedVelocity.x}, ${cappedVelocity.y}), finalVelocity: (${finalVelocity.x}, ${finalVelocity.y}), finalMagnitude: $finalMagnitude")
                                }
                                
                                // Set velocity directly - sprite will continue moving at this speed indefinitely
                                // Note: Negative x/y components are normal (negative x = left, negative y = up)
                                spriteData.spriteState.velocity = finalVelocity
                                
                                if (ENABLE_DRAG_LOGS) {
                                    Log.d("DragGesture", "onDragEnd - Set spriteState.velocity: (${finalVelocity.x}, ${finalVelocity.y}), magnitude: $finalMagnitude")
                                }
                                
                                if (ENABLE_DRAG_LOGS) {
                                    // Log release with momentum and direction
                                    val momentumMagnitude = kotlin.math.sqrt(
                                        cappedVelocity.x * cappedVelocity.x + cappedVelocity.y * cappedVelocity.y
                                    )
                                    val momentumAngle = if (cappedVelocity.x != 0f || cappedVelocity.y != 0f) {
                                        kotlin.math.atan2(cappedVelocity.y, cappedVelocity.x) * 180f / kotlin.math.PI.toFloat()
                                    } else {
                                        0f
                                    }
                                    val momentumDirection = when {
                                        momentumAngle >= -22.5f && momentumAngle < 22.5f -> "RIGHT"
                                        momentumAngle >= 22.5f && momentumAngle < 67.5f -> "DOWN-RIGHT"
                                        momentumAngle >= 67.5f && momentumAngle < 112.5f -> "DOWN"
                                        momentumAngle >= 112.5f && momentumAngle < 157.5f -> "DOWN-LEFT"
                                        momentumAngle >= 157.5f || momentumAngle < -157.5f -> "LEFT"
                                        momentumAngle >= -157.5f && momentumAngle < -112.5f -> "UP-LEFT"
                                        momentumAngle >= -112.5f && momentumAngle < -67.5f -> "UP"
                                        else -> "UP-RIGHT"
                                    }
                                    val releasePosition = spriteData.spriteState.position
                                    val totalDragDistance = kotlin.math.sqrt(
                                        cumulativeDragAmount.x * cumulativeDragAmount.x + 
                                        cumulativeDragAmount.y * cumulativeDragAmount.y
                                    )
                                    Log.d("DragGesture", "Sprite RELEASED - Type: ${spriteData.spriteType}, Position: (${releasePosition.x.toInt()}, ${releasePosition.y.toInt()}), Total Drag Distance: ${totalDragDistance.toInt()}px, Momentum: ${momentumMagnitude.toInt()} px/frame, Velocity: (${cappedVelocity.x.toInt()}, ${cappedVelocity.y.toInt()}), Direction: ${momentumAngle.toInt()}° ($momentumDirection)")
                                }
                                
                                // Clear dragging state (both global and local)
                                setCurrentlyDraggedSprite(null)
                                localDraggedSpriteState = null
                                lastDragPosition = null
                                lastDragTime = 0L
                                recentDragMovements = emptyList()
                                dragStartPosition = null
                                cumulativeDragAmount = Offset.Zero
                            } else {
                                if (ENABLE_DRAG_LOGS) {
                                    Log.d("DragGesture", "onDragEnd SKIPPED - Type: ${spriteData.spriteType}, State mismatch - currentlyDraggedSprite != spriteState")
                                }
                            }
                        },
                        onDragCancel = {
                            if (ENABLE_DRAG_LOGS) {
                                Log.d("DragGesture", "onDragCancel CALLED - Type: ${spriteData.spriteType}, currentlyDraggedSprite == spriteState: ${currentlyDraggedSprite == spriteData.spriteState}, localDraggedSpriteState == spriteState: ${localDraggedSpriteState == spriteData.spriteState}")
                            }
                            // Clear dragging state on cancel as well (check local state first)
                            val wasDragged = localDraggedSpriteState == spriteData.spriteState || currentlyDraggedSprite == spriteData.spriteState
                            if (wasDragged) {
                                if (ENABLE_DRAG_LOGS) {
                                    Log.d("DragGesture", "Sprite DRAG CANCELLED - Type: ${spriteData.spriteType}, Position: (${spriteData.spriteState.position.x.toInt()}, ${spriteData.spriteState.position.y.toInt()})")
                                }
                                setCurrentlyDraggedSprite(null)
                                localDraggedSpriteState = null
                                lastDragPosition = null
                                lastDragTime = 0L
                                recentDragMovements = emptyList()
                                dragStartPosition = null
                                cumulativeDragAmount = Offset.Zero
                            }
                        }
                    )
                }
        )
    }
}
