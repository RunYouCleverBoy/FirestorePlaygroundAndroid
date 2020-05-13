package com.playgrounds.firestoreplayground

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.firebase.ui.auth.AuthUI
import com.firebase.ui.auth.IdpResponse
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.one_string_line.view.*
import java.util.*


class MainActivity : AppCompatActivity() {
    private val viewModel by lazy { object: ViewModelProvider.Factory {
        override fun <T : ViewModel?> create(modelClass: Class<T>): T {
            return modelClass.getConstructor(Context::class.java).newInstance(this@MainActivity)
        } }.create(VM::class.java) }

    enum class Mode { Log, Messages }
    private val mode = MutableLiveData<Mode>()
    private val logAdapter by lazy { LA{ parent -> StrVH(parent) } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val commands = listOf(
            Command("Current user ${viewModel.fb.currentUser?.displayName}"),
            Command("Authenticate") { authenticate() },
            Command("Add Message") { viewModel.fb.addMessage(gibberish())},
            Command("Show messages") { mode.postValue(Mode.Messages) },
            Command("Show logs") { mode.postValue(Mode.Log) },
            Command("Test logs") { mode.postValue(Mode.Log); repeat(20){i -> log("Testing #$i")} }
        )

        commandContainer.adapter = LA{ parent -> CommandVH(parent) }.also { it.submitList(commands) }
        commandContainer.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)

        listContainer.adapter = logAdapter
        listContainer.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)

        mode.observe(this, Observer { when (it) {
            null -> {}
            Mode.Log -> {
                Logger.onAdd = { logAdapter.submitList(Logger.list) }
                logAdapter.submitList(Logger.list)
            }
            Mode.Messages -> {
                Logger.onAdd = {}
                logAdapter.submitList(emptyList())
                viewModel.fb.getMessages { list: List<VM.Message> ->
                    logAdapter.submitList(list.map { message -> message.displayMessage })
                }
            }
        }})
    }

    private fun gibberish() = VM.Message(viewModel.fb.currentUser?.displayName?:"N/A", viewModel.content(10), Date())

    private fun authenticate() {
        // Choose authentication providers
        val providers = arrayListOf(
            AuthUI.IdpConfig.EmailBuilder().build(),
            AuthUI.IdpConfig.PhoneBuilder().build(),
            AuthUI.IdpConfig.GoogleBuilder().build())

        // Create and launch sign-in intent
        startActivityForResult(
            AuthUI.getInstance()
                .createSignInIntentBuilder()
                .setAvailableProviders(providers)
                .build(),
            RC_SIGN_IN)

    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == RC_SIGN_IN) {
            val response = IdpResponse.fromResultIntent(data)

            if (resultCode == Activity.RESULT_OK) {
                // Successfully signed in
                val user: FirebaseUser? = FirebaseAuth.getInstance().currentUser
                viewModel.prefs.putString("user", user?.displayName)
            } else {
                Toast.makeText(this, "FAILED TO SIGN IN", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val RC_SIGN_IN = 14
    }

}

open class TrivialDiff<T>: DiffUtil.ItemCallback<T>() {
    override fun areItemsTheSame(oldItem: T, newItem: T) = oldItem == newItem
    @SuppressLint("DiffUtilEquals")
    override fun areContentsTheSame(oldItem: T, newItem: T) = oldItem == newItem
}

abstract class VH <T> private constructor(val view: View) : RecyclerView.ViewHolder(view) {
    constructor(parent: ViewGroup, layout: Int) : this(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    abstract fun bind(data: T)
}

class StrVH(parent: ViewGroup) : VH<String>(parent, R.layout.one_string_line) {
    private val txt = view.lineText
    override fun bind(data: String) {
        txt.text = data
    }
}

data class Command(val caption: String, val onClick: () -> Unit = {})
class CommandVH(parent: ViewGroup) : VH<Command> (parent, R.layout.one_string_line) {
    private val text = view.lineText
    override fun bind(data: Command) {
        text.text = data.caption
        view.setOnClickListener { data.onClick.invoke() }
    }
}

class LA <T>(private val factory: (ViewGroup) -> VH<T>) : ListAdapter<T, VH<T>>(TrivialDiff()) {
    override fun submitList(list: List<T>?) {
        list?.let {
            super.submitList(it)
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH<T> = factory(parent)
    override fun onBindViewHolder(holder: VH<T>, position: Int) {
        val data = getItem(position)
        holder.bind(data)
    }
}

object Logger {
    val list = mutableListOf<String>()
    private val mainLooper = Handler(Looper.getMainLooper())
    var onAdd: () -> Unit = {}
    fun log(string: String) {
        mainLooper.post{list.add(string); onAdd()}
    }
}

fun log(string: String) = Logger.log(string)

class VM(context: Context): ViewModel() {
    val prefs = Prefs(context)
    val fb = DBase()
    private val mizzBronteMizzBronte = MizzBronte(context)

    fun content(words: Int) = mizzBronteMizzBronte.get(words)

    data class Message(val title: String, val message: String, val timeStamp: Date, val kind: Int = 1) {
        constructor(map: Map<String, Any>) : this (
            (map["Title"] as? String)?:"None",
            (map["Message"] as? String)?:"Message",
            (map["TimeStamp"] as? Timestamp)?.toDate()?:Date(0)
        )
        val map = mapOf("Kind" to kind, "Message" to message, "TimeStamp" to Timestamp(timeStamp), "Title" to title)
        val displayMessage = "[$timeStamp]\n$title: $message"
    }
    class DBase {
        private var db = FirebaseFirestore.getInstance()
        val currentUser get() = FirebaseAuth.getInstance().currentUser
        fun addMessage(message: Message) {
            db.collection("Messages")
                .add(message.map)
                .addOnSuccessListener { documentReference ->  log("Wrote ${documentReference.id}") }
                .addOnFailureListener{ e -> log("Failed on $e") }
        }

        fun getMessages(onNewMessages: (List<Message>) -> Unit) {
            db.collection("Messages").get().addOnSuccessListener { querySnapshot -> querySnapshot.documents
                    .mapNotNull { doc -> doc.data?.let{Message(it)} }
                    .let(onNewMessages)
            }
        }
    }

    class Prefs(context: Context) {
        fun putString(key: String, value: String?) {
            sharedPreferences.edit{ putString(key, value) }
            if (value != null) {
                sharedPreferences.edit().putString(key, value).apply()
            }
        }

        private val sharedPreferences = context.getSharedPreferences("SP", Context.MODE_PRIVATE)
    }
}