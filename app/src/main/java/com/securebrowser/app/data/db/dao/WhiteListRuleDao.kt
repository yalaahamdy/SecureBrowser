package com.securebrowser.app.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.securebrowser.app.data.db.entity.WhiteListRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WhiteListRuleDao {

    @Query("SELECT * FROM white_list_rules ORDER BY createdAt ASC")
    suspend fun getAll(): List<WhiteListRuleEntity>

    @Query("SELECT * FROM white_list_rules ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<WhiteListRuleEntity>>

    @Query("SELECT COUNT(*) FROM white_list_rules")
    suspend fun count(): Int

    @Insert
    suspend fun insert(rule: WhiteListRuleEntity): Long

    @Update
    suspend fun update(rule: WhiteListRuleEntity)

    @Query("UPDATE white_list_rules SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, updatedAt: Long)

    @Query("UPDATE white_list_rules SET ruleType = :ruleType, subdomainPolicy = :subdomainPolicy, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateScope(id: Long, ruleType: String, subdomainPolicy: String, updatedAt: Long)

    @Delete
    suspend fun delete(rule: WhiteListRuleEntity)

    @Query("DELETE FROM white_list_rules")
    suspend fun deleteAll()
}
