import java.io.IOException
import java.lang.Thread.sleep
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

class TCPServer(
    port: Int = 8888,
    private val log: Boolean = true,
    private val readBufferSize: Int = 128 * 1024      // Размер буфера для чтения (по сколько будем читать)
) {
    private var serverName: String = "__Server__"     // username "__Server__" зарезервирован под сервер
    private var socketServer: ServerSocketChannel = ServerSocketChannel.open()
    // сокет, username и поток на чтение клиента
    private val clients = ConcurrentHashMap<SocketChannel, Pair<String, Thread>>()
    private var exit = false
    private var acceptThread: Thread

    init {
        socketServer.configureBlocking(false)
        socketServer.bind(InetSocketAddress(port))
        println("[${getTimeStr()}] Server started on port ${socketServer.socket().localPort}")
        acceptThread = Thread { accept() }.apply { start() }    // старт потока на подключение клиентов
        readConsole()           // main - поток на чтение консоли
    }

    private fun accept() {
        while (!exit) {
            sleep(500)
            val socketClient = socketServer.accept() ?: continue
            socketClient.configureBlocking(false)
            if (log)
                println("[${getTimeStr()}] Connected to me: ${socketClient.socket().inetAddress}")
            // Запускам поток на авторизацию клиента
            Thread { checkAuth(socketClient) }.start()
        }
        Thread.currentThread().interrupt()
    }

    /** Производит авторизацию пользователя.
     *  Закрывает соединение с сокетом сервера при неуспешной авторизации. **/
    private fun checkAuth(socket: SocketChannel) {
        val msg: Msg?
        try {
            msg = getMsg(socket)        // Ждём сообщение от клиента
        } catch (e: IOException) {
            socket.close()  // Закрываем соединение
            if (log)
                println("[${getTimeStr()}] Client rejected: ${socket.socket().inetAddress}")
            Thread.currentThread().interrupt()
            return
        }

        // Если получен пакет типа AUTH, username не занят и у пользователя корректное время
        val timeDiff = System.currentTimeMillis() / 1000L - msg.time
        if (msg.command == Command.AUTH && checkUsernames(msg.username) && timeDiff <= 30) {
            sendMsg(socket, Command.AUTH, serverName, ServersMsg.OK.message)    // Отправляем OK
            val readClientThread = Thread { readClientsMsg(socket) }            // Создаём поток на чтение
            clients[socket] = msg.username to readClientThread  // Добавляем в список клиентов, запоминаем ник и поток
            readClientThread.start()                            // Запускаем поток на чтение сокета

            if (log)
                println("[${getTimeStr()}] Client authorized: ${socket.socket().inetAddress} - ${msg.username}")

            // Отправляем всем пользователям, что подключился новый
            sendMsgForAll(
                Command.SEND_MSG,
                serverName,
                System.currentTimeMillis() / 1000L,
                "К нам подключился ${msg.username}!"
            )
        } else {
            // Отправляем сообщение об ошибке авторизации
            sendMsg(
                socket,
                Command.AUTH,
                serverName,
                if (timeDiff > 30)
                    ServersMsg.ERROR_TIME.message
                else
                    ServersMsg.AUTH_USERNAME_INVALID.message
            )
            socket.close()  // Закрываем соединение
            if (log)
                println("[${getTimeStr()}] Client rejected: ${socket.socket().inetAddress}")
        }
        Thread.currentThread().interrupt()
    }

    private fun readConsole() {
        val read = readLine()?.trim()
        // Если null -> EOF -> выход сочетанием клавиш
        if (read == null || read.startsWith("--stop")) {
            println("\n[${getTimeStr()}] Server shutdown command received.")
            exit()
        }
    }

    private fun exit() {
        exit = true
        acceptThread.interrupt()            // Останавливаем поток на подключение клиентов
        for (client in clients.values) {    // Останавливаем потоки на чтение клиентов
            client.second.interrupt()
        }
        // Закрываем сокеты клиентов и сообщаем им об этом
        println("\n[${getTimeStr()}] Disconnecting clients.")
        while (clients.isNotEmpty()) {
            val socket = clients.keys.last()
            closeClientsSocket(socket, ServersMsg.CLOSE_SERVER)
        }
        // Завершаем программу
        println("\n[${getTimeStr()}] Termination of the program.")
        exitProcess(0)
    }

    private fun checkUsernames(username: String): Boolean {
        if (username.lowercase() == serverName.lowercase())
            return false
        for (client in clients.values)
            if (username == client.first)
                return false
        return true
    }

    private fun readClientsMsg(socket: SocketChannel) {
        while (clients.containsKey(socket) && !exit) {  // Пока у нас есть общение с этим сокетом
            // Попытка получения сообщения
            var msg: Msg?
            try {
                msg = getMsg(socket)
            } catch (e: IOException) {
                if (!exit)
                    closeClientsSocket(socket, ServersMsg.ERROR_GET_MSG, sendMsgForThisClient = false)
                break
            }
            // Проверка корректности пакета
            val checkMsg = checkMsg(socket, msg)
            if (checkMsg != ServersMsg.OK) {
                closeClientsSocket(socket, checkMsg)
                break
            }
            // Если с пакетом всё ок, то смотрим на его тип и действуем согласно ему
            when (msg.command) {
                Command.SEND_MSG -> sendMsgForAll(msg)
                Command.SEND_FILE -> {
                    if (checkSendFileMsg(msg)) {
                        sendMsgForAll(Command.SEND_MSG, serverName, msg.time, "${msg.username} отправил файл.")
                        sendMsgForAll(msg, exceptSocket = socket)
                    } else
                        closeClientsSocket(socket, ServersMsg.ERROR_FILE_DATA)
                }
                Command.CLOSE -> closeClientsSocket(socket, ServersMsg.CLOSE_FROM_CLIENT, false)
                Command.AUTH -> closeClientsSocket(socket, ServersMsg.ERROR_REPEAT_AUTH)
            }
        }
        Thread.currentThread().interrupt()
    }

    /** Закрывает соединение с клиентом по указанной причине.
     *  sendMsgForThisClient указывает, нужно ли отправлять сообщение клиенту о закрытии соединения с ним (по умолчанию true) **/
    private fun closeClientsSocket(socket: SocketChannel, reason: ServersMsg, sendMsgForThisClient: Boolean = true) {
        if (!clients.keys.contains(socket))
            return

        if (sendMsgForThisClient)
            sendMsg(socket, Command.CLOSE, serverName, reason.message)

        sendMsgForAll(
            Command.SEND_MSG,
            serverName,
            System.currentTimeMillis() / 1000L,
            "Пользователь ${clients[socket]?.first} вышел из чата!",
            exceptSocket = socket
        )

        clients[socket]?.second?.interrupt()
        socket.close()
        if (log)
            println("[${getTimeStr()}] Client ${socket.socket().inetAddress}:${clients[socket]?.first} removed from chat. Reason: ${reason.name}. ${reason.message}")
        clients.remove(socket)
    }

    /** Проверка корректности пакета (доверие клиенту??):
     *  1. username совпадает с тем, что запомнил сервер.
     *  2. разница во времени не более 30 секунд.
     *  3. Поле даты не пустое при отправке файла или сообщения.
     *  4. Не повторная попытка авторизации. **/
    private fun checkMsg(socket: SocketChannel, msg: Msg): ServersMsg {
        if (msg.username != clients[socket]?.first)
            return ServersMsg.ERROR_USERNAME
        if (System.currentTimeMillis() / 1000L - msg.time > 30)
            return ServersMsg.ERROR_TIME
        if ((msg.command == Command.SEND_MSG || msg.command == Command.SEND_FILE) && msg.dataB.isEmpty())
            return ServersMsg.ERROR_DATA_EMPTY
        if (msg.command == Command.AUTH && clients.containsKey(socket))
            return ServersMsg.ERROR_REPEAT_AUTH
        return ServersMsg.OK
    }

    /** Отправляет заданное сообщение всем кроме exceptSocket. **/
    private fun sendMsgForAll(
        command: Command,
        username: String,
        time: Long,
        data: String,
        exceptSocket: SocketChannel? = null
    ) {
        if (clients.isNotEmpty())
            for (socket in clients.keys)
                if (socket != exceptSocket)
                    sendMsg(socket, command, username, data.toByteArray(Charsets.UTF_8), time)
    }

    /** Отправляет заданное сообщение всем кроме exceptSocket. **/
    private fun sendMsgForAll(msg: Msg, exceptSocket: SocketChannel? = null) {
        if (clients.isNotEmpty())
            for (socket in clients.keys)
                if (socket != exceptSocket)
                    sendMsg(
                        socket,
                        msg.command.value.toByte(),
                        msg.usernameB.toByteArray(),
                        msg.timeB.toByteArray(),
                        msg.dataB.toByteArray()
                    )
    }
}

enum class ServersMsg(val message: String) {
    OK("OK"),
    AUTH_USERNAME_INVALID("Username already exists or invalid."),
    ERROR_USERNAME("The username in the received package does not match the authorization username."),
    ERROR_SEND_MSG("Exception while sending package."),
    ERROR_FILE_DATA("Error in the data field when sending a file (file name or bytes are missing)."),
    ERROR_GET_MSG("Exception when receiving package."),
    ERROR_TIME("Incorrect time in the received packet."),
    ERROR_DATA_EMPTY("For a message or file sending packet, the \"data\" field should not be empty."),
    ERROR_REPEAT_AUTH("An authorization retry was received even though the socket is already authorized."),
    CLOSE_FROM_CLIENT("The client is disabled at his will."),
    CLOSE_SERVER("Server has been stopped.")
}