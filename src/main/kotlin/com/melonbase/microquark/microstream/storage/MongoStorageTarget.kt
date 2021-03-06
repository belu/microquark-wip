package com.melonbase.microquark.microstream.storage

import com.melonbase.microquark.microstream.MONGODB
import com.mongodb.client.MongoClients
import one.microstream.afs.blobstore.BlobStoreFileSystem
import one.microstream.afs.mongodb.MongoDbConnector
import one.microstream.storage.types.StorageManager
import org.eclipse.microprofile.config.ConfigProvider

private const val CONFIG_MONGODB_CONNECTION_STRING = "quarkus.mongodb.connection-string"

private const val DATABASE_NAME = "microstream@mongodb"
private const val DIRECTORY_PATH = "microstream"

fun loadStorageMongoDb(): StorageManager {
  val connectionString = ConfigProvider.getConfig()
    .getOptionalValue(CONFIG_MONGODB_CONNECTION_STRING, String::class.java)
    .orElseThrow {
      IllegalStateException("Storage type '$MONGODB', but no MongoDB connection defined.")
    }

  val db = MongoClients.create(connectionString).getDatabase("db")

  val path = BlobStoreFileSystem.New(
    MongoDbConnector.Caching(db)
  ).ensureDirectoryPath(DIRECTORY_PATH)

  return createStorageManager(path, DATABASE_NAME)
}