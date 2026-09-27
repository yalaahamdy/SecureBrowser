package com.securebrowser.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.securebrowser.app.data.db.entity.AppSettingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppSettingsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(setting: AppSettingEntity)

    @Query("SELECT * FROM app_settings")
    suspend fun getAll(): List<AppSettingEntity>

    @Query("SELECT * FROM app_settings")
    fun observeAll(): Flow<List<AppSettingEntity>>

    @Query("SELECT value FROM app_settings WHERE `key` = :key LIMIT 1")
    suspend fun getValue(key: String): String?
}
