# Bitcoin Price Visualizer

An interactive Android application that displays real-time Bitcoin prices from Binance and Coinbase exchanges with animated sprite-based visualization.

## Features

### Real-Time Price Display
- **Dual Exchange Support**: Fetches live Bitcoin prices from both Binance and Coinbase
- **Volume Tracking**: Monitors buy and sell volumes from both exchanges
- **Dynamic Updates**: Real-time price and volume updates via WebSocket connections

### Interactive Sprite System
- **Bitcoin Sprites**: 
  - Orange Bitcoin sprites (Binance-controlled)
  - White Bitcoin sprites (Coinbase-controlled)
  - Draggable and interactive
  - Clone spawning mechanics with cooldown system
  
- **Fiat USD Sprites**: 
  - Spawn when sell volume reaches 50% or higher
  - Shrink after Bitcoin collisions (up to 4 collisions)
  - Cooldown system for shrink functionality

- **Cat Sprite**: 
  - Animated cat that roams the screen
  - 4-directional animations (up, down, left, right)
  - Random starting direction on app launch
  - Unaffected by collisions with other sprites
  - Smooth 2-frame animations for each direction

### Physics & Interactions
- **Collision Detection**: Sprites bounce off each other and screen boundaries
- **Drag & Fling**: Interactive sprites can be dragged and flung across the screen
- **Velocity Preservation**: User-applied velocities are maintained
- **Boundary Bouncing**: Sprites reflect off screen edges

### Visual Features
- **Volume Bars**: Animated volume indicators for buy/sell volumes
- **Price Display**: Real-time price updates with visual feedback
- **Material Design 3**: Modern UI with Material 3 components
- **Dark Theme Support**: Built-in dark theme compatibility

## Technical Details

### Architecture
- **Framework**: Jetpack Compose
- **Language**: Kotlin
- **Minimum SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **Networking**: Retrofit 2 with OkHttp
- **API Integration**: 
  - Binance Testnet API
  - Coinbase API
  - Coinbase Exchange API

### Key Components
- `MainActivity.kt`: Main activity with sprite physics and animation logic
- `PriceRepository.kt`: Handles API calls to exchanges
- `PriceDisplay.kt`: UI components for price and volume display
- `VolumeBar.kt`: Volume visualization components

### Sprite System
- **Sprite Types**: Bitcoin (Orange/Blue), Fiat USD, Cat
- **Animation System**: Frame-based animations with configurable delays
- **Collision System**: Physics-based collision detection and response
- **Clone System**: Sprites can spawn clones with cooldown timers

## Getting Started

### Prerequisites
- Android Studio Hedgehog or later
- Android SDK 24 or higher
- Gradle 8.13.2 or compatible version
- Kotlin 1.9.22

### Installation

1. Clone the repository:ash
git clone https://github.com/VirtusVerbis/bitcoin-price-visualizer.git
cd bitcoin-price-visualizer
