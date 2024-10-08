package com.example.ao

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.room.*
import java.text.SimpleDateFormat
import java.util.*

// Clase de datos Pill
@Entity(tableName = "pill_table")
data class PillEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "time") val time: String,
    @ColumnInfo(name = "days") val days: String
)

@Dao
interface PillDao {
    @Query("SELECT * FROM pill_table ORDER BY time LIMIT 5")
    fun getNextFivePills(): List<PillEntity>

    @Query("SELECT * FROM pill_table")
    fun getAllPills(): List<PillEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPill(pill: PillEntity)

    @Delete
    fun deletePill(pill: PillEntity)
}

@Database(entities = [PillEntity::class], version = 1)
abstract class PillDatabase : RoomDatabase() {
    abstract fun pillDao(): PillDao
}

class MainActivity : AppCompatActivity() {

    private lateinit var pillDao: PillDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val recordButton: Button = findViewById(R.id.record_button)
        val editButton: Button = findViewById(R.id.edit_button)
        val listView: ListView = findViewById(R.id.pill_list_view)

        val db = Room.databaseBuilder(applicationContext, PillDatabase::class.java, "pill_database").build()
        pillDao = db.pillDao()

        // Mostrar las próximas 5 pastillas en la lista
        Thread {
            val nextFivePills = pillDao.getNextFivePills()
            runOnUiThread {
                val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, nextFivePills.map { it.name + " a las " + it.time })
                listView.adapter = adapter
            }
        }.start()

        recordButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            } else {
                startVoiceRecognition()
            }
        }

        editButton.setOnClickListener {
            val intent = Intent(this, EditPillsActivity::class.java)
            startActivity(intent)
        }
    }

    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Por favor, diga el nombre de la pastilla, la hora y los días.")
        }
        try {
            voiceRecognitionLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Su dispositivo no soporta el reconocimiento de voz", Toast.LENGTH_SHORT).show()
        }
    }

    private val voiceRecognitionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            matches?.let {
                val recognizedText = it[0]
                processRecognizedText(recognizedText)
            }
        }
    }

    private fun processRecognizedText(recognizedText: String) {
        try {
            val parts = recognizedText.split(",")
            if (parts.size >= 3) {
                val pillName = parts[0].trim()
                val time = parts[1].trim()
                val days = parts[2].trim()

                val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                val calendar = Calendar.getInstance()
                calendar.time = sdf.parse(time) ?: throw IllegalArgumentException("Formato de hora no válido")

                val pill = PillEntity(name = pillName, time = time, days = days)
                Thread {
                    pillDao.insertPill(pill)
                    setAlarm(calendar, pill)
                }.start()

                Toast.makeText(this, "Alarma configurada para $pillName a las $time en los días $days", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Por favor proporcione el nombre de la pastilla, la hora y los días", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error al procesar el texto reconocido: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setAlarm(calendar: Calendar, pill: PillEntity) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            putExtra("pill_name", pill.name)
        }
        val pendingIntent = PendingIntent.getBroadcast(this, pill.id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        alarmManager.setExact(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
    }
}

class EditPillsActivity : AppCompatActivity() {

    private lateinit var pillDao: PillDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_pills)

        val pillListView: ListView = findViewById(R.id.pill_list_view)
        val backButton: Button = findViewById(R.id.back_button)

        val db = Room.databaseBuilder(applicationContext, PillDatabase::class.java, "pill_database").build()
        pillDao = db.pillDao()

        Thread {
            val allPills = pillDao.getAllPills()
            runOnUiThread {
                val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, allPills.map { it.name + " a las " + it.time })
                pillListView.adapter = adapter

                pillListView.setOnItemClickListener { _, _, position, _ ->
                    val pillToDelete = allPills[position]
                    Thread {
                        pillDao.deletePill(pillToDelete)
                        cancelAlarm(pillToDelete)
                        runOnUiThread {
                            Toast.makeText(this, "Pastilla eliminada: ${pillToDelete.name}", Toast.LENGTH_SHORT).show()
                            adapter.remove(adapter.getItem(position))
                            adapter.notifyDataSetChanged()
                        }
                    }.start()
                }
            }
        }.start()

        backButton.setOnClickListener {
            finish()
        }
    }

    private fun cancelAlarm(pill: PillEntity) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            putExtra("pill_name", pill.name)
        }
        val pendingIntent = PendingIntent.getBroadcast(this, pill.id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        alarmManager.cancel(pendingIntent)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pillName = intent.getStringExtra("pill_name")
        Toast.makeText(context, "Es hora de tomar su pastilla: $pillName", Toast.LENGTH_LONG).show()
    }
}