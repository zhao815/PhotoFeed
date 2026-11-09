package com.example.photofeed.data

import java.util.Random

class ShuffleEngine(private val windowSize: Int = 300) {

    fun shuffle(items: List<MediaItemUi>, seed: Long, recentKeys: List<String>): List<MediaItemUi> {
        if (items.isEmpty()) return emptyList()

        val shuffled = items.toMutableList()
        val random = Random(seed)
        for (i in shuffled.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val tmp = shuffled[i]
            shuffled[i] = shuffled[j]
            shuffled[j] = tmp
        }

        val window = recentKeys.takeLast(windowSize).toHashSet()
        if (window.isEmpty()) return shuffled

        val fresh = ArrayList<MediaItemUi>(shuffled.size)
        val seenRecently = ArrayList<MediaItemUi>()
        for (item in shuffled) {
            if (window.contains(item.mediaKey)) seenRecently.add(item) else fresh.add(item)
        }
        return fresh + seenRecently
    }
}
