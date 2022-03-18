package com.hugh.MirlKoiUpdater

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.core.content.contentValuesOf
import androidx.core.content.edit
import androidx.core.view.GravityCompat
import com.hugh.MirlKoiUpdater.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    companion object{
        // region permission code
        const val READ_STORAGE = 0
        const val WRITE_STORAGE = 1
        // endregion

        const val tag = "Updater"
        const val PATH = "MirlKoi"
        const val single = "update.jpg"
        const val shared = "cache"
        var wallpaperFlag : Int = WallpaperManager.FLAG_SYSTEM
        val apis = listOf(
            "https://iw233.cn/API/pc.php",
            "https://iw233.cn/API/mp.php",
            "https://iw233.cn/API/Random.php",
            "https://iw233.cn/API/Mirlkoi.php",
            "https://iw233.cn/API/Mirlkoi-iw233.php",
        )
        val apiRadios = mapOf(
            R.id.api_pc to apis[0],
            R.id.api_mobile to apis[1],
            R.id.api_random to apis[2],
            R.id.api_recommend to apis[3],
            R.id.api_recent to apis[4],
        )
        val apiDescId = mapOf(
            R.id.api_pc to R.string.api_pc,
            R.id.api_mobile to R.string.api_mp,
            R.id.api_random to R.string.api_random,
            R.id.api_recommend to R.string.api_recommend,
            R.id.api_recent to R.string.api_recent,
        )

        fun logD(tag:String, msg:String){
            Log.d(tag,msg)
        }
    }
    private lateinit var api : String
    private var bitmap : Bitmap? = null
    private lateinit var bind : ActivityMainBinding
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bind = ActivityMainBinding.inflate(layoutInflater)
        setContentView(bind.root)
        loadSettings()
        buttonsAction()
        Log.d(tag,"Screen width:${getDisplayScale(" height:")}")

        setAppPreview(bind.layoutDrawer, wallpaperFlag)
    }

    override fun onDestroy() {
        super.onDestroy()
        saveSettings()
        Log.d(tag,"OnDestroy changes saved")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when(requestCode){
            READ_STORAGE->{
                if(grantResults.isEmpty()){
                    Toast.makeText(this,"没有授权，不能生成应用内壁纸预览",
                        Toast.LENGTH_SHORT).show()
                }
            }
            WRITE_STORAGE->{
                if(grantResults.isEmpty()){
                    Toast.makeText(this,"无存储权限，无法将壁纸存入手机",
                        Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    // region shared preference
    private fun saveSettings(){
        getSharedPreferences(shared, MODE_PRIVATE).edit{
            // todo save
            putBoolean("save",bind.save.isChecked)
            putInt("url",apis.indexOf(api))
            putInt("flag", wallpaperFlag)
            Log.d(tag,"save:${bind.save.isChecked}\nurl pos:${apis.indexOf(api)}")
        }
    }
    private fun loadSettings(){
        val prefs = getSharedPreferences(shared, MODE_PRIVATE)
        bind.save.isChecked = prefs.getBoolean("save",false)
        // api
        val selected = prefs.getInt("url",1)
        api = apis[selected]
        bind.apiGroup.check(bind.apiGroup.getChildAt(selected).id) // set selected
        // wallpaper mode
        wallpaperFlag = prefs.getInt("flag",1) // FLAG_SYSTEM === 1
        bind.wpLock.isChecked = (wallpaperFlag and 2)==2
        bind.wpHome.isChecked = (wallpaperFlag and 1)==1
    }
    // endregion
    private fun buttonsAction(){
        bind.apiGroup.setOnCheckedChangeListener { _, id ->
            api = apiRadios[id]!!
            Toast.makeText(this,"切换壁纸选择:${getString(apiDescId[id]!!)}",
                Toast.LENGTH_SHORT).show()
            Log.d(tag,"button clicked $api")
        }

        bind.wpHome.setOnClickListener {
            wallpaperFlag = wallpaperFlag xor WallpaperManager.FLAG_SYSTEM
        }
        bind.wpLock.setOnClickListener {
            wallpaperFlag = wallpaperFlag xor WallpaperManager.FLAG_LOCK
        }
        bind.downloadButton.setOnClickListener {
            saveImage2Gallery("Button action : save image to gallery")
        }
        bind.updateButton.setOnClickListener {
            thread{
                bitmap = download(api)
                if(bitmap==null){
                    Log.w(tag,"Don't get wallpaper from API")
                }
                bitmap?.saveToLocal(openFileOutput(single, Context.MODE_PRIVATE)){
                    Log.d(tag,"get fd downloaded this image")
                }
                if(bind.save.isChecked){
                    saveImage2Gallery("auto mode:save image to gallery")
                }
                val width = getDisplayWidth()
                val height = getDisplayHeight()
                Log.d(tag,"width:$width,height:$height")
                bitmap = bitmap?.saveToWallpaper(
                    getSystemService(Context.WALLPAPER_SERVICE) as WallpaperManager,
                    wallpaperFlag, Rect(0,0,width,height)){
                    Log.d(tag,"bitmap width:${bitmap!!.width}; height:${bitmap!!.height}")
                    Log.d(tag,"switch wallpaper")
                }

                runOnUiThread { setAppPreview(bind.layoutDrawer,bitmap) }
            }
        }
        // region drawer buttons
        bind.settings.setOnClickListener {
            bind.layoutDrawer.openDrawer(GravityCompat.END)
        }
        // endregion
    }
    private fun getURLConnection(url:String):HttpURLConnection{
        val con = if ("https" in url)
            URL(url).openConnection() as HttpsURLConnection
        else URL(url).openConnection() as HttpURLConnection
        return con.apply {
            doInput = true
            requestMethod = "GET"
            instanceFollowRedirects = true
        }
    }
    private fun download(url:String): Bitmap? {
        var bitmap : Bitmap? = null
        Log.d(tag,"Target URL:$url")
        var connector = getURLConnection(url)
        var redirect = true
        do {
            connector.connect()
            Log.d(tag,"response code:${connector.responseCode}")
            when (connector.responseCode) {
                200 -> {
                    Log.d(tag,"fetch image successfully")
                    redirect = false
                    Log.d(tag,"Downloading image")
                    bitmap = BitmapFactory.decodeStream(connector.inputStream)
                    connector.disconnect()
                }
                in 300..400 -> {
                    Log.d(tag,"resp code:${connector.responseCode}|url:${connector.url}")
                    Log.w(tag,"location:${connector.getHeaderField("Location")}")
                    connector.disconnect()
                    connector = getURLConnection(connector.getHeaderField("Location"))
                }
                in 400..500 -> {
                    runOnUiThread {
                        Toast.makeText(this,
                            "API server is deprecated",Toast.LENGTH_LONG).show()
                    }
                    connector.disconnect()
                    redirect=false
                }
            }
        }while (redirect)
        return bitmap
    }
    private fun getExternalImageOutputStream(filename : String,MIME:String = "image/jpeg"): OutputStream? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = contentValuesOf (
                MediaStore.MediaColumns.DISPLAY_NAME to filename,
                MediaStore.MediaColumns.MIME_TYPE to MIME,
                MediaStore.MediaColumns.RELATIVE_PATH to Environment.DIRECTORY_PICTURES+"/"+PATH
            )
            //Inserting the contentValues to contentResolver and getting the Uri
            val imageUri: Uri? =
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            Log.d(tag,imageUri.toString())
            imageUri?.let { contentResolver.openOutputStream(it) }
        } else {
            val path = Environment.
                getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES+"/"+PATH)
            Log.d(tag,"use API before Q|$path")
            val image = File(path,filename)
            FileOutputStream(image)
        }
    }
    private fun saveImage2Gallery(msg:String){
        thread {
            getExternalImageOutputStream(System.currentTimeMillis().toString())?.let {
                bitmap?.saveToGallery(it){
                    Log.d(tag,msg)
                }
            }
        }
    }
}