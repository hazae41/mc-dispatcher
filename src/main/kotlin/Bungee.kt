package hazae41.minecraft.dispatcher.bungee

import hazae41.minecraft.kotlin.bungee.*
import hazae41.minecraft.kotlin.catch
import hazae41.minecraft.sockets.Sockets.onSocketEnable
import hazae41.minecraft.sockets.Sockets.sockets
import hazae41.sockets.*
import io.ktor.http.cio.websocket.send
import kotlinx.coroutines.launch
import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.event.ChatEvent

class Plugin: BungeePlugin(){

    val sessions = mutableMapOf<ProxiedPlayer, (String) -> Unit>()

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
                    execute(command) { message ->
                        launch { send(message)  }
                    }
                }
            }
        }
    }

    override fun onEnable() {

        commands()

        listen<ChatEvent> {
            val player = it.sender as? ProxiedPlayer ?: return@listen
            if(player !in sessions) return@listen
            if(!it.isCommand) return@listen
            sessions[player]!!.invoke(it.message.drop(1))
            it.isCancelled = true
        }
    }
}

class Dispatcher(sender: CommandSender, val callback: (String) -> Unit): CommandSender by sender{
    override fun sendMessage(message: String) = callback(message)
    override fun sendMessages(vararg messages: String) = messages.forEach(::sendMessage)
    override fun sendMessage(message: BaseComponent) = sendMessage(message.toLegacyText())
    override fun sendMessage(vararg messages: BaseComponent) = messages.forEach(::sendMessage)
}

fun Plugin.execute(command: String, callback: (String) -> Unit) {
    val dispatcher = Dispatcher(proxy.console, callback)
    if(!proxy.pluginManager.dispatchCommand(dispatcher, command))
        dispatcher.msg("§cCommand not found")
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
    command("dispatch", "dispatch.use", "proxydispatch"){ argsList ->

        fun help(ex: Exception)
            = msg("/dispatch [-socket <socket>] <target> [command]")

        catch(::help){

            var socketName = Config.sockets[0]

            val args = argsList.parameters { key, value ->
                if(key == "socket") socketName = value
            }

            val socket = sockets[socketName]
            ?: return@catch msg("Unknown socket: $socketName")

            val target = args[0]
            val connection = socket.connections[target]
            ?: return@catch msg("Unknown target: $target")

            if(args.size >= 2){
                val command = args.drop(1).joinToString(" ")
                connection.conversation("/Dispatcher/dispatch"){
                    send(Config.password)
                    send(command)
                    msg("[$target] "+readMessage())
                }
            }

            else if(this !is ProxiedPlayer) {
                msg("&cYou can't use sessions!")
            }

            else {
                msg("&7Now sending commands to $target")
                msg("&7Type /exit to exit")
                connection.conversation("/Dispatcher/dispatch"){
                    send(Config.password)

                    sessions[this@command] = { command ->
                        if(command == "exit") {
                            sessions.remove(this@command)
                            msg("&7No longer sending commands to $target")
                        }
                        else launch { send(command) }
                    }
                    onMessage { msg("[$target] $it") }
                }
            }
        }
    }
}