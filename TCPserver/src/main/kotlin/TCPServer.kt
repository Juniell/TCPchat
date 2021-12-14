import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

class TCPServer(
    port: Int = 8888,
    private val log: Boolean = true,
    private val readBufferSize: Int = 128 * 1024      // Размер буфера для чтения (по сколько будем читать)
) {
    private var serverName: String = "__Server__"     // username "__Server__" зарезервирован под сервер
    private var socketServer = ServerSocket(port)
    private val clients =
        ConcurrentHashMap<Socket, Pair<String, Thread>>()  // сокет, username и поток на чтение клиента
    private var exit = false
    private var acceptThread: Thread

    init {
        println("[${getTimeStr()}] Server started on port ${socketServer.localPort}")
        acceptThread = Thread { accept() }
        acceptThread.start()    // поток на подключение клиентов
        readConsole()           // поток на чтение консоли
    }

    private fun accept() {
        while (!exit) {
            val socketClient = socketServer.accept()
            if (log)
                println("[${getTimeStr()}] Connected to me: ${socketClient.inetAddress}")
            // Запускам поток на авторизацию клиента
            Thread { checkAuth(socketClient) }.start()
        }
        Thread.currentThread().interrupt()
    }

    /** Производит авторизацию пользователя.
     *  Закрывает соединение с сокетом сервера при неуспешной авторизации. **/
    private fun checkAuth(socket: Socket) {
        val msg: Msg?
        try {
            msg = getMsg(socket)        // Ждём сообщение от клиента
        } catch (e: IOException) {
            socket.close()  // Закрываем соединение
            if (log)
                println("[${getTimeStr()}] Client rejected: ${socket.inetAddress}")
            Thread.currentThread().interrupt()
            return
        }

        // Если получен пакет типа AUTH, username не занят и у пользователя корректное время
        val timeDiff = System.currentTimeMillis() / 1000L - msg.time
        if (msg.command == Command.AUTH && checkUsernames(msg.username) && timeDiff <= 30) {
            sendMsg(socket, Command.AUTH, serverName, ServersMsg.OK.message)  // Отправляем OK
            val readClientThread = Thread { readClientsMsg(socket) }    // Создаём поток на чтение
            clients[socket] = msg.username to readClientThread   // Добавляем в список клиентов, запоминаем ник и поток
            readClientThread.start()    // Запускаем поток на чтение сокета

            if (log)
                println("[${getTimeStr()}] Client authorized: ${socket.inetAddress} - ${msg.username}")

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
                println("[${getTimeStr()}] Client rejected: ${socket.inetAddress}")
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

    private fun readClientsMsg(socket: Socket) {
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

    private fun checkSendFileMsg(msg: Msg): Boolean {
        if (msg.data.size > 16777215)
            return false
        val data = msg.data
        for (i in 0 until data.size - 1)
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && i != data.indices.first && i + 1 != data.indices.last)
                return true
        return false
    }

    /** Закрывает соединение с клиентом по указанной причине.
     *  sendMsgForThisClient указывает, нужно ли отправлять сообщение клиенту о закрытии соединения с ним (по умолчанию true) **/
    private fun closeClientsSocket(socket: Socket, reason: ServersMsg, sendMsgForThisClient: Boolean = true) {
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
            println("[${getTimeStr()}] Client ${socket.inetAddress}:${clients[socket]?.first} removed from chat. Reason: ${reason.name}. ${reason.message}")
        clients.remove(socket)
    }

    /** Проверка корректности пакета (доверие клиенту??):
     *  1. username совпадает с тем, что запомнил сервер.
     *  2. разница во времени не более 30 секунд.
     *  3. Поле даты не пустое при отправке файла или сообщения.
     *  4. Не повторная попытка авторизации. **/
    private fun checkMsg(socket: Socket, msg: Msg): ServersMsg {
        if (msg.username != clients[socket]?.first)
            return ServersMsg.ERROR_USERNAME
        if (System.currentTimeMillis() / 1000L - msg.time > 30)
            return ServersMsg.ERROR_TIME
        if ((msg.command == Command.SEND_MSG || msg.command == Command.SEND_FILE) && msg.data.isEmpty())
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
        exceptSocket: Socket? = null
    ) {
        if (clients.isNotEmpty())
            for (socket in clients.keys)
                if (socket != exceptSocket)
                    sendMsg(socket, command, username, data.toByteArray(Charsets.UTF_8), time)
    }

    /** Отправляет заданное сообщение всем кроме exceptSocket. **/
    private fun sendMsgForAll(msg: Msg, exceptSocket: Socket? = null) {
        if (clients.isNotEmpty())
            for (socket in clients.keys)
                if (socket != exceptSocket)
                    sendMsg(
                        socket,
                        msg.command.value.toByte(),
                        msg.usernameB.toByteArray(),
                        msg.timeB.toByteArray(),
                        msg.data.toByteArray()
                    )
    }

    private fun sendMsg(socket: Socket, command: Command, username: String, dataB: ByteArray, timeConst: Long = -1L) {
        val commandB = command.value.toByte()
        val unB = username.trim().toByteArray(Charsets.UTF_8)
        val usernameB = mutableListOf<Byte>()
        if (unB.size < 10)
            for (i in 1..(10 - unB.size))
                usernameB.add(0)
        usernameB.addAll(unB.toList())
        val time = if (timeConst == -1L) System.currentTimeMillis() / 1000L else timeConst
        val timeB = mutableListOf<Byte>()
        for (i in 3 downTo 0)
            timeB.add((time ushr 8 * i).toByte())

        sendMsg(socket, commandB, usernameB.toByteArray(), timeB.toByteArray(), dataB)
    }

    private fun sendMsg(socket: Socket, command: Command, username: String, data: String, timeConst: Long = -1L) =
        sendMsg(socket, command, username, data.toByteArray(Charsets.UTF_8), timeConst)

    private fun sendMsg(socket: Socket, commandB: Byte, usernameB: ByteArray, timeB: ByteArray, dataB: ByteArray) {
        val msgB = mutableListOf<Byte>()
        val dataLen = dataB.size

        // Проверка на максимальный размер data
        if (dataLen > 16777215)
            throw IllegalArgumentException("The data field contains more than 16,777,215 bytes.")

        // Проверка корректной размерности полей
        val command = commandB.toUByte().toInt()
        if (command < 0 || command > 3 || usernameB.size != 10 || timeB.size != 4)
            throw IllegalArgumentException("The data sent was of the wrong size for sending.")

        // Формирование сообщения
        msgB.add(commandB)               // 1 байт: command (1 байт)
        val dataLenB = mutableListOf<Byte>()
        for (i in 2 downTo 0)
            msgB.add((dataLen ushr 8 * i).toByte())

        msgB.addAll(dataLenB)            // 2-4 байты: dataLen (3 байта)
        msgB.addAll(usernameB.toList())  // 5-14 байты: username (10 байт)
        msgB.addAll(timeB.toList())      // 15-18 байты: time (4 байта)
        msgB.addAll(dataB.toList())      // 19 и далее байты: data

        /** Отправка сформированного сообщения **/
        try {
            val out = socket.getOutputStream()
            out.write(msgB.toByteArray())
            if (log)
                if (command == Command.SEND_FILE.value)
                    println("[${getTimeStr()}] <$serverName to ${socket.inetAddress}:${clients[socket]?.first}>: *Sending the file.*")
                else
                    println(
                        "[${getTimeStr()}] <$serverName to ${socket.inetAddress}:${clients[socket]?.first}>: ${
                            dataB.toString(
                                Charsets.UTF_8
                            )
                        }"
                    )

        } catch (e: IOException) {
            // Если поймали исключение, значит пользователь отключился, поэтому отключаемся от него
            closeClientsSocket(socket, ServersMsg.ERROR_SEND_MSG, false)
            return
        }
    }

    private fun getMsg(socket: Socket): Msg {
        val input = socket.getInputStream()

        if (exit)
            throw IOException(ServersMsg.CLOSE_SERVER.message)

        /** Чтение первых 18 байтов **/
        val bytes = ByteArray(18)
        val read = input.read(bytes, 0, 18)
        if (read == -1)
            throw SocketException(ServersMsg.ERROR_GET_MSG.message)

        /** 1 байт: command **/
        val command = when (bytes[0].toUByte().toInt()) {   // Получаем код команды
            0 -> Command.SEND_MSG
            1 -> Command.SEND_FILE
            2 -> Command.AUTH
            else -> Command.CLOSE
        }

        /** 2-4 байты: dataLen **/
        var dataLen = bytes[1].toUByte().toInt()
        for (i in 2..3)
            dataLen = (dataLen shl 8).or(bytes[i].toUByte().toInt())

        /** 5-14 байты: username **/
        val usernameB = bytes.slice(4..13)
        val username = usernameB.dropWhile { it == 0.toByte() }.toByteArray().toString(Charsets.UTF_8)

        /** 15-18 байты: time **/
        val timeB = bytes.slice(14..17)
        var timeI = timeB[0].toUByte().toInt()
        for (i in 1..3)
            timeI = (timeI shl 8).or(timeB[i].toUByte().toInt())

        /** 19 и следующие dataLen байты: data **/
        val dataB = mutableListOf<Byte>()

        var readied = 0
        while (readied != dataLen) {
            val residue = dataLen - readied

            val needReed = if (residue < readBufferSize) residue else readBufferSize

            var bytesData = ByteArray(needReed)
            val readData = input.read(bytesData, 0, needReed)
            if (readData == -1)
                throw SocketException("Сервер не отвечает.")
            if (readData < needReed)
                bytesData = bytesData.dropLast(needReed - readData).toByteArray()
            readied += bytesData.size
            dataB.addAll(bytesData.toList())
        }

//        val data = dataB.toByteArray().toString(Charsets.UTF_8)

        val data = if (command == Command.SEND_FILE)
            ""
        else
            dataB.toByteArray().toString(Charsets.UTF_8)

        if (log)
            if (command == Command.SEND_FILE)
                println("[${getTimeStr()}] <${socket.inetAddress}:${clients[socket]?.first} to $serverName>: *Sending the file.*")
            else
                println("[${getTimeStr()}] <${socket.inetAddress}:${clients[socket]?.first} to $serverName>: $data")

        return Msg(command, username, usernameB, timeI.toLong(), timeB, dataB)
    }
}

private fun getTimeStr(l: Long = System.currentTimeMillis() / 1000L): String? {
    val sdf = SimpleDateFormat("HH:mm")
    val date = Date(l * 1000)
    return sdf.format(date)
}

data class Msg(
    val command: Command,
    val username: String,
    val usernameB: List<Byte>,
    val time: Long,
    val timeB: List<Byte>,
    val data: List<Byte>
)

enum class Command(val value: Int) {
    SEND_MSG(0),
    SEND_FILE(1),
    AUTH(2),
    CLOSE(3)
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