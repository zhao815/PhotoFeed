package com.example.photofeed

import android.app.Application
import com.example.photofeed.data.NoteStore
import com.example.photofeed.db.AppDatabase

class App : Application() {
    val db: AppDatabase by lazy { AppDatabase.get(this) }

    override fun onCreate() {
        super.onCreate()
        NoteStore.init(applicationContext)
    }
}
