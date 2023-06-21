package com.example.estufaapp

import android.content.Intent
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {
    var REQUEST_ENABLE_BT = 1
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.requestFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        /*if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name: CharSequence = "Estufa"
            val description = "RECARGA_Y_ACOMODA_LEÑA"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel("Estufa", name, importance)
            channel.description = description
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            val notificationManager = getSystemService(
                NotificationManager::class.java
            )
            notificationManager.createNotificationChannel(channel)
        }*/

        setContentView(R.layout.activity_splash)
        getSupportActionBar()!!.setTitle("")

        /*   val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            // Device doesn't support Bluetooth
        }

        if (bluetoothAdapter?.isEnabled == false) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
        }*/

        CoroutineScope(Dispatchers.IO).launch {
            delay(4000)
            val intent = Intent(this@SplashActivity, MainActivity::class.java)
            // intent.putExtra("firesJetson", firesJetson)
            // intent.putExtra("comm", comm)
            startActivity(intent)
            finish()
        }
    }
}
