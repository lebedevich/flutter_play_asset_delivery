package de.yucondigital.flutter_play_asset_delivery


import android.content.res.AssetManager
import androidx.annotation.NonNull

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import java.io.File

/** FlutterPlayAssetDeliveryPlugin */
class FlutterPlayAssetDeliveryPlugin: FlutterPlugin, MethodCallHandler {
  private lateinit var channel : MethodChannel
  private lateinit var assetManager : AssetManager
  private lateinit var assetList : List<String>
  private lateinit var cacheDir : File

  private val cacheControlFileName: String = ".cache-data.file"

  private val MILLIS_PER_DAY = 24 * 60 * 60 * 1000 // 1 day
  private val cacheControlAvailable: Int = 2 * MILLIS_PER_DAY

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    assetManager = flutterPluginBinding.applicationContext.assets
    cacheDir = flutterPluginBinding.applicationContext.cacheDir

    fetchAllAssets()

    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "flutter_play_asset_delivery")
    channel.setMethodCallHandler(this)
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    if (call.method == "getAssetFile") {
      val assetName: String = call.arguments.toString()

      if (checkAsset(assetName)) {
        val file: File = moveAssetTempFile(assetName)
        result.success(file.absolutePath)
      } else {
        result.error("Asset not found", "$assetName could not be found. ", null)
      }
    } else {
      result.notImplemented()
    }
  }

  private fun checkAsset(assetName: String): Boolean {
    val pathSegments = assetName.split("/")

    if (pathSegments.isEmpty()) {
      return false
    }

    if (pathSegments.size == 1) {
      return assetList.contains(assetName)
    }

    val directoryPath = pathSegments.subList(0, pathSegments.size - 1).joinToString("/")
    val fileName = pathSegments.last()

    val directoryAssets = assetManager.list(directoryPath)?.asList() ?: emptyList()
    return directoryAssets.contains(fileName)
  }

  private fun fetchAllAssets() {
    assetList = assetManager.list("")?.asList() ?: emptyList()
    optimizeCache()
  }

  private fun assetNameToCacheName(assetName: String): String {
    return "shared_" + assetName.replace("/", "_")
  }

  private fun moveAssetTempFile(assetName: String): File {
    val file = File(cacheDir, assetNameToCacheName(assetName))
    val result = file.createNewFile()

    if (result) {
      file.writeBytes(assetManager.open(assetName).readBytes())
    }

    updateCacheControlFile(assetName)

    return file
  }

  private fun updateCacheControlFile(newAssetName: String) {
    val cacheControlFile = File(cacheDir, cacheControlFileName)
    val fileExists = cacheControlFile.exists()

    if (!fileExists) {
      cacheControlFile.createNewFile()
    }

    val cacheControlData = cacheControlFile.readText()
    val cacheControlMap = if (cacheControlData.isNotEmpty()) {
      cacheControlData.split("\n").associate {
        val (key, value) = it.split("=")
        key to value
      }.toMutableMap()
    } else {
      mutableMapOf()
    }

    cacheControlMap[newAssetName] = System.currentTimeMillis().toString()
    cacheControlFile.writeText(cacheControlMap.map { "${it.key}=${it.value}" }.joinToString("\n"))
  }

  private fun optimizeCache() {
    val cacheControlFile = File(cacheDir, cacheControlFileName)
    val fileExists = cacheControlFile.exists()

    if (!fileExists) {
      val cacheFiles = cacheDir.listFiles() ?: emptyArray()
      for (file in cacheFiles) {
        if (file.name.startsWith("shared_")) {
          file.delete()
        }
      }
    } else {
      val cacheControlData = cacheControlFile.readText()
      val cacheControlMap = if (cacheControlData.isNotEmpty()) {
        cacheControlData.split("\n").associate {
          val (key, value) = it.split("=")
          key to value
        }.toMutableMap()
      } else {
        mutableMapOf()
      }

      cacheControlMap.filter { it.value.toLong() < System.currentTimeMillis() - cacheControlAvailable }
        .forEach { (key, _) ->
          deleteCacheFile(assetNameToCacheName(key))
          cacheControlMap.remove(key)
        }
    }
  }

  private fun deleteCacheFile(fileName: String) {
    val fileToDelete = File(cacheDir, fileName)
    fileToDelete.delete()
  }
}
