package com.example.universaldeviceconnector

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "UDCApp"
        private const val APP_AUTHORIZED_USER = "admin"
        private const val SECURE_PASS_HASH = "098f6bcd4621d373cade4e832627b4f6"
        private const val APP_MODE = "production"
        private const val KILL_SWITCH_FILE = "kill.flag"
        private val SUPPORTED_CATEGORIES = listOf(
            "vending machine", "atm", "vehicle", "router", "printer", "iot sensor",
            "appliance", "camera", "speaker", "smart display", "hvac", "cnc",
            "label printer", "usb device", "serial device", "pos terminal",
            "kiosk", "scanner", "medical device", "industrial controller"
        )
        private const val ONLINE_LOOKUP_API = "https://api.macvendors.com/"
        private val COMMON_NETWORK_RANGES = listOf(
            "192.168.1.0/24", "192.168.0.0/24", "10.0.0.0/24", "172.16.0.0/24"
        )
        private val COMMON_PORTS = listOf(22, 23, 80, 443, 21, 25, 53, 110, 143, 993, 995)
        private val DEVICE_SIGNATURES = mapOf(
            "printer" to listOf("hp", "canon", "epson", "brother", "lexmark", "xerox"),
            "router" to listOf("linksys", "netgear", "cisco", "asus", "tp-link", "d-link"),
            "camera" to listOf("axis", "hikvision", "dahua", "bosch", "sony", "panasonic"),
            "pos" to listOf("verifone", "ingenico", "pax", "square", "clover"),
            "atm" to listOf("ncr", "diebold", "wincor", "hitachi", "fujitsu"),
            "iot" to listOf("arduino", "raspberry", "esp8266", "esp32", "particle")
        )
    }

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var wifiManager: WifiManager
    private val deviceCache = mutableMapOf<String, DeviceInfo>()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainScope = CoroutineScope(Dispatchers.Main)
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager
    private lateinit var adapter: DevicePagerAdapter
    private lateinit var statusText: TextView
    private lateinit var deviceCountText: TextView
    private val logFileName = "log_${APP_MODE}.log"
    private val debugFileName = "debug_${APP_MODE}.log"
    private val cacheFileName = "device_cache.json"
    private val networkDevicesFile = "network_devices.json"

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.all { it.value }) startAllScans()
        else Toast.makeText(this, "Required permissions denied", Toast.LENGTH_SHORT).show()
    }

    data class DeviceInfo(
        val name: String, val address: String, val vendor: String = "Unknown",
        val category: String = "Unknown", val ports: List<Int> = emptyList(),
        val services: List<String> = emptyList(), val compatibility: String = "Unknown",
        val lastSeen: Long = System.currentTimeMillis(), val isActive: Boolean = false
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkKillSwitch()) { corruptApp(); return }
        loadCache(); loadNetworkDevices(); setContentView(R.layout.activity_main)
        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        if (btAdapter == null) Toast.makeText(this, "Bluetooth not available", Toast.LENGTH_LONG).show()
        bluetoothAdapter = btAdapter ?: BluetoothAdapter.getDefaultAdapter()!!
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        tabLayout = findViewById(R.id.tabLayout); viewPager = findViewById(R.id.viewPager)
        statusText = findViewById(R.id.statusText); deviceCountText = findViewById(R.id.deviceCount)
        adapter = DevicePagerAdapter(); viewPager.adapter = adapter; tabLayout.setupWithViewPager(viewPager)
        if (BuildConfig.DEBUG) { logEvent("Phone-only dev mode: Auth bypassed"); checkPermissionsAndStartScans() }
        else authenticateUser()
    }

    private fun checkKillSwitch(): Boolean {
        if (BuildConfig.DEBUG) return false
        val f = File(filesDir, KILL_SWITCH_FILE)
        if (f.exists()) { logEvent("Kill switch detected"); return true }; return false
    }

    private fun corruptApp() {
        logEvent("Kill switch triggered")
        try { listOf(logFileName, debugFileName, cacheFileName, networkDevicesFile).forEach { File(filesDir, it).delete() }; deviceCache.clear() }
        catch (e: Exception) { Log.e(TAG, "Corrupt error", e) }; finishAffinity()
    }

    private fun authenticateUser() {
        val input = EditText(this); input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        AlertDialog.Builder(this).setTitle("Authentication Required").setMessage("Enter password for user \"$APP_AUTHORIZED_USER\":")
            .setView(input).setCancelable(false).setPositiveButton("OK") { _, _ ->
                val pw = input.text.toString()
                if (md5(pw) == SECURE_PASS_HASH) { logEvent("Authenticated"); checkPermissionsAndStartScans() }
                else if (APP_MODE == "experimental") {
                    AlertDialog.Builder(this).setTitle("Bypass Permission").setMessage("Verbal permission granted? (yes)")
                        .setCancelable(false).setPositiveButton("Yes") { _, _ -> logEvent("Bypass granted"); checkPermissionsAndStartScans() }
                        .setNegativeButton("No") { _, _ -> logEvent("Bypass rejected"); finish() }.show()
                } else { logEvent("Auth failed"); Toast.makeText(this, "Access denied", Toast.LENGTH_LONG).show(); finish() }
            }.show()
    }

    private fun md5(input: String): String {
        return MessageDigest.getInstance("MD5").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun loadCache() {
        try { val f = File(filesDir, cacheFileName); if (f.exists()) {
            val j = JSONObject(f.readText()); j.keys().forEach { k -> val d = j.getJSONObject(k)
                deviceCache[k] = DeviceInfo(d.optString("name","Unknown"), d.optString("address",""), d.optString("vendor","Unknown"), d.optString("category","Unknown"), compatibility=d.optString("compatibility","Unknown"), lastSeen=d.optLong("lastSeen",System.currentTimeMillis()))
            }; logDebug("Cache loaded: ${deviceCache.size}") }
        } catch (e: Exception) { logDebug("Cache load failed: ${e.message}") }
    }

    private fun loadNetworkDevices() {
        try { val f = File(filesDir, networkDevicesFile); if (f.exists()) {
            val j = JSONObject(f.readText()); j.keys().forEach { k -> val d = j.getJSONObject(k)
                deviceCache["net_$k"] = DeviceInfo(d.optString("name",k), d.optString("address",k), d.optString("vendor","Unknown"), d.optString("category","Unknown"), compatibility=d.optString("compatibility","Unknown"), lastSeen=d.optLong("lastSeen",System.currentTimeMillis()), isActive=false)
            }; logDebug("Network cache loaded: ${j.length()}") }
        } catch (e: Exception) { logDebug("Network load failed: ${e.message}") }
    }

    private fun saveNetworkDevices() {
        try { val j = JSONObject(); adapter.networkDevices.forEach { d ->
            val o = JSONObject().apply { put("name",d.name); put("address",d.address); put("vendor",d.vendor); put("category",d.category); put("compatibility",d.compatibility); put("lastSeen",d.lastSeen) }
            j.put(d.address, o) }; File(filesDir, networkDevicesFile).writeText(j.toString(2)); logDebug("Network saved: ${j.length()}")
        } catch (e: Exception) { logDebug("Network save failed: ${e.message}") }
    }

    private fun saveCache() {
        try { val j = JSONObject(); deviceCache.entries.forEach { (k,d) ->
            val o = JSONObject().apply { put("name",d.name); put("address",d.address); put("vendor",d.vendor); put("category",d.category); put("compatibility",d.compatibility); put("lastSeen",d.lastSeen) }
            j.put(k, o) }; File(filesDir, cacheFileName).writeText(j.toString(2)); logDebug("Cache saved: ${deviceCache.size}")
        } catch (e: Exception) { logDebug("Cache save failed: ${e.message}") }
    }

    private fun checkPermissionsAndStartScans() {
        val p = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { p.add(Manifest.permission.BLUETOOTH_SCAN); p.add(Manifest.permission.BLUETOOTH_CONNECT) }
        else { p.add(Manifest.permission.BLUETOOTH); p.add(Manifest.permission.BLUETOOTH_ADMIN) }
        p.addAll(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_WIFI_STATE, Manifest.permission.CHANGE_WIFI_STATE, Manifest.permission.INTERNET))
        val missing = p.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissionsLauncher.launch(missing.toTypedArray()) else startAllScans()
    }

    private fun startAllScans() { adapter.startBluetoothScan(); adapter.startWifiScan(); adapter.startNetworkScan(); adapter.listSerialDevices() }

    private suspend fun lookupOnline(mac: String): String {
        return try { val r = httpClient.newCall(Request.Builder().url("$ONLINE_LOOKUP_API$mac").build()).execute()
            if (r.isSuccessful) r.body?.string() ?: "Unknown" else "Unknown"
        } catch (e: Exception) { logDebug("Lookup failed $mac: ${e.message}"); "Unknown" }
    }

    private fun categorizeDevice(name: String, vendor: String): String {
        val s = "$name $vendor".lowercase(); DEVICE_SIGNATURES.entries.forEach { (c,kw) -> if (kw.any { s.contains(it) }) return c }
        return when { s.contains("print")->"printer"; s.contains("router")||s.contains("access point")->"router"; s.contains("camera")||s.contains("webcam")->"camera"; s.contains("speaker")||s.contains("audio")->"speaker"; s.contains("tv")||s.contains("display")->"smart display"; else->"unknown" }
    }

    private fun suggestAction(name: String, category: String): String = when (category.lowercase()) {
        "printer"->"Check print queue, test page"; "router"->"Check connectivity, admin panel"; "camera"->"Verify stream, check settings"
        "pos","atm"->"Check transaction logs, network"; "iot"->"Verify sensor data, connectivity"; "speaker"->"Test audio output, pairing"; else->"Basic connectivity test"
    }

    private fun checkCompatibility(name: String, category: String): String {
        val s = name.lowercase(); return when (category) {
            "printer"->when { s.contains("hp")||s.contains("canon")->"High compatibility"; s.contains("brother")||s.contains("epson")->"Medium compatibility"; else->"Unknown compatibility" }
            "router"->"Standard network protocols"; "camera"->"RTSP/HTTP streaming"; "pos"->"EMV/NFC protocols"; else->"Standard protocols" }
    }

    private suspend fun performPortScan(ip: String): List<Int> {
        val open = mutableListOf<Int>(); COMMON_PORTS.forEach { p -> try { val s = Socket(); s.connect(InetSocketAddress(ip, p), 1000); open.add(p); s.close(); logDebug("Open port: $ip:$p") } catch (_: Exception) {} }; return open
    }

    private suspend fun isHostReachable(ip: String, timeoutMs: Int): Boolean {
        for (p in listOf(80,443,22,445)) { try { val s = Socket(); s.connect(InetSocketAddress(ip, p), timeoutMs); s.close(); return true } catch (_: Exception) {} }
        return try { InetAddress.getByName(ip).isReachable(timeoutMs) } catch (_: Exception) { false }
    }

    private fun identifyService(port: Int): String = when (port) {
        21->"FTP"; 22->"SSH"; 23->"Telnet"; 25->"SMTP"; 53->"DNS"; 80->"HTTP"; 110->"POP3"; 143->"IMAP"; 443->"HTTPS"; 445->"SMB"
        993->"IMAPS"; 995->"POP3S"; 3389->"RDP"; 5900->"VNC"; 8080->"HTTP-Alt"; 8443->"HTTPS-Alt"; else->"Service($port)"
    }

    private fun categorizeByPorts(ports: List<Int>): String = when {
        ports.contains(80)&&ports.contains(443)&&ports.contains(53)->"router"; ports.contains(23)&&ports.contains(80)->"iot"
        ports.contains(22)&&ports.contains(80)->"router"; ports.contains(9100)->"printer"; ports.contains(5492)||ports.contains(631)->"printer"
        ports.contains(554)->"camera"; ports.contains(8080)||ports.contains(8443)->"kiosk"; ports.contains(80)->"web device"
        ports.contains(22)->"server"; ports.contains(23)->"legacy device"; else->"unknown"
    }

    private fun frequencyToChannel(freq: Int): Int = when { freq==2484->14; freq in 2412..2472->(freq-2412)/5+1; freq in 5180..5825->(freq-5180)/5+36; freq in 5955..7115->(freq-5955)/5+1; else->-1 }

    private fun updateStatusBar(msg: String, count: Int) { mainScope.launch { try { statusText.text=msg; deviceCountText.text="$count devices" } catch (_: Exception) {} } }

    private fun logEvent(text: String) { try { val d=getExternalFilesDir(null)?:filesDir; File(d,logFileName).appendText("[${SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault()).format(Date())}] $text\n"); Log.i(TAG,text) } catch (e: Exception) { Log.e(TAG,"Log failed",e) } }
    private fun logDebug(text: String) { try { val d=getExternalFilesDir(null)?:filesDir; File(d,debugFileName).appendText("[${SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault()).format(Date())}] $text\n"); Log.d(TAG,text) } catch (e: Exception) { Log.e(TAG,"Debug log failed",e) } }

    override fun onDestroy() { super.onDestroy(); ioScope.cancel(); mainScope.cancel()
        try { unregisterReceiver(adapter.btReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(adapter.wifiReceiver) } catch (_: Exception) {}
        saveCache(); saveNetworkDevices(); logEvent("App terminated") }

    private fun runBluetoothDiagnosis(device: BluetoothDevice) {
        logEvent("BT diagnosis: ${device.address}"); updateStatusBar("Diagnosing BT…", 0)
        val ck = device.name ?: device.address; val cached = deviceCache[ck]
        val report = buildString {
            append("📱 BT Diagnosis\n━━━━━━━━━━━━━━━━━━\n\n🏷️ Name: ${device.name?:"Unknown"}\n📍 MAC: ${device.address}\n")
            append("🔗 Bond: ${when(device.bondState){BluetoothDevice.BOND_BONDED->"✅ Bonded";BluetoothDevice.BOND_BONDING->"⏳ Bonding";else->"❌ Not Bonded"}}\n")
            append("📡 Type: ${when(device.type){BluetoothDevice.DEVICE_TYPE_CLASSIC->"Classic";BluetoothDevice.DEVICE_TYPE_LE->"BLE";BluetoothDevice.DEVICE_TYPE_DUAL->"Dual";else->"Unknown"}}\n")
            cached?.let { append("\n📋 Cached:\n  Vendor: ${it.vendor}\n  Category: ${it.category}\n  Compat: ${it.compatibility}\n  Action: ${suggestAction(it.name,it.category)}\n") }
            append("\n💡 ${suggestAction(device.name?:"",cached?.category?:"unknown")}") }
        AlertDialog.Builder(this).setTitle("BT Diagnosis").setMessage(report).setPositiveButton("OK",null)
            .setNeutralButton("🔄 Refresh Vendor") { _,_ -> ioScope.launch {
                val v=lookupOnline(device.address.replace(":","")); val c=categorizeDevice(device.name?:"",v)
                deviceCache[ck]=DeviceInfo(device.name?:"Unknown",device.address,v,c,compatibility=checkCompatibility(device.name?:"",c)); saveCache()
                mainScope.launch { Toast.makeText(this@MainActivity,"Vendor: $v",Toast.LENGTH_SHORT).show() } } }.show()
        updateStatusBar("BT done", 0)
    }

    private fun runWifiDiagnosis(result: ScanResult) {
        logEvent("WiFi diagnosis: ${result.SSID}"); updateStatusBar("Diagnosing WiFi…", 0)
        val sec = when { result.capabilities.contains("WPA3")->"WPA3 ⭐"; result.capabilities.contains("WPA2")->"WPA2 ✅"; result.capabilities.contains("WPA")->"WPA ⚠️"; result.capabilities.contains("WEP")->"WEP 🚨"; else->"Open 🚨" }
        val qual = when { result.level>-50->"⭐⭐⭐⭐⭐ Excellent"; result.level>-60->"⭐⭐⭐⭐ Good"; result.level>-70->"⭐⭐⭐ Fair"; result.level>-80->"⭐⭐ Weak"; else->"⭐ Very Weak" }
        val band = if(result.frequency<3000)"2.4 GHz" else if(result.frequency<5900)"5 GHz" else "6 GHz"
        val ch = frequencyToChannel(result.frequency)
        val recs = buildString { if(result.capabilities.contains("WEP")||!result.capabilities.contains("WPA"))append("🚨 Upgrade to WPA2/WPA3\n"); if(result.level<-70)append("📡 Weak signal – move closer\n"); if(result.frequency<3000)append("ℹ️ 2.4GHz: more interference\n")else append("✅ $band: less congestion\n") }
        val report = buildString { append("📶 WiFi Diagnosis\n━━━━━━━━━━━━━━━━━━\n\n📛 SSID: ${result.SSID.ifBlank{"(Hidden)"}}\n📍 BSSID: ${result.BSSID}\n📊 Signal: ${result.level} dBm\n📈 Quality: $qual\n🔒 Security: $sec\n📻 Band: $band (${result.frequency} MHz)\n📡 Channel: ${if(ch>0)ch else "N/A"}\n⚙️ Caps: ${result.capabilities}\n\n💡 Recommendations:\n$recs") }
        AlertDialog.Builder(this).setTitle("WiFi Diagnosis").setMessage(report).setPositiveButton("OK",null).show()
        updateStatusBar("WiFi done", 0)
    }

    private fun runNetworkDiagnosis(device: DeviceInfo) {
        logEvent("Net diagnosis: ${device.address}"); updateStatusBar("Diagnosing ${device.address}…", 0)
        val pd = AlertDialog.Builder(this).setTitle("⏳ Diagnosing…").setMessage("Scanning ${device.address}").setCancelable(false).create(); pd.show()
        ioScope.launch { val ip=device.address; val pingOk=isHostReachable(ip,2000); val openPorts=if(pingOk)performPortScan(ip)else emptyList(); val services=openPorts.map{identifyService(it)}
            mainScope.launch { pd.dismiss()
                val report = buildString { append("🖥️ Network Diagnosis\n━━━━━━━━━━━━━━━━━━\n\n📍 IP: $ip\n📊 Status: ${if(pingOk)"🟢 Online" else "🔴 Offline"}\n")
                    if(device.vendor!="Unknown")append("🏭 Vendor: ${device.vendor}\n"); if(device.category!="Unknown")append("📂 Category: ${device.category}\n")
                    append("\n🔌 Open Ports (${openPorts.size}/${COMMON_PORTS.size}):\n"); if(openPorts.isEmpty())append("   None detected\n")else openPorts.forEachIndexed{i,p->append("   ${i+1}. Port $p → ${services.getOrElse(i){"?"}} \n")}
                    append("\n💡 ${suggestAction(device.name,device.category)}\n"); if(openPorts.contains(23))append("   ⚠️ Telnet open – migrate to SSH\n"); if(openPorts.contains(80)&&!openPorts.contains(443))append("   ⚠️ HTTP-only – enable HTTPS\n"); if(openPorts.contains(21))append("   ⚠️ FTP open – consider SFTP\n") }
                AlertDialog.Builder(this@MainActivity).setTitle("Network Report").setMessage(report).setPositiveButton("OK",null).show(); updateStatusBar("Net done",0) } }
    }

    inner class DevicePagerAdapter : PagerAdapter() {
        private val tabTitles = arrayOf("Bluetooth","WiFi","Network","Serial")
        private lateinit var bluetoothView:View; private lateinit var wifiView:View; private lateinit var networkView:View; private lateinit var serialView:View
        private lateinit var btListView:ListView; private lateinit var btAdapter:ArrayAdapter<String>; private val btDevices=mutableListOf<BluetoothDevice>(); private val btDisplayList=mutableListOf<String>()
        private lateinit var wifiListView:ListView; private lateinit var wifiAdapter:ArrayAdapter<String>; private val wifiScanResults=mutableListOf<ScanResult>(); private val wifiDisplayList=mutableListOf<String>()
        private lateinit var networkListView:ListView; private lateinit var networkAdapter:ArrayAdapter<String>; val networkDevices=mutableListOf<DeviceInfo>(); private val networkDisplayList=mutableListOf<String>()
        private lateinit var serialListView:ListView; private lateinit var serialAdapter:ArrayAdapter<String>; private val serialDisplayList=mutableListOf<String>()

        override fun instantiateItem(container:android.view.ViewGroup,position:Int):Any { val inflater=LayoutInflater.from(container.context)
            val view=when(position){ 0->{bluetoothView=inflater.inflate(R.layout.device_list_layout,container,false);setupBluetoothView();bluetoothView}; 1->{wifiView=inflater.inflate(R.layout.device_list_layout,container,false);setupWifiView();wifiView}; 2->{networkView=inflater.inflate(R.layout.device_list_layout,container,false);setupNetworkView();networkView}; 3->{serialView=inflater.inflate(R.layout.device_list_layout,container,false);setupSerialView();serialView}; else->View(container.context) }
            container.addView(view); return view }
        override fun getCount():Int=tabTitles.size
        override fun isViewFromObject(view:View,obj:Any):Boolean=view==obj
        override fun getPageTitle(position:Int):CharSequence=tabTitles[position]
        override fun destroyItem(container:android.view.ViewGroup,position:Int,obj:Any){container.removeView(obj as View)}

        private fun setupBluetoothView(){btListView=bluetoothView.findViewById(R.id.listView);btAdapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_list_item_1,btDisplayList);btListView.adapter=btAdapter
            bluetoothView.findViewById<Button>(R.id.refreshButton).setOnClickListener{startBluetoothScan()};bluetoothView.findViewById<Button>(R.id.diagnoseButton).setOnClickListener{val pos=btListView.checkedItemPosition;if(pos==ListView.INVALID_POSITION){Toast.makeText(this@MainActivity,"Select a BT device first",Toast.LENGTH_SHORT).show();return@setOnClickListener};runBluetoothDiagnosis(btDevices[pos])};btListView.choiceMode=ListView.CHOICE_MODE_SINGLE}
        private fun setupWifiView(){wifiListView=wifiView.findViewById(R.id.listView);wifiAdapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_list_item_1,wifiDisplayList);wifiListView.adapter=wifiAdapter
            wifiView.findViewById<Button>(R.id.refreshButton).setOnClickListener{startWifiScan()};wifiView.findViewById<Button>(R.id.diagnoseButton).setOnClickListener{val pos=wifiListView.checkedItemPosition;if(pos==ListView.INVALID_POSITION){Toast.makeText(this@MainActivity,"Select a WiFi network first",Toast.LENGTH_SHORT).show();return@setOnClickListener};runWifiDiagnosis(wifiScanResults[pos])};wifiListView.choiceMode=ListView.CHOICE_MODE_SINGLE}
        private fun setupNetworkView(){networkListView=networkView.findViewById(R.id.listView);networkAdapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_list_item_1,networkDisplayList);networkListView.adapter=networkAdapter
            networkView.findViewById<Button>(R.id.refreshButton).setOnClickListener{startNetworkScan()};networkView.findViewById<Button>(R.id.diagnoseButton).setOnClickListener{val pos=networkListView.checkedItemPosition;if(pos==ListView.INVALID_POSITION){Toast.makeText(this@MainActivity,"Select a network device first",Toast.LENGTH_SHORT).show();return@setOnClickListener};runNetworkDiagnosis(networkDevices[pos])};networkListView.choiceMode=ListView.CHOICE_MODE_SINGLE}
        private fun setupSerialView(){serialListView=serialView.findViewById(R.id.listView);serialAdapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_list_item_1,serialDisplayList);serialListView.adapter=serialAdapter
            serialView.findViewById<Button>(R.id.refreshButton).setOnClickListener{listSerialDevices()};serialView.findViewById<Button>(R.id.diagnoseButton).setOnClickListener{val pos=serialListView.checkedItemPosition;if(pos!=ListView.INVALID_POSITION)runSerialDiagnosis(pos)else Toast.makeText(this@MainActivity,"Select a serial device first",Toast.LENGTH_SHORT).show()};serialListView.choiceMode=ListView.CHOICE_MODE_SINGLE}

        fun startBluetoothScan(){if(!::bluetoothAdapter.isInitialized||!bluetoothAdapter.isEnabled){Toast.makeText(this@MainActivity,"Bluetooth disabled",Toast.LENGTH_LONG).show();return}
            Toast.makeText(this@MainActivity,"Scanning BT…",Toast.LENGTH_SHORT).show();logEvent("BT scan started");updateStatusBar("Scanning BT…",0)
            btDevices.clear();btDisplayList.clear();btAdapter.notifyDataSetChanged();if(bluetoothAdapter.isDiscovering)bluetoothAdapter.cancelDiscovery();bluetoothAdapter.startDiscovery()
            try{registerReceiver(btReceiver,IntentFilter(BluetoothDevice.ACTION_FOUND))}catch(_:{});bluetoothAdapter.bondedDevices.forEach{addBluetoothDevice(it,true)}}
        private fun addBluetoothDevice(device:BluetoothDevice,isPaired:Boolean=false){if(!btDevices.contains(device)){btDevices.add(device);ioScope.launch{
            val ck=device.name?:device.address;val cd=deviceCache[ck];val vendor=cd?.vendor?:lookupOnline(device.address.replace(":",""));val cat=cd?.category?:categorizeDevice(device.name?:"",vendor);val compat=cd?.compatibility?:checkCompatibility(device.name?:"",cat)
            deviceCache[ck]=DeviceInfo(device.name?:"Unknown",device.address,vendor,cat,compatibility=compat,isActive=isPaired);saveCache()
            val ps=if(isPaired)" [PAIRED]" else "";val txt="${device.name?:"Unknown"}$ps\n${device.address} - $vendor\nCategory: $cat | ${suggestAction(device.name?:"",cat)}"
            mainScope.launch{btDisplayList.add(txt);btAdapter.notifyDataSetChanged();updateStatusBar("BT: ${btDevices.size} found",btDevices.size)};logDebug("BT added: ${device.name} [$cat]")}}}
        val btReceiver=object:BroadcastReceiver(){override fun onReceive(ctx:Context?,intent:Intent?){if(intent?.action==BluetoothDevice.ACTION_FOUND){val d:BluetoothDevice?=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);d?.let{addBluetoothDevice(it,false)}}}}

        fun startWifiScan(){if(!wifiManager.isWifiEnabled){Toast.makeText(this@MainActivity,"WiFi disabled",Toast.LENGTH_LONG).show();return}
            Toast.makeText(this@MainActivity,"Scanning WiFi…",Toast.LENGTH_SHORT).show();logEvent("WiFi scan started");updateStatusBar("Scanning WiFi…",0)
            wifiScanResults.clear();wifiDisplayList.clear();wifiAdapter.notifyDataSetChanged();if(!wifiManager.startScan()){Toast.makeText(this@MainActivity,"WiFi scan failed",Toast.LENGTH_SHORT).show();return}
            try{registerReceiver(wifiReceiver,IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))}catch(_:{})}
        val wifiReceiver=object:BroadcastReceiver(){override fun onReceive(ctx:Context?,intent:Intent?){if(intent?.action==WifiManager.SCAN_RESULTS_AVAILABLE_ACTION){
            try{unregisterReceiver(this)}catch(_:{});val results=wifiManager.scanResults;wifiScanResults.clear();wifiScanResults.addAll(results);wifiDisplayList.clear()
            results.forEach{r->val sec=when{r.capabilities.contains("WPA3")->"WPA3";r.capabilities.contains("WPA2")->"WPA2";r.capabilities.contains("WPA")->"WPA";r.capabilities.contains("WEP")->"WEP";else->"Open"}
                val sig=when{r.level>-50->"Excellent";r.level>-60->"Good";r.level>-70->"Fair";else->"Weak"};wifiDisplayList.add("${r.SSID}\nSignal: ${r.level}dBm ($sig)\nSecurity: $sec | Freq: ${r.frequency}MHz")}
            wifiAdapter.notifyDataSetChanged();updateStatusBar("WiFi: ${results.size} networks",results.size);logEvent("WiFi scan done: ${results.size}")}}}

        fun startNetworkScan(){Toast.makeText(this@MainActivity,"Scanning network…",Toast.LENGTH_SHORT).show();logEvent("Network scan started");updateStatusBar("Scanning network…",0)
            networkDevices.clear();networkDisplayList.clear();networkAdapter.notifyDataSetChanged();ioScope.launch{COMMON_NETWORK_RANGES.forEach{scanNetworkRange(it)}
                mainScope.launch{Toast.makeText(this@MainActivity,"Network scan done",Toast.LENGTH_SHORT).show();updateStatusBar("Network: ${networkDevices.size} devices",networkDevices.size);logEvent("Network scan done: ${networkDevices.size}");saveNetworkDevices()}}}
        private suspend fun scanNetworkRange(cidr:String){try{val parts=cidr.split("/");val baseIp=parts[0];val maskBits=parts[1].toInt();val ipParts=baseIp.split(".").map{it.toInt()};val maxHosts=(1 shl(32-maskBits))-2;val limit=minOf(maxHosts,254)
            for(i in 1..limit){val ip="${ipParts[0]}.${ipParts[1]}.${ipParts[2]}.$i";if(isHostReachable(ip,600)){logDebug("Host: $ip");val ports=performPortScan(ip);val svcs=ports.map{identifyService(it)};val cat=categorizeByPorts(ports)
                val di=DeviceInfo(ip,ip,"Pending",cat,ports,svcs,checkCompatibility(ip,cat),isActive=true);networkDevices.add(di)
                val txt="$ip\nCategory: $cat | Ports: ${ports.size}\nServices: ${svcs.joinToString(", ").ifEmpty{"None"}}";withContext(Dispatchers.Main){networkDisplayList.add(txt);networkAdapter.notifyDataSetChanged();updateStatusBar("Network: ${networkDevices.size} found",networkDevices.size)}}
                if(i%32==0)delay(80)}}catch(e:Exception){logDebug("scanNetworkRange($cidr) failed: ${e.message}")}}

        fun listSerialDevices(){logEvent("Listing USB/Serial devices");updateStatusBar("Enumerating USB…",0);serialDisplayList.clear()
            try{val usbManager=getSystemService(Context.USB_SERVICE) as UsbManager;val deviceList=usbManager.deviceList
                if(deviceList.isEmpty()){serialDisplayList.add("No USB devices detected.\nConnect a USB-Serial adapter.")}
                else{deviceList.values.forEach{usb->val sb=StringBuilder();sb.appendLine(usb.productName?:usb.deviceName?:"Unknown USB Device")
                    sb.append("  VID: 0x${usb.vendorId.toString(16).uppercase().padStart(4,'0')} | PID: 0x${usb.productId.toString(16).uppercase().padStart(4,'0')}\n")
                    sb.append("  Class: 0x${usb.deviceClass.toString(16).uppercase()} | Sub: 0x${usb.deviceSubclass.toString(16).uppercase()}")
                    val hasSerial=(0 until usb.interfaceCount).any{idx->val iface=usb.getInterface(idx);iface.interfaceClass==0x02||iface.interfaceClass==0x0A||iface.interfaceClass==0xFF}
                    if(hasSerial)sb.appendLine("\n  🔌 Serial/CDC interface detected");serialDisplayList.add(sb.toString())}}}
            catch(e:Exception){serialDisplayList.add("USB enumeration error: ${e.message}");logDebug("Serial list error: ${e.message}")}
            serialAdapter.notifyDataSetChanged();updateStatusBar("Serial: ${serialDisplayList.size} entries",serialDisplayList.size);logEvent("Serial scan completed: ${serialDisplayList.size} entries")}

        private fun runSerialDiagnosis(position:Int){if(position<0||position>=serialDisplayList.size)return;val info=serialDisplayList[position];logEvent("Serial diagnosis requested for entry $position")
            AlertDialog.Builder(this@MainActivity).setTitle("🔌 Serial / USB Diagnosis").setMessage(buildString{append("Device Entry #${position+1}\n━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n");append(info)
                append("\n\n💡 Recommendations:\n   • Verify cable and connector seating\n   • Confirm baud rate / data bits / parity\n   • Check OS-level USB permission grant\n   • Test with a known-good adapter\n")})
                .setPositiveButton("OK",null).show()}
    }
}
