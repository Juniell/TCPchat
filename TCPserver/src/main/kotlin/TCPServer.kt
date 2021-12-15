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
    private val clients = ConcurrentHashMap<SocketChannel, String>()
    private val socketsForAccept = mutableListOf<SocketChannel>()
    private var exit = false
    private val acceptThread = Thread { accept() }
    private val readClientsThread = Thread { readClients() }
    private val authThread = Thread { auth() }

    init {
        socketServer.configureBlocking(false)
        socketServer.bind(InetSocketAddress(port))
        println("[${getTimeStr()}] Server started on port ${socketServer.socket().localPort}")
        acceptThread.start()        // старт потока на подключение клиентов
        readClientsThread.start()   // старт потока на чтение сообщений клиентов todo: останавливать / запускать поток, если нет клиентов / появился первый??
        authThread.start()          // старт потока на авторизацию
        readConsole()               // main - поток на чтение консоли
    }

    private fun readConsole() {
        val read = readLine()?.trim()
        // Если null -> EOF -> выход сочетанием клавиш
        if (read == null || read.startsWith("--stop")) {
            println("\n[${getTimeStr()}] Server shutdown command received.")
            exit()
        }
    }

    private fun accept() {
        while (!exit) {
            sleep(500)
            val socketClient = socketServer.accept() ?: continue
            socketClient.configureBlocking(false)
            if (log)
                println("[${getTimeStr()}] Connected to me: ${socketClient.socket().inetAddress}")
            // Помещаем клиента в список для авторизации
            socketsForAccept.add(socketClient)
        }
        Thread.currentThread().interrupt()
    }

    /** Обрабатывает список сокетов на авторизацию **/
    private fun auth() {
        while (!exit) {
            sleep(500)
            for (socket in socketsForAccept.toList()) {
                var msg: Msg?
                try {
                    msg = getMsg(socket)    // Пытаемся считать сообщение
                } catch (e: IOException) {  // Исключение, если сокет закрыт -> закрываем сокет и идём дальше
                    socket.close()
                    socketsForAccept.remove(socket)
                    continue
                }
                if (msg.isEmpty())          // Если было получено пустое сообщение
                    continue                // то этот клиент пока не прислал сообщение не авторизацию -> переходим к следующему
                else {                      // Если было получено сообщение
                    checkAuth(socket, msg)  // проверяем и проводим авторизацию
                    printLogAboutGetMsg(socket, msg.command.value, msg.data)    // Выводим в логи полученное сообщение
                    socketsForAccept.remove(socket)
                    continue
                }
            }
        }
        Thread.currentThread().interrupt()
    }

    /** Производит авторизацию пользователя.
     *  Закрывает соединение с сокетом при неуспешной авторизации. **/
    private fun checkAuth(socket: SocketChannel, msg: Msg) {
        // Если получен пакет типа AUTH, username не занят и у пользователя корректное время
        val timeDiff = System.currentTimeMillis() / 1000L - msg.time

        if (msg.command == Command.AUTH && checkUsernames(msg.username) && timeDiff <= 30) {
            sendMsgOrDisconnect(
                socket,
                Command.AUTH,
                serverName,
                ServersMsg.OK.message.toByteArray(Charsets.UTF_8)
            )    // Отправляем OK
            clients[socket] = msg.username  // Добавляем в список клиентов и запоминаем ник

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
            sendMsgOrDisconnect(
                socket,
                Command.AUTH,
                serverName,
                if (timeDiff > 30)
                    ServersMsg.ERROR_TIME.message.toByteArray(Charsets.UTF_8)
                else
                    ServersMsg.AUTH_USERNAME_INVALID.message.toByteArray(Charsets.UTF_8)
            )
            socket.close()  // Закрываем соединение
            if (log)
                println("[${getTimeStr()}] Client rejected: ${socket.socket().inetAddress}")
        }
    }

    /** Проверяет по очереди всех клиентов на наличие новых сообщений **/
    private fun readClients() {
        while (!exit) {
            sleep(500)
            for (socket in clients.keys) {  // Проходимся по списку клиентов
                var msg: Msg?
                try {
                    msg = getMsg(socket)    // Пытаемся считать сообщение
                } catch (e: IOException) {  // Исключение, если сокет закрыт -> отключаем клиента и идём дальше
                    if (!exit)
                        closeClientsSocket(socket, ServersMsg.ERROR_GET_MSG, sendMsgForThisClient = false)
                    continue
                }
                if (msg.isEmpty())          // Если было получено пустое сообщение
                    continue                // значит этот клиент ничего не писал -> переходим к следующему
                else {                      // Если было получено непустое сообщение
                    printLogAboutGetMsg(socket, msg.command.value, msg.data)    // выводим в логи полученное сообщение
                    processMsg(socket, msg) // обрабатываем его
                    // todo: выделить отдельный поток для обработки очереди сообщений???
                }
            }
        }
        Thread.currentThread().interrupt()
    }

    /** Обрабатывает полученные сообщения **/
    private fun processMsg(socket: SocketChannel, msg: Msg) {
        // Проверка корректности пакета
        val checkMsg = checkMsg(socket, msg)
        if (checkMsg != ServersMsg.OK)
            closeClientsSocket(socket, checkMsg)

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

    private fun checkUsernames(username: String): Boolean {
        if (username.lowercase() == serverName.lowercase())
            return false
        for (client in clients.values)
            if (username == client)
                return false
        return true
    }

    private fun exit() {
        exit = true
        acceptThread.interrupt()            // Остановка потока на подключение клиентов
        readClientsThread.interrupt()       // Остановка потока на чтение клиентов
        authThread.interrupt()              // Остановка потока на авторизацию

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

    /** Закрывает соединение с клиентом по указанной причине.
     *  sendMsgForThisClient указывает, нужно ли отправлять сообщение клиенту о закрытии соединения с ним (по умолчанию true) **/
    private fun closeClientsSocket(socket: SocketChannel, reason: ServersMsg, sendMsgForThisClient: Boolean = true) {
        if (!clients.keys.contains(socket))
            return

        val username = clients[socket]

        // Если необходимо, сообщаем клиенту о его отключении
        if (sendMsgForThisClient)
            sendMsgOrDisconnect(socket, Command.CLOSE, serverName, reason.message.toByteArray())

        if (log)
            println("[${getTimeStr()}] Client ${socket.socket().inetAddress}:${clients[socket]} removed from chat. Reason: ${reason.name}. ${reason.message}")

        // Закрываем сокет и удаляем из списка клиентов
        socket.close()
        clients.remove(socket)

        // Сообщаем всем клиентам, что этот клиент отключается
        sendMsgForAll(
            Command.SEND_MSG,
            serverName,
            System.currentTimeMillis() / 1000L,
            "Пользователь $username вышел из чата!",
            exceptSocket = socket
        )
    }

    /** Проверка корректности пакета (доверие клиенту??):
     *  1. username совпадает с тем, что запомнил сервер.
     *  2. разница во времени не более 30 секунд.
     *  3. Поле даты не пустое при отправке файла или сообщения.
     *  4. Не повторная попытка авторизации. **/
    private fun checkMsg(socket: SocketChannel, msg: Msg): ServersMsg {
        if (msg.username != clients[socket])
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
                    sendMsgOrDisconnect(socket, command, username, data.toByteArray(Charsets.UTF_8), time)
    }

    /** Отправляет заданное сообщение всем кроме exceptSocket. **/
    private fun sendMsgForAll(msg: Msg, exceptSocket: SocketChannel? = null) {
        if (clients.isNotEmpty())
            for (socket in clients.keys)
                if (socket != exceptSocket)
                    sendMsgOrDisconnect(
                        socket,
                        msg.command.value.toByte(),
                        msg.usernameB.toByteArray(),
                        msg.timeB.toByteArray(),
                        msg.dataB.toByteArray()
                    )
    }

    /** Обёртка для библиотечной функции, чтобы обрабатывать исключения**/
    private fun sendMsgOrDisconnect(
        socket: SocketChannel,
        commandB: Byte,
        usernameB: ByteArray,
        timeB: ByteArray,
        dataB: ByteArray
    ) {
        try {
            sendMsg(socket, commandB, usernameB, timeB, dataB)
            printLogAboutSendMsg(socket, commandB.toUByte().toInt(), dataB)
        } catch (e: IOException) {
            closeClientsSocket(socket, ServersMsg.ERROR_SEND_MSG, sendMsgForThisClient = false)
        }
    }

    /** Обёртка для библиотечной функции, чтобы обрабатывать исключения**/
    private fun sendMsgOrDisconnect(
        socket: SocketChannel,
        command: Command,
        username: String,
        dataB: ByteArray,
        timeConst: Long = -1L
    ) {
        try {
            sendMsg(
                socket,
                command,
                username,
                dataB,
                if (timeConst == -1L) System.currentTimeMillis() / 1000 else timeConst
            )
            printLogAboutSendMsg(socket, command.value, dataB)
        } catch (e: IOException) {
            closeClientsSocket(socket, ServersMsg.ERROR_SEND_MSG, sendMsgForThisClient = false)
        }
    }

    /** Вывод логов при отправке сообщения**/
    private fun printLogAboutSendMsg(socket: SocketChannel, commandVal: Int, dataB: ByteArray) {
        if (log)
            println(
                if (commandVal == Command.SEND_FILE.value)
                    "[${getTimeStr()}] <$serverName to ${socket.socket().inetAddress}:${clients[socket]}>: *Sending the file.*"
                else
                    "[${getTimeStr()}] <$serverName to ${socket.socket().inetAddress}:${clients[socket]}>: ${
                        dataB.toString(Charsets.UTF_8)
                    }"
            )
    }

    private fun printLogAboutGetMsg(socket: SocketChannel, commandVal: Int, data: String) {
        if (log)
            if (commandVal == Command.SEND_FILE.value)
                println("[${getTimeStr()}] <${socket.socket().inetAddress}:${clients[socket]} to $serverName>: *Sending the file.*")
            else
                println("[${getTimeStr()}] <${socket.socket().inetAddress}:${clients[socket]} to $serverName>: $data")
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