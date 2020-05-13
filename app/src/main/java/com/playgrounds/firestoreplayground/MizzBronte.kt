package com.playgrounds.firestoreplayground

import android.content.Context
import kotlin.random.Random

class MizzBronte(context: Context) {
    private val rand = Random(System.nanoTime())
    init {
        asWords = if (asWords.isEmpty())
            context.assets.open("wutheringHeights.txt").bufferedReader().readText().split("\\s+".toRegex()).toTypedArray()
        else asWords
    }

    fun get(words: Int) = rand.nextInt(asWords.size - words).let { it until (it+words) }.joinToString(" "){ asWords[it] }
    companion object {
        var asWords: Array<String> = arrayOf()
    }
}