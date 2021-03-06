package nl.quantifiedstudent.watch.activity

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import nl.quantifiedstudent.watch.adapter.BluetoothScanResultAdapter
import nl.quantifiedstudent.watch.databinding.ActivityBluetoothPairBinding
import nl.quantifiedstudent.watch.extensions.toMap
import nl.quantifiedstudent.watch.protocol.BluetoothProtocolCollection
import javax.inject.Inject

@ExperimentalUnsignedTypes
@SuppressLint("MissingPermission", "TODO")
@AndroidEntryPoint
class BluetoothPairActivity : AppCompatActivity() {
    @Inject
    lateinit var protocolCollection: BluetoothProtocolCollection

    private lateinit var binding: ActivityBluetoothPairBinding

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val bluetoothActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK && !bluetoothAdapter.isEnabled) {
            launchBluetoothActivity()
        }
    }

    private val bluetoothLowEnergyScanner by lazy { bluetoothAdapter.bluetoothLeScanner }
    private val bluetoothScanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
    private val bluetoothScanResults = mutableListOf<ScanResult>()

    private val bluetoothScanResultAdapter: BluetoothScanResultAdapter by lazy {
        BluetoothScanResultAdapter(bluetoothScanResults) { bluetoothDevice, scanRecord ->
            if (bluetoothDevice == null || scanRecord == null) return@BluetoothScanResultAdapter

            bluetoothLowEnergyScanner.stopScan(bluetoothScanCallback)

            val manufacturerSpecificData = scanRecord.manufacturerSpecificData.toMap()
            val protocol = protocolCollection.determineProtocol(manufacturerSpecificData)

            if (protocol is BluetoothGattCallback) {
                Log.i("BluetoothScanResultCallback", "Connecting with device ${bluetoothDevice.name}")
                bluetoothDevice.connectGatt(this@BluetoothPairActivity, false, protocol)
            } else {
                Log.e("BluetoothScanResultCallback", "Unable to connect with device ${bluetoothDevice.name}")
            }
        }
    }

    private val bluetoothScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val index = bluetoothScanResults.indexOfFirst { it.device.address == result.device.address }
            if (index != -1) {
                bluetoothScanResults[index] = result
                bluetoothScanResultAdapter.notifyItemChanged(index)
            } else {
                Log.i("BluetoothScanCallback", "Device, Name: ${result.device.name ?: "Unnamed"}, address: ${result.device.address}")

                bluetoothScanResults.add(result)
                bluetoothScanResultAdapter.notifyItemInserted(bluetoothScanResults.size - 1)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBluetoothPairBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestPermissions(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            ACCESS_FINE_LOCATION_REQUEST_CODE
        )

        binding.bluetoothScanResultRecyclerView.apply {
            adapter = bluetoothScanResultAdapter
            layoutManager = LinearLayoutManager(
                this@BluetoothPairActivity,
                RecyclerView.VERTICAL,
                false
            )
            isNestedScrollingEnabled = false
        }
    }

    override fun onResume() {
        super.onResume()

        if (!bluetoothAdapter.isEnabled) {
            launchBluetoothActivity()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != ACCESS_FINE_LOCATION_REQUEST_CODE) return
        if (grantResults.isEmpty() || grantResults[0] == PackageManager.PERMISSION_DENIED) return finishAffinity()

        startScanner()
    }

    private fun startScanner() {
        val filters = protocolCollection.buildScanFilters()

        bluetoothLowEnergyScanner.startScan(
            filters,
            bluetoothScanSettings,
            bluetoothScanCallback
        )
    }

    private fun launchBluetoothActivity() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        bluetoothActivityLauncher.launch(intent)
    }

    companion object {
        private const val ACCESS_FINE_LOCATION_REQUEST_CODE = 2
    }
}