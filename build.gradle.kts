plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP 9 compiles Kotlin itself; this only pins which Kotlin it uses.
    // GeckoView 158 and androidx core 1.19 are built with Kotlin 2.4.
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
}
