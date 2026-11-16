package com.aurelis.app

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

object MemoryCategory {
    const val PREFERENCES = "Preferences"
    const val PROJECTS = "Projects"
    const val IMPORTANT = "Important"
    const val TASKS = "Tasks"
    const val LEARNING = "Learning"
    const val CONTEXT = "Context"
    val ALL = listOf(PREFERENCES, PROJECTS, IMPORTANT, TASKS, LEARNING, CONTEXT)
}

@Entity(tableName = "memories")
data class Memory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "reminders")
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val triggerAt: Long,
    val done: Boolean = false
)

@Entity(tableName = "tasks")
data class TaskItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "chat")
data class ChatRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val text: String,
    val time: Long
)

@Entity(tableName = "history")
data class HistoryRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val text: String
)

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    fun observe(): Flow<List<Memory>>

    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    suspend fun list(): List<Memory>

    @Insert
    suspend fun insert(m: Memory): Long

    @Update
    suspend fun update(m: Memory)

    @Delete
    suspend fun delete(m: Memory)

    @Query("DELETE FROM memories")
    suspend fun clear()
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE done = 0 ORDER BY triggerAt ASC")
    fun observePending(): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders WHERE done = 0 ORDER BY triggerAt ASC")
    suspend fun pending(): List<Reminder>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun get(id: Long): Reminder?

    @Insert
    suspend fun insert(r: Reminder): Long

    @Update
    suspend fun update(r: Reminder)

    @Delete
    suspend fun delete(r: Reminder)

    @Query("DELETE FROM reminders")
    suspend fun clear()
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY done ASC, createdAt DESC")
    fun observe(): Flow<List<TaskItem>>

    @Query("SELECT * FROM tasks ORDER BY done ASC, createdAt DESC")
    suspend fun list(): List<TaskItem>

    @Insert
    suspend fun insert(t: TaskItem): Long

    @Update
    suspend fun update(t: TaskItem)

    @Delete
    suspend fun delete(t: TaskItem)

    @Query("DELETE FROM tasks")
    suspend fun clear()
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat ORDER BY time DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ChatRow>

    @Insert
    suspend fun insert(r: ChatRow): Long

    @Query("DELETE FROM chat")
    suspend fun clear()
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY time DESC LIMIT 200")
    fun observe(): Flow<List<HistoryRow>>

    @Insert
    suspend fun insert(r: HistoryRow): Long

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Database(
    entities = [Memory::class, Reminder::class, TaskItem::class, ChatRow::class, HistoryRow::class],
    version = 1,
    exportSchema = false
)
abstract class AurelisDb : RoomDatabase() {
    abstract fun memories(): MemoryDao
    abstract fun reminders(): ReminderDao
    abstract fun tasks(): TaskDao
    abstract fun chat(): ChatDao
    abstract fun history(): HistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AurelisDb? = null

        fun get(ctx: Context): AurelisDb =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(ctx.applicationContext, AurelisDb::class.java, "aurelis.db")
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
