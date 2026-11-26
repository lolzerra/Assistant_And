package fr.maison.assistant

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/** Activity invisible : autorisation Bluetooth si besoin, puis la fenêtre d'Android « Activer le Bluetooth ? ». */
class BluetoothActivity : ComponentActivity() {
    private val enable = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        done(r.resultCode == Activity.RESULT_OK)
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) askEnable() else done(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return  // recréée pendant qu'une fenêtre est ouverte : la réponse arrivera
        if (NavMode.pendingBluetooth == null) return finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        else askEnable()
    }

    private fun askEnable() {
        runCatching { enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }.onFailure {
            Journal.log("bluetooth : fenêtre refusée (${it.message})")
            done(false)
        }
    }

    private fun done(ok: Boolean) {
        NavMode.pendingBluetooth?.complete(ok)
        finish()
    }
}
