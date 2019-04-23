package hazae41.minecraft.dispatcher.bukkit

import hazae41.minecraft.kotlin.bukkit.*
import hazae41.minecraft.kotlin.catch
import hazae41.minecraft.sockets.Sockets.onSocketEnable
import hazae41.minecraft.sockets.Sockets.sockets
import hazae41.sockets.*
import io.ktor.http.cio.websocket.send
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerCommandPreprocessEvent

class Plugin: BukkitPlugin() {
    val sessions = mutableMapOf<Player, (String) -> Unit>()

    override fun onLoad() {
        update(9854)
        init(Config)

        onSocketEnable { name ->
            info("Making magic with $name")
            if(name !in Config.sockets) return@onSocketEnable
            onConversation("/Dispatcher/dispatch"){
                val (encrypt, decrypt) = aes()
                val password = readMessage().decrypt()
                if(password != Config.password) close()

                else onMessage { message ->
                    val command = message.decrypt()
                    execute(command) { message ->
                        launch { send(message.encrypt())  }
                    }
                }
            }
        }
    }

    override fun onEnable() {

        commands()

        listen<PlayerCommandPreprocessEvent> {
            if(it.player !in sessions) return@listen
            sessions[it.player]?.invoke(it.message.drop(1))
            it.isCancelled = true
        }
    }
}

class Dispatcher(sender: CommandSender, val callback: (String) -> Unit): CommandSender by sender{
    override fun sendMessage(message: String) = callback(message)
    override fun sendMessage(messages: Array<out String>) = messages.forEach(::sendMessage)
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

            if(args.size >= 2){
                val command = args.drop(1).joinToString(" ")
                connection.conversation("/Dispatcher/dispatch"){
                    val (encrypt, decrypt) = aes()
                    send(Config.password.encrypt())
                    send(command.encrypt())
                    msg("[$target] "+readMessage().decrypt())
                }
            }

            else if(this !is Player) {
                msg("&cYou can't use sessions!")
            }

            else {
                msg("&7Now sending commands to $target")
                msg("&7Type /exit to exit")
                connection.conversation("/Dispatcher/dispatch"){
                    val (encrypt, decrypt) = aes()
                    send(readMessage().encrypt())

                    sessions[this@command] = { command ->
                        if(command == "exit") {
                            sessions.remove(this@command)
                            msg("&7No longer sending commands to $target")
                        }
                        else launch { send(command.encrypt()) }
                    }
                    onMessage { msg("[$target] "+it.decrypt()) }
                }
            }
        }
    }
}
