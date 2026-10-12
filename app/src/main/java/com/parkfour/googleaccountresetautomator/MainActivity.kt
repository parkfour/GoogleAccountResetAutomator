package com.parkfour.googleaccountresetautomator

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class MainActivity : ComponentActivity() {

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results: Map<String, Boolean> ->
        if (results[Manifest.permission.BLUETOOTH_CONNECT] == false) {
            Toast.makeText(this, "Bluetooth permission required to select car.", Toast.LENGTH_LONG).show()
        }
        if (results[Manifest.permission.POST_NOTIFICATIONS] == false) {
            Toast.makeText(this, "Notification permission required to see switch prompts.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val missingPermissions = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
        ) {
            missingPermissions += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            missingPermissions += Manifest.permission.POST_NOTIFICATIONS
        }
        if (missingPermissions.isNotEmpty()) {
            requestPermissionsLauncher.launch(missingPermissions.toTypedArray())
        }

        ContextCompat.startForegroundService(this, Intent(this, DockWatcherService::class.java))

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

/**
 * Accessibility services have no runtime-permission dialog; the only way to grant one
 * is the user flipping it on under Settings > Accessibility, so we just report status here.
 */
private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = ComponentName(context, AccountSwitchAccessibilityService::class.java).flattenToString()
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val splitter = TextUtils.SimpleStringSplitter(':')
    splitter.setString(enabledServices)
    while (splitter.hasNext()) {
        if (splitter.next().equals(expected, ignoreCase = true)) return true
    }
    return false
}

@Composable
fun MainScreen() {
    val context = LocalContext.current

    val sharedPrefs = remember { context.getSharedPreferences("AutomatorPrefs", Context.MODE_PRIVATE) }
    var savedDeviceName by remember { mutableStateOf(sharedPrefs.getString("TARGET_BT_NAME", "No device selected") ?: "No device selected") }

    // Primary Email Persistence State variables
    var primaryEmailInput by remember { mutableStateOf(sharedPrefs.getString("PRIMARY_EMAIL", "") ?: "") }

    var accessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }

    // Refresh the status when the user comes back from Settings > Accessibility.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled = isAccessibilityServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    var expanded by remember { mutableStateOf(false) }
    var pairedDevices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Automator Dashboard",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "Engine: Accessibility Service Automation",
            fontSize = 14.sp,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // --- ACCESSIBILITY SERVICE STATUS CARD ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (accessibilityEnabled)
                    MaterialTheme.colorScheme.surfaceVariant
                else
                    MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (accessibilityEnabled) "Accessibility Service: Enabled" else "Accessibility Service: Not Enabled",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (accessibilityEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                if (!accessibilityEnabled) {
                    Text(
                        text = "Required to automate switching the Google app back to your primary account. Android only lets you grant this from Settings.",
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )
                    OutlinedButton(
                        onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open Accessibility Settings")
                    }
                }
            }
        }

        // --- PRIMARY EMAIL CONFIGURATION CARD ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Primary Account Target Email:", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = primaryEmailInput,
                    onValueChange = { newValue ->
                        primaryEmailInput = newValue
                        sharedPrefs.edit().putString("PRIMARY_EMAIL", newValue).apply()
                    },
                    placeholder = { Text("example@gmail.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // --- BLUETOOTH SELECTOR UI ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Target Car Bluetooth:", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                Text(text = savedDeviceName, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))

                Box {
                    OutlinedButton(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
                                pairedDevices = bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
                                expanded = true
                            } else {
                                Toast.makeText(context, "Missing Bluetooth Permissions", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Select Target Device ▼")
                    }

                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.fillMaxWidth(0.8f)
                    ) {
                        if (pairedDevices.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No paired devices found") },
                                onClick = { expanded = false }
                            )
                        } else {
                            pairedDevices.forEach { device ->
                                @Suppress("MissingPermission")
                                val deviceName = device.name ?: "Unknown Device"
                                @Suppress("MissingPermission")
                                val deviceAddress = device.address

                                DropdownMenuItem(
                                    text = { Text(deviceName) },
                                    onClick = {
                                        savedDeviceName = deviceName
                                        sharedPrefs.edit()
                                            .putString("TARGET_BT_NAME", deviceName)
                                            .putString("TARGET_BT_ADDRESS", deviceAddress)
                                            .apply()
                                        expanded = false
                                        Toast.makeText(context, "Saved: $deviceName", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(bottom = 32.dp))

        // --- DEBUG: MANUALLY TRIGGER THE ACCOUNT SWITCH ---
        Button(
            onClick = {
                if (primaryEmailInput.isEmpty()) {
                    Toast.makeText(context, "Please set a target email first", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                val started = AccountSwitchAccessibilityService.switchToPrimaryAccount(
                    context,
                    primaryEmailInput,
                    restartAndroidAuto = true
                )
                if (started) {
                    Toast.makeText(context, "Switching to primary account...", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Accessibility Service isn't enabled yet.", Toast.LENGTH_LONG).show()
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE)),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Manual Trigger", color = Color.White, fontSize = 16.sp)
        }
    }
}