package dev.gray.screentime

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "catalog")
internal data class Catalog(@PrimaryKey val pkg: String, val label: String, val system: Boolean?, val icon: ByteArray?)

@Entity(tableName = "daily", primaryKeys = ["pkg", "date"], indices = [Index("date")])
internal data class Daily(val pkg: String, val date: String, val ms: Long?, val entries: Int?,
                          val source: String, val zone: String) {
    fun value() = DayValue(ms, entries, source)
}

@Entity(tableName = "coverage")
internal data class Cover(@PrimaryKey val date: String, val zone: String, val fromMs: Long,
                          val endMs: Long, val complete: Boolean)
@Entity(tableName = "collector_state")
internal data class Checkpoint(@PrimaryKey val id: Int = 1, val lastSuccess: Long? = null,
                               val anchor: Long? = null, val zone: String = "", val cutOff: Long = 0)

@Dao
internal interface ArchiveDao {
    @Query("SELECT * FROM daily WHERE date BETWEEN :first AND :last ORDER BY date")
    fun year(first: String, last: String): Flow<List<Daily>>
    @Query("SELECT * FROM daily WHERE date BETWEEN :first AND :last")
    suspend fun existing(first: String, last: String): List<Daily>
    @Query("SELECT * FROM coverage WHERE date BETWEEN :first AND :last")
    fun coverage(first: String, last: String): Flow<List<Cover>>
    @Query("SELECT * FROM coverage WHERE date BETWEEN :first AND :last")
    suspend fun priorCoverage(first: String, last: String): List<Cover>
    @Query("SELECT * FROM catalog") fun catalog(): Flow<List<Catalog>>
    @Query("SELECT * FROM catalog") suspend fun catalogSnapshot(): List<Catalog>
    @Query("SELECT * FROM collector_state WHERE id=1") suspend fun checkpoint(): Checkpoint?
    @Query("SELECT * FROM collector_state WHERE id=1") fun status(): Flow<Checkpoint?>
    @Query("SELECT MIN(date) FROM daily") fun firstDay(): Flow<String?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveDays(rows: List<Daily>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveCatalog(rows: List<Catalog>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveCoverage(rows: List<Cover>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveState(state: Checkpoint)
}

@Database(entities = [Daily::class, Catalog::class, Cover::class, Checkpoint::class], version = 1, exportSchema = true)
internal abstract class Archive : RoomDatabase() {
    abstract fun dao(): ArchiveDao
    companion object {
        @Volatile private var instance: Archive? = null
        fun open(context: Context): Archive = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, Archive::class.java, "annual-archive.db")
                .build().also { instance = it }
        }
    }
}
