package com.vayunmathur.openassistant.util
import android.annotation.SuppressLint
import android.content.Context
import com.vayunmathur.library.log.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.vayunmathur.library.intents.calendar.EventData
import com.vayunmathur.library.intents.clock.SetAlarmData
import com.vayunmathur.library.intents.clock.SetTimerData
import com.vayunmathur.library.intents.contacts.ContactData
import com.vayunmathur.library.intents.music.PlayMusicData
import com.vayunmathur.library.intents.notes.NoteData
import com.vayunmathur.openassistant.MainActivity
import com.vayunmathur.openassistant.data.MemoryDao
import com.vayunmathur.openassistant.data.MessageDao
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

class AssistantToolSet(
    private val context: Context,
    private val memoryDao: MemoryDao? = null,
    private val messageDao: MessageDao? = null,
    private val conversationId: Long = -1L,
) : ToolSet {
    private val intents = AssistantToolIntents(context)
    private val device = AssistantDeviceTools(context)

    @Tool(description = "Get a list of all notes")
    fun getNotes(): String = intents.runTool {
        intents.getNotes()
    }

    @Tool(description = "Create a new note in the notes app. Should only be used with EXPLICIT request by user")
    fun createNote(title: String, content: String): String = intents.runTool {
        intents.insertNote(NoteData(title, content))
        "Success: Created note '$title'"
    }

    @Tool(description = "Get a list of all contacts")
    fun getContacts(): String = intents.runTool {
        intents.getContacts()
    }

    @Tool(description = "Create a new contact")
    fun createContact(name: String, phoneNumber: String): String = intents.runTool {
        intents.insertContact(ContactData(name, phoneNumber))
        "Success: Created contact '$name'"
    }

    @Tool(description = "Get a list of calendar events. Each event's start and end times are epoch milliseconds.")
    fun getCalendarEvents(): String = intents.runTool {
        intents.getCalendarEvents()
    }

    @Tool(description = "Create a new calendar event. Start and end times must be epoch milliseconds.")
    fun createCalendarEvent(
        title: String,
        @ToolParam(description = "start time as epoch milliseconds") start: Double,
        @ToolParam(description = "end time as epoch milliseconds") end: Double,
        location: String = "",
    ): String = intents.runTool {
        intents.insertCalendarEvent(EventData(title, start.toLong(), end.toLong(), location))
        "Success: Created event '$title'"
    }

    @Tool(description = "Get a list of family members and their current locations")
    fun getFamilyLocations(): String = intents.runTool {
        intents.getFamilyLocations()
    }

    @Tool(description = "Create a temporary FindFamily link that shares the user's live location.")
    fun createLocationShareLink(
        @ToolParam(description = "name or label for who the link is for") name: String,
        @ToolParam(description = "how long the link stays active, in milliseconds") durationMillis: Double,
    ): String = intents.runTool {
        intents.createLocationShareLink(name, durationMillis)
    }

    @Tool(description = "Search for music (songs, albums, artists, or playlists)")
    fun searchMusic(query: String): String = intents.runTool {
        intents.searchMusic(query)
    }

    @Tool(description = "Play music given its id and type (song, album, artist, or playlist)")
    fun playMusic(id: Double, type: String): String = intents.runTool {
        intents.playMusic(PlayMusicData(id.toLong(), type))
        "Success: Playing music"
    }

    @Tool(description = "Search emails by keyword")
    fun searchEmails(query: String): String = intents.runTool {
        intents.searchEmails(query)
    }

    @Tool(description = "Get recent emails from the inbox")
    fun getRecentEmails(): String = intents.runTool {
        intents.getRecentEmails()
    }

    @Tool(description = "Get the current date and time in the local timezone.")
    fun getLocalCurrentDateTime(): String = device.getLocalCurrentDateTime()

    @SuppressLint("QueryPermissionsNeeded")
    @Tool(description = "Get a list of installed apps on the device")
    fun getAppList(): String = device.getAppList()

    @Tool(description = "Open an app given its package id")
    fun openApp(@ToolParam(description = "package id") packageId: String): String =
        device.openApp(packageId, intents::handleMissingApp)

    @Tool(description = "Send a message")
    fun sendMessage(recipient: String, message: String): String =
        device.sendMessage(recipient, message)

    @Tool(description = "Make a phone call")
    fun makePhoneCall(recipient: String): String = device.makePhoneCall(recipient)

    @Tool(description = "Set an alarm in the Clock app at a specific hour and minute (24-hour time).")
    fun setAlarm(
        @ToolParam(description = "hour in 24-hour format, 0-23") hour: Double,
        @ToolParam(description = "minute, 0-59") minute: Double,
        @ToolParam(description = "optional label for the alarm") label: String = "",
    ): String = intents.runTool {
        intents.setAlarm(SetAlarmData(hour.toInt(), minute.toInt(), label))
        "Success: Alarm set for %02d:%02d".format(hour.toInt(), minute.toInt())
    }

    @Tool(description = "Start a countdown timer in the Clock app for a number of seconds.")
    fun setTimer(
        @ToolParam(description = "timer length in seconds") seconds: Double,
        @ToolParam(description = "optional label for the timer") label: String = "",
    ): String = intents.runTool {
        intents.setTimer(SetTimerData(seconds.toInt(), label))
        "Success: Timer started for ${seconds.toInt()}s"
    }

    @Tool("Set title of current conversation. Mandatory for first response")
    fun setConversationTitle(newTitle: String): String = device.setConversationTitle(newTitle)

    @Tool(description = "Get the current weather conditions at a specific latitude/longitude.")
    fun getWeather(latitude: Double, longitude: Double): String = intents.runTool {
        intents.getWeather(latitude, longitude)
    }

    @Tool(description = "Get the current weather conditions for a named place (city, address, landmark).")
    fun getWeatherByName(
        @ToolParam(description = "city or place name") location: String,
    ): String = intents.runTool {
        intents.getWeatherByName(location)
    }

    @Tool(description = "Get a list of all memories")
    fun getMemories(): String = intents.runTool {
        device.getMemories(memoryDao)
    }

    @Tool(description = "Remove a memory by its id")
    fun removeMemory(id: Double): String = intents.runTool {
        device.removeMemory(memoryDao, id)
    }

    @Tool(description = "Add a new memory to the list of memories")
    fun addToMemory(content: String): String = intents.runTool {
        device.addToMemory(memoryDao, content)
    }
}

class MissingAppException(val packageName: String) : Exception("App $packageName is not installed.")
class StopInferenceException : Exception("STOP")

@kotlinx.serialization.Serializable
data class WeatherLatLonRequest(val latitude: Double, val longitude: Double)

@kotlinx.serialization.Serializable
data class WeatherNameRequest(val name: String)

@kotlinx.serialization.Serializable
data class CreateLinkRequest(val name: String, val expiryMillis: Long)

@OptIn(ExperimentalSerializationApi::class)
suspend inline fun <reified Input : Any, reified Output : Any> launchIntent(
    context: Context,
    packageName: String,
    className: String,
    input: Input,
): Output {
    val stringOutput = MainActivity.intentLauncher.launch(
        context,
        packageName,
        className,
        serializer<Input>(),
        input,
    )
    if (stringOutput == "package $packageName doesn't exist") throw MissingAppException(packageName)
    return Json.decodeFromString(serializer<Output>(), stringOutput)
}

@OptIn(ExperimentalSerializationApi::class)
suspend inline fun <reified Input : Any> launchIntentU(
    context: Context,
    packageName: String,
    className: String,
    input: Input,
) {
    val stringOutput = MainActivity.intentLauncher.launch(
        context,
        packageName,
        className,
        serializer<Input>(),
        input,
    )
    if (stringOutput == "package $packageName doesn't exist") throw MissingAppException(packageName)
}
