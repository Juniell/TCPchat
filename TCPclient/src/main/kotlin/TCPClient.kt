import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Paths
import kotlin.system.exitProcess

class TCPClient(
    private val port: Int = 8888,
    private val inetAddress: InetAddress = InetAddress.getLocalHost()
) {
    private lateinit var socketServer: SocketChannel
    private var username = ""
    private var serverName = ""
    private val readThread = Thread { readChat() }
    private var exit = false

    init {
        login()
    }

    /** Метод авторизации клиента.**/
    private fun login() {
        println("Введите имя пользователя:")
        val username = readLine()?.trim()
        when {
            username == null -> {
                exit("Отключение сочетанием клавиш Ctrl+D", tellToServer = false)
                return
            }
            username.isEmpty() -> {
                println("Неверное имя пользователя.")
                login()
            }
            username.length > 10 || username.toByteArray().size > 10 -> {
                println("Имя пользователя должно быть меньше 10 символов.")
                login()
            }
            else -> {
                try {
                    socketServer = SocketChannel.open(InetSocketAddress(inetAddress, port))
                    println("Я подключился к серверу.")
                } catch (e: ConnectException) {
                    println("Сервер не отвечает.")
                    socketServer.close()
                    exitProcess(0)
                }

                println("Попытка авторизации.")
                sendMsg(Command.AUTH, "", username = username.trim())
                try {
                    val ans = getMsg()

                    if (ans.command == Command.AUTH && ans.data == "OK") {  // Если получилось авторизоваться
                        this.username = username.trim()  // запоминаем свой username
                        this.serverName = ans.username   // запоминаем username сервера
                        readThread.start()               // запускам поток на чтение сообщений
                        writeToChat()                    // читаем консоль
                    } else  // Если не получилось авторизоваться
                        exit(ans.data, tellToServer = false)      // отключаемся по указанной от сервера причине
                } catch (e: IOException) {
                    // Отключаемся, т.к. не удалось считать сообщение от сервера
                    exit("Сервер не отвечает.", tellToServer = false)
                }
            }
        }
    }

    private fun stopRead() {
        if (!readThread.isInterrupted)
            readThread.interrupt()
    }

    /** Отключение от сервера и закрытие приложения **/
    private fun exit(reason: String = "", tellToServer: Boolean = false) {
        if (exit)
            return
        exit = true
        println("Выход")
        if (reason.isNotEmpty())
            println("Причина: $reason")
        if (tellToServer)
            sendMsg(Command.CLOSE, "")
        if (this::socketServer.isInitialized)
            socketServer.close()
        exitProcess(0)
    }

    /** Чтение сообщений от сервера и их обработка. **/
    private fun readChat() {
        while (!exit && !Thread.currentThread().isInterrupted) {
            val msg: Msg
            try {
                msg = getMsg()
            } catch (e: IOException) {
                stopRead()
                exit(e.message ?: "Сервер не отвечает", tellToServer = false)
                return
            }
            when (msg.command) {
                Command.CLOSE -> if (msg.username == serverName) {
                    stopRead()
                    exit(msg.data, false)
                }
                Command.SEND_MSG -> println("[${getTimeStr(msg.time)}] ${msg.username}:\t${msg.data}")
                Command.SEND_FILE -> Thread { saveFile(msg) }.start()
                Command.AUTH -> {}  // не должно быть - скипаем
            }
        }
    }

    /** Сохранение файла. **/
    private fun saveFile(msg: Msg) {
        val data = msg.dataB
        for (i in 0 until data.size - 1)
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && i != data.indices.first && i + 1 != data.indices.last) {
                val fileName = data.slice(0 until i).toByteArray().toString(Charsets.UTF_8)
                val fileData = data.slice(i + 2 until data.size)
                try {
                    val path = Paths.get("").toAbsolutePath()
                        .toString() + File.separator + fileName
                    File(path).apply {
                        createNewFile()
                        writeBytes(fileData.toByteArray())
                    }
                    println("[${getTimeStr()}] Скачен файл пользователя ${msg.username}: $path")
                } catch (e: Exception) {
                    println("[${getTimeStr()}] Не удалось скачать файл, который отправил ${msg.username}")
                }
                return
            }
        Thread.currentThread().interrupt()
    }

    /** Чтение консоли и выполнение команд или отправка соответствующих сообщений. **/
    private fun writeToChat() {
        while (!exit && !Thread.currentThread().isInterrupted) {
            val msg = readLine()?.trim()

            // Если null -> EOF -> выход сочетанием клавиш
            if (msg == null) {
                stopRead()
                exit("Отключение сочетанием клавиш Ctrl+D", tellToServer = true)
                return
            }

            // Проверка корректности сообщения
            if (msg.isEmpty())
                println("\nСообщение не должно быть пустым.\n")
            else {
                // Проверка наличия команды
                if (msg.length >= 6)
                    when (msg.substring(0, 6)) {
                        "--file" -> {
                            try {
                                val file = File(msg.substringAfter(" "))
                                val fileBytes = file.readBytes()
                                val filename = file.name
                                val dataFileB = mutableListOf<Byte>()
                                dataFileB.addAll(filename.toByteArray().toList())
                                dataFileB.add(0.toByte())
                                dataFileB.add(0.toByte())
                                dataFileB.addAll(fileBytes.toList())
                                if (dataFileB.size > 16777215)
                                    println("[${getTimeStr()}] Выбранный файл (вместе с названием) превышает размер в 16 777 215 байт. Выберете другой файл.")
                                else
                                    sendMsg(Command.SEND_FILE, "", dataFileB.toByteArray())
                            } catch (e: FileNotFoundException) {
                                println("[${getTimeStr()}] Указан неверный путь до файла.")
                            }
                        }
                        "--exit" -> {
                            stopRead()
                            exit("Отключение командой --exit", tellToServer = true)
                        }
                        else -> sendMsg(Command.SEND_MSG, msg)
                    }
                else
                    sendMsg(Command.SEND_MSG, msg)
            }
        }
    }

    private fun sendMsg(command: Command, data: String, fileBytes: ByteArray = ByteArray(0), username: String = this.username) {
        val dataB = if (fileBytes.isEmpty())
            data.trim().toByteArray(Charsets.UTF_8)
        else
            fileBytes
        try {
            sendMsg(socketServer, command, username, dataB)
        } catch (e: IllegalArgumentException) {
            println("Сообщение не было отправлено. Некорректное сообщение: ${e.message}")
        } catch (e: IOException) {  // если сервер недоступен
            stopRead()
            exit(e.message ?: "Сервер не отвечает", tellToServer = false)
            return
        }
    }

    private fun getMsg(): Msg {
        var msg = getMsg(socketServer)
        while (msg.isEmpty())
            msg = getMsg(socketServer)
        return msg
    }

}