@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.safeshe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.telephony.SmsManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.example.safeshe.ui.theme.SafeSheTheme
import com.google.android.gms.location.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.graphics.graphicsLayer


class MainActivity : ComponentActivity() {

    private lateinit var smsManager: SmsManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var shakeStartTime: Long = 0
    private var shaking = false
    private var contactsList = mutableStateListOf<String>()
    private val PREFS_NAME = "SafeShePrefs"
    private val CONTACTS_KEY = "contacts"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        smsManager = SmsManager.getDefault()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        loadContacts()

        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (!allGranted) {
                Toast.makeText(this, "All permissions are required", Toast.LENGTH_LONG).show()
            }
        }

        requestPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.SEND_SMS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )

        setContent {
            SafeSheTheme {
                val snackbarHostState = remember { SnackbarHostState() }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("SafeShe", fontSize = 22.sp) },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                titleContentColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { paddingValues ->
                    EmergencyContactsScreen(
                        modifier = Modifier.padding(paddingValues),
                        snackbarHostState = snackbarHostState
                    )
                }
            }
        }
    }

    @Composable
    fun EmergencyContactsScreen(
        modifier: Modifier = Modifier,
        snackbarHostState: SnackbarHostState
    ) {
        var currentNumber by remember { mutableStateOf("") }

        // Pulse animation for SOS button
        val infiniteTransition = rememberInfiniteTransition()
        val scale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.1f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            )
        )

        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Enter up to 4 emergency numbers", fontSize = 20.sp)

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = currentNumber,
                onValueChange = { currentNumber = it },
                placeholder = { Text("Enter 10-digit mobile number") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    if (currentNumber.length == 10 && contactsList.size < 4) {
                        contactsList.add(currentNumber)
                        saveContacts()
                        currentNumber = ""
                    }
                },
                enabled = contactsList.size < 4,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add Number")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Display contacts as rounded cards
            contactsList.forEachIndexed { index, number ->
                Card(
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${index + 1}. $number", fontSize = 18.sp)
                        IconButton(
                            onClick = {
                                contactsList.removeAt(index)
                                saveContacts()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { sendSOS(contactsList) },
                modifier = Modifier
                    .size(200.dp)
                    .graphicsLayer(scaleX = scale, scaleY = scale),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
            ) {
                Text("SOS", fontSize = 40.sp, color = Color.White)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Shake feedback
            LaunchedEffect(Unit) {
                snapshotFlow { shaking }.collect { isShaking ->
                    if (isShaking) {
                        snackbarHostState.showSnackbar("Shake detected! Sending SOS...")
                    }
                }
            }
        }
    }

    private fun sendSOS(contacts: List<String>) {
        if (contacts.isEmpty()) return

        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
            || ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
            || ActivityCompat.checkSelfPermission(
                this, Manifest.permission.SEND_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Permissions not granted", Toast.LENGTH_SHORT).show()
            return
        }

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L
        ).setMinUpdateIntervalMillis(500).build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation
                val latitude = location?.latitude
                val longitude = location?.longitude
                val message = if (latitude != null && longitude != null) {
                    "EMERGENCY! I need help. My location: https://maps.google.com/?q=$latitude,$longitude"
                } else {
                    "EMERGENCY! I need help. Location not available."
                }

                contacts.forEach { number ->
                    try {
                        smsManager.sendTextMessage(number, null, message, null, null)
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Failed to send SMS to $number", Toast.LENGTH_SHORT).show()
                    }
                }

                // Vibrate device on SOS
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))

                Toast.makeText(this@MainActivity, "SOS Sent!", Toast.LENGTH_SHORT).show()
                fusedLocationClient.removeLocationUpdates(this)
            }
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
    }

    private fun saveContacts() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(CONTACTS_KEY, contactsList.toSet()).apply()
    }

    private fun loadContacts() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(CONTACTS_KEY, emptySet())
        contactsList.clear()
        contactsList.addAll(saved ?: emptySet())
    }

    // Gentle shake detection
    private val shakeListener = object : SensorEventListener {
        private var lastX = 0f
        private var lastY = 0f
        private var lastZ = 0f

        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val deltaX = kotlin.math.abs(x - lastX)
            val deltaY = kotlin.math.abs(y - lastY)
            val deltaZ = kotlin.math.abs(z - lastZ)

            lastX = x
            lastY = y
            lastZ = z

            val totalMovement = deltaX + deltaY + deltaZ
            val currentTime = System.currentTimeMillis()

            if (totalMovement > 4) { // gentle movement threshold
                if (!shaking) {
                    shaking = true
                    shakeStartTime = currentTime
                } else if (currentTime - shakeStartTime > 1000) {
                    sendSOS(contactsList)
                    shaking = false
                }
            } else {
                shaking = false
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.also { sensor ->
            sensorManager.registerListener(shakeListener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(shakeListener)
    }
}
