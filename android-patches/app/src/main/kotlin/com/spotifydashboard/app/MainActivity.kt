package com.spotifydashboard.app

import android.os.Bundle
import com.getcapacitor.BridgeActivity
import com.getcapacitor.community.database.sqlite.CapacitorSQLite
import app.tauri.filepicker.FilePickerPlugin

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(CapacitorSQLite::class.java)
        registerPlugin(FilePickerPlugin::class.java)
        // Browser, App, Preferences auto-register via @CapacitorPlugin annotation
        super.onCreate(savedInstanceState)
    }
}
