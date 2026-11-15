package com.stockflow.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null
    private var output: java.io.OutputStream? = null
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var devicePicker: Spinner
    private val devices = mutableListOf<BluetoothDevice>()
    private val executor = Executors.newSingleThreadExecutor()
    private var lastValue = ""; private var lastAt = 0L
    private var barcodeField: EditText? = null
    private val navy = Color.rgb(9, 19, 42); private val panel = Color.rgb(19, 37, 70)
    private val green = Color.rgb(38, 224, 125); private val cyan = Color.rgb(25, 195, 220)

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); showHome(); ensureBluetoothPermission { loadPairedDevices() } }
    private fun text(s:String,size:Float=15f,color:Int=Color.WHITE)=TextView(this).apply { text=s; textSize=size; setTextColor(color); setPadding(0,8,0,8) }
    private fun button(s:String, action:()->Unit)=Button(this).apply { text=s; isAllCaps=false; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(25,105,190)); setOnClickListener{action()} }
    private fun field(hint:String)=EditText(this).apply { this.hint=hint; setSingleLine(true); textSize=16f; setTextColor(Color.WHITE); setHintTextColor(0xFF9DB2D0.toInt()); setPadding(18,14,18,14); setBackgroundColor(panel); layoutParams=LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,5,0,5)} }
    private fun base(title:String, subtitle:String) {
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(18,18,18,12); setBackgroundColor(navy) }
        root.addView(text("▣  StockFlow",27f,green)); root.addView(text(title,23f)); root.addView(text(subtitle,13f,0xFFB7C9E5.toInt()))
        status=text("● Not connected",13f,0xFFFFC66D.toInt()); root.addView(status)
        val nav=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER; setPadding(0,8,0,8) }
        listOf("Home","Scan","Product","Sale","Purchase").forEach { label -> nav.addView(button(label){ when(label){"Home"->showHome();"Scan"->showScan();"Product"->showProduct();"Sale"->showTransaction("SALE");else->showTransaction("PURCHASE") } }, LinearLayout.LayoutParams(0,-2,1f)) }
        root.addView(nav); setContentView(root)
    }
    private fun content():LinearLayout { val scroll=ScrollView(this); val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(4,8,4,12)}; scroll.addView(body); root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f)); return body }
    private fun showHome(){ base("Your shop, in your pocket","Scan • Manage • Sell • Grow"); val b=content(); b.addView(text("Welcome to StockFlow Scanner",20f,green)); b.addView(text("Connect to your Windows StockFlow app to send scans, products, sales and purchases directly to your inventory.")); b.addView(text("Bluetooth connection",18f)); devicePicker=Spinner(this); b.addView(devicePicker); b.addView(button("Refresh paired devices"){ensureBluetoothPermission{loadPairedDevices()}}); b.addView(button("Connect to StockFlow PC"){ensureBluetoothPermission{connectBluetooth()}}); b.addView(button("Scan a barcode"){showScan()}); b.addView(button("Add a product"){showProduct()}); b.addView(button("Record a sale"){showTransaction("SALE")}); b.addView(button("Record a purchase"){showTransaction("PURCHASE")}); b.addView(text("Tip: pair your phone with DESKTOP-C2527B1 in Android Bluetooth settings first.",13f,0xFF9DB2D0.toInt())) }
    private fun showScan(){ base("Barcode scanner","Point your camera at a product barcode"); val b=content(); val preview=PreviewView(this); b.addView(preview,LinearLayout.LayoutParams(-1,0,1f)); val manual=field("Or type barcode manually"); b.addView(manual); b.addView(button("Send barcode to PC"){sendBarcode(manual.text.toString());manual.setText("")}); startCamera(preview) }
    private fun showProduct(){ base("Add a product","Product details will be saved on your PC"); val b=content(); val code=field("Barcode"); barcodeField=code; val name=field("Product name *"); val category=field("Category"); val buy=field("Buying price (UGX)"); val sell=field("Selling price (UGX)"); val qty=field("Opening quantity"); val expiry=field("Expiry date (YYYY-MM-DD, optional)"); listOf(code,name,category,buy,sell,qty,expiry).forEach{b.addView(it)}; b.addView(button("Scan barcode"){showScan()}); b.addView(button("Save product to StockFlow PC"){ if(name.text.isBlank()){status.text="Enter a product name.";return@button}; val p=JSONObject().put("type","product").put("barcode",code.text.toString()).put("name",name.text.toString()).put("category",category.text.toString()).put("buy_price",buy.text.toString().toDoubleOrNull()?:0.0).put("sell_price",sell.text.toString().toDoubleOrNull()?:0.0).put("quantity",qty.text.toString().toIntOrNull()?:0).put("expiry_date",expiry.text.toString()); sendMessage(p) }) }
    private fun showTransaction(kind:String){ base(if(kind=="SALE")"Record a sale" else "Record a purchase", "Transaction will update your PC inventory"); val b=content(); val code=field("Product barcode"); barcodeField=code; val qty=field("Quantity"); val price=field(if(kind=="SALE")"Selling price per item (UGX)" else "Buying price per item (UGX)"); val party=field(if(kind=="SALE")"Customer (optional)" else "Supplier (optional)"); listOf(code,qty,price,party).forEach{b.addView(it)}; b.addView(button("Scan barcode"){showScan()}); b.addView(button(if(kind=="SALE")"Save sale" else "Save purchase"){ val q=qty.text.toString().toIntOrNull()?:0; val p=price.text.toString().toDoubleOrNull()?:0.0; if(code.text.isBlank()||q<1||p<0){status.text="Enter a barcode, quantity and valid price.";return@button}; val msg=JSONObject().put("type",kind.lowercase()).put("barcode",code.text.toString()).put("quantity",q).put("unit_price",p).put("party",party.text.toString()); sendMessage(msg) }) }
    private fun ensureCameraPermission(done:()->Unit){ if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)done() else ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.CAMERA),10) }
    private fun ensureBluetoothPermission(done:()->Unit){ if(Build.VERSION.SDK_INT>=31&&ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.BLUETOOTH_CONNECT),11) else done() }
    @SuppressLint("MissingPermission") private fun loadPairedDevices(){ val a=BluetoothAdapter.getDefaultAdapter(); if(a==null){status.text="Bluetooth unavailable";return}; if(!a.isEnabled){status.text="Turn Bluetooth on";return}; devices.clear();devices.addAll(a.bondedDevices?: emptySet()); devicePicker.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,devices.map{"${it.name?:"Device"} (${it.address})"});status.text=if(devices.isEmpty())"Pair with your PC first" else "${devices.size} paired device(s) found" }
    @SuppressLint("MissingPermission") private fun connectBluetooth(){ if(devices.isEmpty()){loadPairedDevices();return}; val pos=devicePicker.selectedItemPosition.coerceIn(devices.indices); status.text="Connecting…"; Thread{try{socket?.close();val s=devices[pos].createRfcommSocketToServiceRecord(spp);s.connect();socket=s;output=s.outputStream;runOnUiThread{status.text="● Connected to ${s.remoteDevice.name}";status.setTextColor(green)}}catch(e:Exception){runOnUiThread{status.text="Connection failed: ${e.message}"}}}.start() }
    private fun sendBarcode(v:String){val c=v.trim();if(c.isNotEmpty())sendMessage(JSONObject().put("type","scan").put("barcode",c))}
    private fun sendMessage(obj:JSONObject){Thread{try{val out=output?:throw IllegalStateException("Connect to the PC first");out.write((obj.toString()+"\r\n").toByteArray(Charsets.UTF_8));out.flush();runOnUiThread{status.text="✓ Sent to StockFlow PC";status.setTextColor(green)}}catch(e:Exception){runOnUiThread{status.text="Not sent: ${e.message}";status.setTextColor(0xFFFFC66D.toInt())}}}.start()}
    private fun startCamera(preview:PreviewView){ensureCameraPermission{val future=ProcessCameraProvider.getInstance(this);future.addListener({val provider=future.get();val p=Preview.Builder().build().also{it.setSurfaceProvider(preview.surfaceProvider)};val scanner=BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build());val analysis=ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();analysis.setAnalyzer(executor){proxy->val media=proxy.image;if(media==null){proxy.close();return@setAnalyzer};scanner.process(InputImage.fromMediaImage(media,proxy.imageInfo.rotationDegrees)).addOnSuccessListener{codes->val v=codes.firstOrNull()?.rawValue?:return@addOnSuccessListener;val now=System.currentTimeMillis();if(v!=lastValue||now-lastAt>1800){lastValue=v;lastAt=now;sendBarcode(v);runOnUiThread{status.text="Scanned $v"}}}.addOnCompleteListener{proxy.close()}};try{provider.unbindAll();provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,p,analysis)}catch(e:Exception){status.text="Camera error: ${e.message}"}},ContextCompat.getMainExecutor(this))} }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED){if(requestCode==11)loadPairedDevices();if(requestCode==10)showScan()}}
    override fun onDestroy(){super.onDestroy();try{socket?.close()}catch(_:Exception){};executor.shutdown()}
}
