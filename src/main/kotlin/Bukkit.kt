package hazae41.minecraft.dispatcher.bukkit

import hazae41.minecraft.kotlin.bukkit.*
import hazae41.minecraft.kotlin.catch
import hazae41.minecraft.sockets.Sockets.onSocketEnable
import hazae41.minecraft.sockets.Sockets.sockets
import hazae41.sockets.*
import io.ktor.http.cio.websocket.send
import kotlinx.coroutines.launch
import net.md_5.bungee.api.chat.BaseComponent
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.command.RemoteConsoleCommandSender
import org.bukkit.conversations.Conversation
import org.bukkit.conversations.ConversationAbandonedEvent
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import java.util.*

class Plugin: BukkitPlugin() {
    val sessions = mutableMapOf<UUID, (String) -> Unit>()

    override fun onLoad() {
        update(9854)
        init(Config)

        onSocketEnable { name ->
            info("Making magic with $name")
            if(name !in Config.sockets) return@onSocketEnable
            onConversation("/Dispatcher/dispatch"){
                val password = readMessage()
                if(password != Config.password) close()

                else onMessage { message ->
                    val command = message
                    schedule{
                        execute(command) { message ->
                            launch { send(message)  }
                        }
                    }
                }
            }
        }
    }

    override fun onEnable() {

        commands()

        listen<PlayerCommandPreprocessEvent> {
            if(it.player.uniqueId !in sessions) return@listen
            sessions[it.player.uniqueId]?.invoke(it.message.drop(1))
            it.isCancelled = true
        }
    }
}

class Dispatcher(sender: ConsoleCommandSender, val callback: (String) -> Unit): ConsoleCommandSender by sender{
    override fun sendMessage(message: String) = callback(message)
    override fun sendMessage(messages: Array<out String>) = messages.forEach(::sendMessage)
    override fun sendRawMessage(message: String) = sendMessage(message)
    override fun spigot() = object: CommandSender.Spigot(){
        override fun sendMessage(component: BaseComponent) = sendMessage(component.toLegacyText())
        override fun sendMessage(vararg components: BaseComponent) = components.forEach{sendMessage(it)}
    }
}

fun Plugin.execute(command: String, callback: (String) -> Unit) {
    val dispatcher = Dispatcher(server.consoleSender, callback)
    if(!server.dispatchCommand(dispatcher, command))
        dispatcher.msg("Unknown command. Type /help for help.")
}

object Config: ConfigFile("config"){
    val sockets by stringList("sockets")
    val password by string("password")
}

fun Array<String>.parameters(callback: (String, String) -> Unit): List<String> {
    val it = iterator()
    var remaining = toList()
    while(it.hasNext()){
        val arg = it.next()
        if(!arg.startsWith("-")) break
        val key = arg.drop(1)
        val value = it.next()
        remaining = remaining.drop(2)
        callback(key, value)
    }
    return remaining
}

fun Plugin.commands() {
    command("dispatch", "dispatch.use", "serverdispatch"){ argsList ->

        fun help(ex: Exception)
            = msg("&c/dispatch [-socket <socket>] <target> [command]")

        catch(::help){

            var socketName = Config.sockets[0]

            val args = argsList.parameters { key, value ->
                if(key == "socket") socketName = value
            }

            val socket = sockets[socketName]
                ?: return@catch msg("&7Unknown socket: $socketName")

            val target = args[0]
            val connection = socket.connections[target]
                ?: return@catch msg("&7Unknown target: $target")

            when {
                (args.size >= 2) -> {
                    val command = args.drop(1).joinToString(" ")
                    connection.conversation("/Dispatcher/dispatch"){
                        send(Config.password)
                        send(command)
                        onMessage { msg("[$target] $it") }
                    }
                }
                (this is Player) -> {
                    msg("&7Now sending commands to $target")
                    msg("&7Type /exit to exit")
                    connection.conversation("/Dispatcher/dispatch"){
                        send(Config.password)

                        sessions[uniqueId] = { command ->
                            if(command == "exit") {
                                sessions.remove(uniqueId)
                                msg("&7No longer sending commands to $target")
                            }
                            else launch { send(command) }
                        }

                        onMessage { msg("[$target] $it") }
                    }
                }
                else -> msg("&cYou can't use sessions!")
            }
        }
    }
}
