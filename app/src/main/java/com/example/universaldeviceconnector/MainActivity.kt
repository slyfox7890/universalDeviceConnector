@@
     override fun onCreate(savedInstanceState: Bundle?) {
         super.onCreate(savedInstanceState)
         if (checkKillSwitch()) { corruptApp(); return }
-        loadCache(); loadNetworkDevices(); setContentView(R.layout.activity_main)
-        val btAdapter = BluetoothAdapter.getDefaultAdapter()
-        if (btAdapter == null) Toast.makeText(this, "Bluetooth not available", Toast.LENGTH_LONG).show()
-        bluetoothAdapter = btAdapter ?: BluetoothAdapter.getDefaultAdapter()!!
-        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
-        tabLayout = findViewById(R.id.tabLayout); viewPager = findViewById(R.id.viewPager)
-        statusText = findViewById(R.id.statusText); deviceCountText = findViewById(R.id.deviceCount)
-        adapter = DevicePagerAdapter(); viewPager.adapter = adapter; tabLayout.setupWithViewPager(viewPager)
-        if (BuildConfig.DEBUG) { logEvent("Phone-only dev mode: Auth bypassed"); checkPermissionsAndStartScans() }
-        else authenticateUser()
+        loadCache(); loadNetworkDevices(); setContentView(R.layout.activity_main)
+
+        // Safe Bluetooth adapter handling: only assign when non-null; avoid a !! on a possibly-null value.
+        val btAdapter = BluetoothAdapter.getDefaultAdapter()
+        if (btAdapter == null) {
+            Toast.makeText(this, "Bluetooth not available", Toast.LENGTH_LONG).show()
+        } else {
+            bluetoothAdapter = btAdapter
+        }
+
+        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
+        tabLayout = findViewById(R.id.tabLayout); viewPager = findViewById(R.id.viewPager)
+        statusText = findViewById(R.id.statusText); deviceCountText = findViewById(R.id.deviceCount)
+        adapter = DevicePagerAdapter(); viewPager.adapter = adapter; tabLayout.setupWithViewPager(viewPager)
+
+        // Defer starting scans / authentication until after the ViewPager has been laid out so instantiateItem() runs
+        // and the adapter's lateinit views are initialized. This prevents UninitializedPropertyAccessException.
+        viewPager.post {
+            if (BuildConfig.DEBUG) {
+                logEvent("Phone-only dev mode: Auth bypassed")
+                checkPermissionsAndStartScans()
+            } else {
+                authenticateUser()
+            }
+        }
     }
@@
-        fun startBluetoothScan(){if(!::bluetoothAdapter.isInitialized||!bluetoothAdapter.isEnabled){Toast.makeText(this@MainActivity,"Bluetooth disabled",Toast.LENGTH_LONG).show();return}
-            Toast.makeText(this@MainActivity,"Scanning BT…",Toast.LENGTH_SHORT).show();logEvent("BT scan started");updateStatusBar("Scanning BT…",0)
-            btDevices.clear();btDisplayList.clear();btAdapter.notifyDataSetChanged();if(bluetoothAdapter.isDiscovering)bluetoothAdapter.cancelDiscovery();bluetoothAdapter.startDiscovery()
-            try{registerReceiver(btReceiver,IntentFilter(BluetoothDevice.ACTION_FOUND))}catch (_: Exception) {};bluetoothAdapter.bondedDevices.forEach{addBluetoothDevice(it,true)}}
+        fun startBluetoothScan(){
+            // Defensive guards: ensure the bluetooth adapter exists and this view's UI pieces were initialized.
+            if (!::btListView.isInitialized || !::btAdapter.isInitialized) {
+                Toast.makeText(this@MainActivity, "Bluetooth UI not ready", Toast.LENGTH_SHORT).show()
+                return
+            }
+            if (!::bluetoothAdapter.isInitialized || !bluetoothAdapter.isEnabled) {
+                Toast.makeText(this@MainActivity, "Bluetooth disabled", Toast.LENGTH_LONG).show(); return
+            }
+            Toast.makeText(this@MainActivity,"Scanning BT…",Toast.LENGTH_SHORT).show();logEvent("BT scan started");updateStatusBar("Scanning BT…",0)
+            btDevices.clear();btDisplayList.clear();btAdapter.notifyDataSetChanged()
+            if (bluetoothAdapter.isDiscovering) bluetoothAdapter.cancelDiscovery()
+            bluetoothAdapter.startDiscovery()
+            try { registerReceiver(btReceiver, IntentFilter(BluetoothDevice.ACTION_FOUND)) } catch (_: Exception) {}
+            bluetoothAdapter.bondedDevices.forEach { addBluetoothDevice(it, true) }
+        }
