package com.example.photofeed.data

object MediaKey {
    fun build(id: Long, dateTaken: Long, size: Long): String = "$id:$dateTaken:$size"
}
