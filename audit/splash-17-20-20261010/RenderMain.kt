package com.yfuse.app
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import com.yfuse.core.designsystem.SplashAnimation
import java.io.File

internal class WaterFireArtwork(root: String) {
    val full = BitmapFactory.decodeFile("$root/water_fire_logo.png")!!
    val layers = listOf("blue", "orange", "gold").map { BitmapFactory.decodeFile("$root/water_fire_$it.png")!! }
    val domino = File(root, "water_fire_domino.csv").readLines().map {
        val a = it.split(',')
        CraftDot(40f + a[0].toFloat() * 4.32f, 40f + a[1].toFloat() * 4.32f, (0xFF000000 or a[2].toLong(16)).toInt())
    }
}

fun main(args: Array<String>) {
    println("Loading artwork")
    val art = WaterFireArtwork(args[0])
    println("Artwork loaded")
    val output = File(args[0], "frames").apply { mkdirs() }
    val variants = listOf(SplashAnimation.Hologram, SplashAnimation.Marble, SplashAnimation.Fan, SplashAnimation.Domino)
    for (variant in variants) {
        println("Creating renderer: $variant")
        val renderer = WaterFireMechanisms(art, variant)
        println("Creating bitmap")
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        println("Drawing: $variant")
        for (time in 0..2200 step 50) {
            bitmap.eraseColor(0)
            renderer.draw(canvas, time.toFloat())
            File(output, "${variant.name}-$time.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        println("Rendered ${variant.name}: 45 frames")
        bitmap.recycle()
    }
}
